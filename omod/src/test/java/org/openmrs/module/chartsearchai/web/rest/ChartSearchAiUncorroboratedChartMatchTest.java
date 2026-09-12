/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.reference.SafetyWarningFixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A contraindication chip says whether the chart match behind it is corroborated (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/374">#374</a>).
 *
 * <p>Issue #309 gave a CONDITION contraindication rule a corroborating leg, so the injected
 * {@code drug_reference} record and the {@code safety_finding} beside it both hedge a rule whose
 * token reached a recorded condition only mid-word. The clinician-facing chip did not: the module
 * held the answer on {@code SafetyWarning.restsOnAnUncorroboratedChartMatch()} and nothing a
 * {@code /search} consumer reads carried it, so on that issue's own reproduction the two surfaces
 * disagreed about one chart — the records hedging and the chip asserting.
 *
 * <p><b>Three guards over one key, and none of them subsumes another.</b> The case here reads the
 * published value off the SSE bytes for three chips, two of which carry ONE sentence, which is
 * the defect stated as behaviour. The source pin below asserts the value is READ off the accessor
 * rather than recomputed beside it, which no value comparison can see. And
 * {@code ChartSearchAiSafetyWarningSeverityWireTest}'s reflective guard compares every public
 * accessor's own reading against the key it names, over a fixture that since #374 carries a chip
 * answering true — which is what generalises the comparison past this class's own chips. Mutate
 * the serializer's put and read all three.
 *
 * <p>Two things are deliberately NOT asserted here, each because something else already holds them.
 * That the key reaches {@code GET /chartsearchai/chartalerts} is
 * {@code ChartSearchAiChartAlertsTest.everyFindingIsShapedExactlyAsASearchChipIs}, which reads the key
 * set off a live {@code /search} chip rather than listing it — a hand-written list here is the thing
 * that case's javadoc forbids. And that the payload still marshals for an XML client is
 * {@code ChartSearchAiChartOrderBridgeTest.theWholePayloadStillMarshalsForAnXmlClient} through the
 * shared {@code XmlPayloads.assertMarshals}; this key adds no new hazard there, and
 * {@code ChartSearchAiRestController.serializeSafetyWarnings} is canonical for why.
 */
public class ChartSearchAiUncorroboratedChartMatchTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The hazard case's own sentence, from issue #309's reproduction and quoted in #374. */
	private static final String UNCORROBORATED_DETAIL =
			"Naltrexone is contraindicated by an active condition: acute hepatitis or liver failure";

	/** Chip 1's sentence, whose rule the chart DOES corroborate. */
	private static final String UNHEDGED_DETAIL =
			"Ibuprofen is contraindicated by the recorded condition Peptic ulcer disease.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ContraindicationChipStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/**
	 * Three contraindication chips whose sentences are equally categorical and whose provenance
	 * answers differ — the arrangement the key exists for. Chip 0 is built by the curated-rule arm's
	 * own factory; chips 1 and 2 by the public constructor, which answers false the way the allergen
	 * arm's sentences do.
	 *
	 * <p><b>Chip 2 carries chip 0's sentence verbatim, and that is what makes the value assertions
	 * below discriminate anything.</b> Without it every published field except this key separates the
	 * two answers — chip 0's detail is the only one naming an active condition — so a serializer
	 * re-deriving the value from {@code detail} agrees with the accessor on every chip and passes.
	 * Measured on this change's own polish round: with the pinned put commented out and a
	 * {@code detail}-sniff written beside it, the whole omod suite stayed green. Chips 0 and 2 are now
	 * byte-identical in {@code type}, {@code drug}, {@code detail}, {@code severity} and
	 * {@code chartOrderBridges}, so no function of any other published field can agree with the
	 * accessor on both, and the re-derivation reddens on BEHAVIOUR rather than on a source scan.
	 */
	private static List<SafetyWarning> chips() {
		return Arrays.asList(
			SafetyWarningFixtures.uncorroboratedContraindication("Naltrexone", UNCORROBORATED_DETAIL),
			new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", UNHEDGED_DETAIL),
			new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Naltrexone", UNCORROBORATED_DETAIL));
	}

	/**
	 * The chips as the {@code done} event actually serialized them.
	 *
	 * <p>Read off the SSE bytes and not off the blocking handler's {@code Map}, for the reason
	 * {@code ChartSearchAiSafetyWarningSeverityWireTest} reads that surface for its own "present and
	 * null": the controller serializes the SSE payloads itself, so this is the JSON a client receives,
	 * while the {@code /search} body is a map Spring has not serialized yet. What is being asked of the
	 * bytes is that a primitive {@code boolean} survives as a JSON boolean rather than being dropped or
	 * stringified.
	 */
	private JsonNode streamedChips() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), "Can I give her naltrexone?",
			new User(3), false);
		JsonNode chips = SseEvents.dataOfType(out, "done", MAPPER).get("safetyWarnings");
		assertNotNull(chips, "the done event carried no safetyWarnings key");
		assertEquals(3, chips.size(), "precondition: this arrangement raises three chips, was: " + chips);
		return chips;
	}

	/**
	 * The chip states the module's own provenance answer, so the chip surface no longer withholds what
	 * the two injected records say.
	 *
	 * <p><b>This is the defect itself.</b> On issue #309's reproduction the injected
	 * {@code drug_reference} record and the {@code safety_finding} beside it both hedged while the chip
	 * asserted the contraindication, and the module held the answer with no path to a client. All three
	 * chips here carry equally categorical sentences and the answer is asserted of each, because a key
	 * reading true for every contraindication chip would satisfy a one-sided reading of this case while
	 * saying nothing. <b>Nothing but this key tells chips 0 and 2 apart</b> — that pair, and not chips 0
	 * and 1, is what {@link #chips()} added chip 2 for.
	 *
	 * <p>It does not assert that {@code detail} changed, because it did not — ADR Decision 92 for why
	 * the sentence was not hedged instead.
	 */
	@Test
	public void theChipStatesWhetherItsChartMatchIsCorroborated() throws Exception {
		JsonNode chips = streamedChips();

		JsonNode uncorroborated = chips.get(0);
		assertEquals(UNCORROBORATED_DETAIL, uncorroborated.get("detail").asText(),
			"precondition: chip 0 is the hazard case's own sentence");
		JsonNode published = uncorroborated.get("restsOnAnUncorroboratedChartMatch");
		assertNotNull(published,
			"a contraindication chip must state whether anything corroborates the chart match behind "
					+ "it — issue #374. Chip was: " + uncorroborated);
		assertTrue(published.isBoolean(),
			"it must reach a client as a JSON boolean rather than as a string: " + uncorroborated);
		assertTrue(published.asBoolean(),
			"the module knows nothing corroborates this match; the chip must say so: " + uncorroborated);

		JsonNode unhedged = chips.get(1);
		assertEquals(UNHEDGED_DETAIL, unhedged.get("detail").asText(),
			"precondition: chip 1 is the one whose rule the chart corroborates");
		assertNotNull(unhedged.get("restsOnAnUncorroboratedChartMatch"),
			"the key is present on every chip, not only on the ones that answer true: " + unhedged);
		assertFalse(unhedged.get("restsOnAnUncorroboratedChartMatch").asBoolean(),
			"a chip with nothing to hedge must not be hedged: " + unhedged);

		// The pair that makes the two assertions above discriminate: chip 2 is chip 0's sentence
		// verbatim and answers false, so nothing but this key separates them. A value re-derived from
		// any other published field reddens HERE, which is what stops the source pin below from being
		// the only thing standing between a maintainer and a detail-sniff.
		JsonNode twin = chips.get(2);
		assertEquals(uncorroborated.get("detail").asText(), twin.get("detail").asText(),
			"precondition: chip 2 carries chip 0's sentence verbatim");
		assertEquals(uncorroborated.get("drug").asText(), twin.get("drug").asText(),
			"precondition: and its drug");
		assertFalse(twin.get("restsOnAnUncorroboratedChartMatch").asBoolean(),
			"two chips with one sentence must still publish their own provenance answers, or the value "
					+ "is being read off something other than the chip: " + twin);
	}

	/**
	 * The chip's provenance answer is published by READING THE ACCESSOR, once, in the one serializer.
	 *
	 * <p>Scoped to {@code serializeSafetyWarnings}' own body and not to the file, for the reason
	 * {@code ChartSearchAiInteractionPairExtentTest} gives of its own scoping: asked of the whole
	 * source, a put anywhere in the controller would satisfy it, including one on a payload this
	 * chip's array is not part of.
	 *
	 * <p><b>Counted rather than matched as a statement, and over comment-stripped source</b> — the
	 * idiom {@code ChartSearchAiChartOrderBridgeTest} uses for the sibling key. It asks that exactly one
	 * live statement names the key and exactly one names the accessor, with its receiver so a javadoc
	 * {@code @link} cannot count as a read. Do not tighten it to a whitespace-exact match of the whole
	 * {@code map.put(..)}: that reddens when the accessor read is hoisted into a local, which changes
	 * nothing this guard is about.
	 *
	 * <p><b>It is not what stops a re-derivation, and must not be relied on as such.</b> That is
	 * {@link #theChipStatesWhetherItsChartMatchIsCorroborated}'s identical-detail chip pair, which
	 * makes any value computed from another published field disagree with the accessor on behaviour.
	 * This case adds the thing no value comparison can see: that the published value came from the
	 * accessor rather than from something that happens to agree with it on this fixture — the
	 * two-resolutions-that-agree shape issue #151 records.
	 */
	@Test
	public void theSerializerPublishesTheChipsOwnProvenanceAnswer() throws Exception {
		String body = liveCode(ChartSearchAiStreamingTest.bodyOf(
			ChartSearchAiStreamingTest.controllerSource(),
			"private List<Map<String, Object>> serializeSafetyWarnings("));

		int keys = ChartSearchAiStreamingTest.occurrences(body, "\"restsOnAnUncorroboratedChartMatch\"");
		assertEquals(1, keys,
			"the key must be written in exactly one live statement inside the one method that builds a "
					+ "chip's wire map (issue #374). Found " + keys + " in: " + body);
		int reads = ChartSearchAiStreamingTest.occurrences(body,
			"warning.restsOnAnUncorroboratedChartMatch()");
		assertEquals(1, reads,
			"and it must be read straight off the chip, once — a second reader, or none, is a site "
					+ "re-deriving the provenance answer instead of publishing it. Found " + reads
					+ " in: " + body);
	}

	/**
	 * @return {@code source} with its comments removed — {@code //} to end of line, and {@code /*} to
	 *         its close — so a guard over it cannot be satisfied by a commented-out copy of the
	 *         statement it looks for.
	 *
	 *         <p><b>Both comment forms are stripped, and the block form is the one that matters.</b>
	 *         Stripping only {@code //} leaves a block-commented {@code map.put(..)} in the "live" text
	 *         to be COUNTED, so the guard passes on a dead statement — fail-open, and the exact defect
	 *         stripping {@code //} exists to close. A round of this change's own review found that
	 *         hole, in a paragraph that had claimed the residues could only fail closed.
	 *
	 *         <p>One residue is left and is named rather than claimed away: a comment opener inside a
	 *         STRING LITERAL starts a strip that the literal did not. Handling that needs literal
	 *         tracking, which is a parser. It can only REMOVE text, so it can only make a count too
	 *         LOW — and the counts above are equalities, so a count of one that should be two reads as
	 *         a divergence rather than as agreement. What bounds this method altogether is that it
	 *         guards SPELLING; what stops a re-derivation is the chip pair in
	 *         {@link #theChipStatesWhetherItsChartMatchIsCorroborated}, which reads values.
	 */
	private static String liveCode(String source) {
		StringBuilder out = new StringBuilder(source.length());
		boolean inBlock = false;
		for (String line : source.split("\n", -1)) {
			StringBuilder kept = new StringBuilder(line.length());
			for (int i = 0; i < line.length(); i++) {
				if (inBlock) {
					if (line.startsWith("*/", i)) {
						inBlock = false;
						i++;
					}
					continue;
				}
				if (line.startsWith("//", i)) {
					break;
				}
				if (line.startsWith("/*", i)) {
					inBlock = true;
					i++;
					continue;
				}
				kept.append(line.charAt(i));
			}
			out.append(kept).append('\n');
		}
		return out.toString();
	}

	/** Returns this class's fixture chips on every path the controller can take. */
	private static class ContraindicationChipStubService implements ChartSearchService {

		private ChartAnswer answer() {
			return new ChartAnswer("Naltrexone is contraindicated [1].",
					Collections.<RecordReference> emptyList(), 0, 0, 0, chips(), null, null, null);
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept("Naltrexone is contraindicated [1].");
			citationsConsumer.accept(answer().getReferences());
			// Production's own early-done shape: built before validation runs, so it carries no chips.
			ungroundedAnswerConsumer.accept(
					new ChartAnswer("Naltrexone is contraindicated [1].",
							Collections.<RecordReference> emptyList()));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
