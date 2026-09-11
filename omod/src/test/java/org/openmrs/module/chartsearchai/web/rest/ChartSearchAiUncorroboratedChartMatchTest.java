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
 * published value off the SSE bytes for two chips whose sentences are equally categorical, which is
 * the defect stated as behaviour. The source pin below asserts the value is READ off the accessor
 * rather than recomputed beside it, which no value comparison can see. And
 * {@code ChartSearchAiSafetyWarningSeverityWireTest}'s reflective guard compares every public
 * accessor's own reading against the key it names, over a fixture that since #374 carries a chip
 * answering true — which is what generalises the comparison past this class's own two chips. Mutate
 * the serializer's put and read all three.
 *
 * <p>Two things are deliberately NOT asserted here, each because something else already holds them.
 * That the key reaches {@code GET /chartsearchai/chartalerts} is
 * {@code ChartSearchAiChartAlertsTest.everyFindingIsShapedExactlyAsASearchChipIs}, which reads the key
 * set off a live {@code /search} chip rather than listing it — a hand-written list here is the thing
 * that case's javadoc forbids. And that the payload still marshals for an XML client is
 * {@code ChartSearchAiChartOrderBridgeTest.theWholePayloadStillMarshalsForAnXmlClient} through the
 * shared {@code XmlPayloads.assertMarshals}; this key adds no new hazard there, a primitive
 * {@code boolean} being the JDK type the bridges are not.
 */
public class ChartSearchAiUncorroboratedChartMatchTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The hazard case's own sentence, from issue #309's reproduction and quoted in #374. */
	private static final String UNCORROBORATED_DETAIL =
			"Naltrexone is contraindicated by an active condition: acute hepatitis or liver failure";

	private static final String CORROBORATED_DETAIL =
			"Ibuprofen is contraindicated by the recorded condition Peptic ulcer disease.";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new TwoContraindicationStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/**
	 * Two contraindication chips whose sentences are equally categorical and whose provenance answers
	 * differ — the arrangement the key exists for. The uncorroborated one is built by the
	 * curated-rule arm's own factory; the other by the public constructor, which answers false the way
	 * the allergen arm's sentences do.
	 */
	private static List<SafetyWarning> chips() {
		return Arrays.asList(
			SafetyWarningFixtures.uncorroboratedContraindication("Naltrexone", UNCORROBORATED_DETAIL),
			new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", CORROBORATED_DETAIL));
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
		assertEquals(2, chips.size(), "precondition: this arrangement raises two chips, was: " + chips);
		return chips;
	}

	/**
	 * The chip states the module's own provenance answer, so the chip surface no longer withholds what
	 * the two injected records say.
	 *
	 * <p><b>This is the defect itself.</b> On issue #309's reproduction the injected
	 * {@code drug_reference} record and the {@code safety_finding} beside it both hedged while the chip
	 * asserted the contraindication, and the module held the answer with no path to a client. The two
	 * chips here carry equally categorical sentences, so nothing but this key tells them apart —
	 * which is also why the assertion is made of BOTH: a key that read true for every contraindication
	 * chip would satisfy a one-sided reading of this case while saying nothing.
	 *
	 * <p>It does not assert that {@code detail} changed, because it did not: the sentence is measured
	 * prose that {@code DrugSafetyChipLabelTest} and issue #108 constrain, and
	 * {@code DrugReferenceInjector.renderFinding} copies it verbatim into a citable
	 * {@code safety_finding} that appends its own provenance clause off this very flag — so hedging the
	 * sentence would state the hedge twice. ADR Decision 92.
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

		JsonNode corroborated = chips.get(1);
		assertEquals(CORROBORATED_DETAIL, corroborated.get("detail").asText(),
			"precondition: chip 1 is the corroborated one");
		assertNotNull(corroborated.get("restsOnAnUncorroboratedChartMatch"),
			"the key is present on every chip, not only on the ones that answer true: " + corroborated);
		assertEquals(false, corroborated.get("restsOnAnUncorroboratedChartMatch").asBoolean(),
			"a chip with nothing to hedge must not be hedged: " + corroborated);
	}

	/**
	 * The chip's provenance answer is published by READING THE ACCESSOR, in the one serializer.
	 *
	 * <p>Scoped to {@code serializeSafetyWarnings}' own body and not to the file, for the reason
	 * {@code ChartSearchAiInteractionPairExtentTest} gives of its own scoping: asked of the whole
	 * source, a put anywhere in the controller would satisfy it, including one on a payload this
	 * chip's array is not part of.
	 *
	 * <p><b>It asserts the accessor is READ, which is the half the reflective guard cannot see.</b>
	 * That guard compares the published value against the accessor on the fixture's chips, so it is
	 * satisfied by any expression that happens to agree with them — a re-derivation from
	 * {@code getSeverity()}, or a constant matching a fixture that carries one value. Issue #340's
	 * defect was a value computed and then dropped; the shape this pin adds is a value RECOMPUTED at
	 * the serializer, which is the two-resolutions-that-agree shape issue #151 records, and it fails
	 * silently in one direction.
	 */
	@org.junit.jupiter.api.Test
	public void theSerializerPublishesTheChipsOwnProvenanceAnswer() throws Exception {
		String body = ChartSearchAiStreamingTest.bodyOf(ChartSearchAiStreamingTest.controllerSource(),
			"private List<Map<String, Object>> serializeSafetyWarnings(");

		assertTrue(body.contains("map.put(\"restsOnAnUncorroboratedChartMatch\",\n"
				+ "\t\t\t\twarning.restsOnAnUncorroboratedChartMatch());")
				|| body.contains("map.put(\"restsOnAnUncorroboratedChartMatch\", "
						+ "warning.restsOnAnUncorroboratedChartMatch());"),
			"serializeSafetyWarnings must publish the chip's own provenance answer, read off "
					+ "SafetyWarning.restsOnAnUncorroboratedChartMatch() rather than re-derived — "
					+ "issue #374. Body was: " + body);
	}

	/** Returns the two fixture chips on every path the controller can take. */
	private static class TwoContraindicationStubService implements ChartSearchService {

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
