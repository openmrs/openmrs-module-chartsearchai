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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.Introspector;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire contract for the {@code safetyWarnings} chip object: the rating a chip was RAISED and ORDERED
 * on reaches the client as a field, so that badging a Major differently from a Minor does not mean
 * substring-matching the clinician-facing English inside {@code detail} (issue #340).
 *
 * <p>Before this, {@code ChartSearchAiRestController.serializeSafetyWarnings} wrote {@code type},
 * {@code drug} and {@code detail}, and {@link SafetyWarning#getSeverity()} — public, populated by
 * every rated arm, and the rating the two PAIRWISE arms order their own chips by before
 * {@code DrugSafetyValidator.maxPairChips} cuts the list — stopped there. The only surviving trace of
 * it on the wire was a word in the middle of prose the module rewords freely, and this repo already
 * carries a reader that has to parse it: {@code eval/drift-metric/score_probe_safety.py}'s
 * {@code CHIP_SEVERITY} regex, whose own comment calls that "the fault issue #207 exists to have
 * removed".
 *
 * <p><b>The prose is not a fallback — though on the shipped dataset it currently looks like one.</b>
 * {@code DdiDrugReferenceSource.noteFor} builds every DDInter note as
 * {@code severity + ". " + mechanism}, or as {@code severity + " severity interaction (…)."} where the
 * row has no mechanism text, and {@code DrugSafetyValidator.interactionWarning} appends that note
 * after an em dash — so over the shipped KB's 590,312 links there is no rated rule whose chip fails
 * to name its rating (measured; no note is null or blank either). What a parse rests on there is only
 * that {@code detail} is prose this module rewords freely. On an operator {@code sourceFormat=json}
 * dataset it breaks outright: note and rating are independently authored, so a rule may carry no note
 * — the branch above appends nothing and the rating is nowhere in the sentence — or one whose leading
 * word is a different rating. Both are pinned, in
 * {@link #theRatingIsPublishedEvenWhereTheProseNamesItNowhere}.
 *
 * <p>Driven through the real controller, at all three call sites of the one private helper: the
 * blocking {@code /search} handler, the SSE {@code done} event, and the {@code grounded} event of the
 * async-grounding path. Covering the helper at one site would prove the helper and not that the other
 * two call it — the reason {@code ChartSearchAiSearchResponseGroupingTest} exists for its own
 * endpoint. The two SSE sites are also the ones whose ACTUAL JSON can be read back, since the
 * controller serializes those payloads itself with a default {@link ObjectMapper}; so it is there
 * that "the key is present and null" is asserted of the bytes a client receives, rather than of a
 * {@code Map} Spring has not serialized yet.
 *
 * <p>The stubbed {@link ChartSearchService} is this package's established seam for a wire-contract
 * test — {@code omod/pom.xml} declares no {@code chartsearchai-api} test-jar, so the api-side
 * fixtures that drive the real {@code DrugSafetyValidator} are not reachable from here. What that
 * seam leaves to the api suite is that the real arms populate the rating at all, which
 * {@code DrugSafetyInteractionSeverityFloorTest} and {@code DrugSafetyQuestionPairInteractionTest}
 * already assert of chips built by the real validator; what is unique to this class, and missing
 * without it, is whether the value survives the last step.
 */
public class ChartSearchAiSafetyWarningSeverityWireTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * The fixture chips. <b>Every case below indexes this list positionally, so the indices are the
	 * contract</b> — inserting or reordering a chip retargets them, and only two of the four
	 * positional reads carry a precondition assertion that would catch it.
	 *
	 * <ul>
	 *   <li>0, 1, 3, 4 — the shape of issue #340's own measured response (patient
	 *       {@code 763e6e5f-…}, "Please screen her current medications for drug interactions."): a
	 *       rated Major and a rated Minor interaction on one drug, and two contraindications.</li>
	 *   <li>2 — a rated rule carrying NO note, so its rating appears nowhere in its own
	 *       {@code detail}. An operator {@code sourceFormat=json} shape: the shipped DDInter dataset
	 *       cannot produce it, because {@code DdiDrugReferenceSource.noteFor} always writes a note and
	 *       always leads it with the rating.</li>
	 *   <li>5 — a rating the module does not RECOGNISE, which it treats exactly as it treats null and
	 *       which must still reach the wire as the dataset wrote it.</li>
	 *   <li>6 — a chip whose sentence and rating DISAGREE, which is what makes
	 *       {@link #theRatingIsPublishedEvenWhereTheProseNamesItNowhere} able to tell a field read
	 *       from a parse that falls back to the field.</li>
	 * </ul>
	 */
	private static List<SafetyWarning> fixtureWarnings() {
		return Arrays.asList(
				new SafetyWarning(SafetyWarning.TYPE_INTERACTION, "Lidocaine",
						"Lidocaine interacts with active order metoclopramide — Major. Coadministration of "
								+ "local anesthetics with methemoglobin-inducing agents raises the risk of "
								+ "methemoglobinemia.",
						"Major"),
				new SafetyWarning(SafetyWarning.TYPE_INTERACTION, "Lidocaine",
						"Lidocaine interacts with active order neomycin — Minor. Limited in vitro data "
								+ "suggest additive neuromuscular blockade.",
						"Minor"),
				// A rated rule carrying NO note: interactionWarning appends one only when there is one,
				// so the rating is on the chip and nowhere in its sentence. A json-dataset shape — the
				// ddinter parser writes a note for every row, and always leads it with the rating.
				new SafetyWarning(SafetyWarning.TYPE_INTERACTION, "Warfarin",
						"Warfarin interacts with active order aspirin", "Moderate"),
				// Contraindications carry no rating at all — the arms that raise one use the
				// three-argument constructor. Null here is a real statement, not a missing value.
				new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen",
						"Ibuprofen is contraindicated by a documented Severe allergy to Aspirin."),
				new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen",
						"Ibuprofen is contraindicated by the recorded condition Peptic ulcer disease."),
				// An operator's json dataset writes its own vocabulary: DrugReference.Interaction's
				// severity is a plain Jackson-bound string with no vocabulary check over it.
				new SafetyWarning(SafetyWarning.TYPE_INTERACTION, "Phenelzine",
						"Phenelzine interacts with active order selegiline — Severe. Operator-authored rule.",
						"Severe"),
				// The two DISAGREEING, which a json dataset reaches immediately: the note and the rating
				// are independently authored fields, so a curated note may open on a rating word that is
				// not the rule's. score_probe_safety.py records this exact hazard for its own prose
				// parse — "a curated note opening 'Major bleeding risk…' would read as a Major rating
				// for a rule the module rates as null".
				new SafetyWarning(SafetyWarning.TYPE_INTERACTION, "Tramadol",
						"Tramadol interacts with active order sertraline — Major bleeding risk is not what "
								+ "this rule is rated.",
						"Minor"));
	}

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new SafetyWarningStubService());
		// resolvePatient consults this on the blocking path; production autowires it.
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
	}

	/**
	 * The chips off the blocking {@code /search} response. The OpenMRS static context is installed
	 * per call and torn down whatever happens, rather than in {@code setUp}, because the SSE tests in
	 * this class must keep running with no context at all — that absence is what enforces
	 * {@code streamAnswer}'s "free of {@code Context} reads" contract on the request thread. See
	 * {@link RestControllerContext}'s javadoc.
	 */
	private List<Map<String, Object>> searchChips() {
		openmrsContext.install();
		try {
			ResponseEntity<Object> response = controller.search(
					RestControllerContext.searchBody("Please screen her current medications for drug interactions."));
			assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = (Map<String, Object>) response.getBody();
			assertNotNull(payload, "no response body");
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> chips = (List<Map<String, Object>>) payload.get("safetyWarnings");
			assertNotNull(chips, "the /search response carried no safetyWarnings array");
			assertEquals(fixtureWarnings().size(), chips.size(),
					"every fixture warning must survive serialization");
			return chips;
		}
		finally {
			openmrsContext.restore();
		}
	}

	@Test
	public void aRatedChipPublishesTheRatingItWasOrderedOn() {
		List<Map<String, Object>> chips = searchChips();

		assertEquals("Major", chips.get(0).get("severity"),
				"the Major interaction must publish its rating as a field: " + chips.get(0));
		assertEquals("Minor", chips.get(1).get("severity"),
				"and the Minor one must be distinguishable from it without parsing detail: " + chips.get(1));
	}

	@Test
	public void anUnratedChipPublishesTheKeyWithANullValue() {
		List<Map<String, Object>> chips = searchChips();

		for (Map<String, Object> chip : chips) {
			assertTrue(chip.containsKey("severity"),
					"the severity key must be present on every chip, null included — a client reads it "
							+ "unconditionally, exactly as it reads grounded on a reference: " + chip);
		}
		Map<String, Object> contraindication = chips.get(3);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, contraindication.get("type"),
				"precondition: chip 3 of the fixture is the contraindication");
		assertNull(contraindication.get("severity"),
				"a contraindication carries no rating, and null is that statement rather than a floor: "
						+ contraindication);
	}

	/**
	 * A rating the module does not recognise still reaches the wire as the dataset wrote it.
	 *
	 * <p>This is the assertion that would fail if the field were later "helpfully" normalized into a
	 * closed vocabulary, and normalizing it is the thing issue #340 deliberately did not do. Measured
	 * 2026-08-30 by driving the real {@code DrugSafetyValidator.severityPriority},
	 * {@code ratingLicensesWithholding} and {@code clearsSeverityFloor} on {@code "Severe"},
	 * {@code ""} and null: all three answer identically for all three inputs (priority
	 * {@code 2147483647} against {@code Major}'s {@code 3}, withholding true, floor cleared), because
	 * {@code severityRank} maps everything it does not recognise to the same {@code -1} that null
	 * gets. So publishing null here would deny a rating the chip's own {@code detail} still quotes,
	 * and a client would be told the source rated nothing when it rated something.
	 *
	 * <p><b>This case pins the WIRE half of that and not the reader half.</b> That an unrecognised
	 * rating is treated as unrated is {@code severityRank}'s behaviour, is unchanged by #340, and is
	 * not pinned here — a change to it would leave this case green while making
	 * {@code README.md}'s advice to clients wrong.
	 */
	@Test
	public void anUnrecognisedRatingReachesTheWireAsTheDatasetWroteIt() {
		Map<String, Object> chip = searchChips().get(5);

		assertEquals("Severe", chip.get("severity"),
				"the field is the dataset's word, not a value coerced into the four DDInter ratings: "
						+ chip);
	}

	@Test
	public void theRatingIsPublishedEvenWhereTheProseNamesItNowhere() {
		List<Map<String, Object>> chips = searchChips();
		Map<String, Object> chip = chips.get(2);

		assertEquals("Warfarin interacts with active order aspirin", chip.get("detail"),
				"precondition: this fixture chip's own sentence names no rating");
		assertFalse(((String) chip.get("detail")).contains("Moderate"),
				"precondition: and the rating word is nowhere in it");
		assertEquals("Moderate", chip.get("severity"),
				"so the field is the structured rating the chip was raised with, not a parse of the "
						+ "sentence beside it: " + chip);

		// The other direction, and the one a PURE-parse check cannot reach: a serializer that parses
		// the prose and falls back to the field agrees with the field wherever the two agree, so it
		// survives the assertion above. Measured — replacing the field read with exactly that
		// fallback left this class green until this chip existed. Here the sentence says one thing
		// and the rule is rated another, which no fallback can reconcile.
		Map<String, Object> disagreeing = chips.get(6);
		assertTrue(((String) disagreeing.get("detail")).contains("Major"),
				"precondition: this fixture chip's sentence carries a rating word that is not its rating");
		assertEquals("Minor", disagreeing.get("severity"),
				"the wire must carry the rule's own rating, never a word read out of its sentence: "
						+ disagreeing);
	}

	@Test
	public void theDoneEventCarriesTheRatingAndTheKeyForAnUnratedChip() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(),
				"Please screen her current medications for drug interactions.",
				RestControllerContext.user(), false);

		JsonNode chips = MAPPER.readTree(SseEvents.ofType(out, "done").data).get("safetyWarnings");
		assertNotNull(chips, "the done event carried no safetyWarnings array");
		assertEquals(fixtureWarnings().size(), chips.size(), "every fixture warning must reach the done event");

		JsonNode rated = chips.get(0);
		assertTrue(rated.has("severity"),
				"the streaming surface must publish the rating too — it is the same helper, and the "
						+ "README documents done as carrying the chips: " + rated);
		assertEquals("Major", rated.get("severity").asText(),
				"and it must be the rating the chip was raised with: " + rated);
		// Asserted on the SERIALIZED bytes rather than on a Map, because "present and null" is a claim
		// about what a client parses: a key Jackson dropped would be indistinguishable from an absent
		// one on the blocking path, where Spring serializes after this test can look.
		JsonNode contraindication = chips.get(3);
		assertTrue(contraindication.has("severity"),
				"the key must survive JSON serialization on an unrated chip rather than being omitted: "
						+ contraindication);
		assertTrue(contraindication.get("severity").isNull(),
				"and it must be JSON null: " + contraindication);
	}

	/**
	 * The third emission site: the trailing {@code grounded} event of the async-grounding path.
	 *
	 * <p>Asserted separately rather than left to the shared helper, for the reason
	 * {@code ChartSearchAiSearchResponseGroupingTest}'s own javadoc gives about its endpoint —
	 * covering the helper at two sites proves the helper, not that the third one calls it. This is
	 * also the only surface on which a client sees the chips at all when
	 * {@code chartsearchai.grounding.async=true}, since the {@code done} that precedes it publishes an
	 * empty array by design.
	 */
	@Test
	public void theTrailingGroundedEventCarriesTheRatingToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(),
				"Please screen her current medications for drug interactions.",
				RestControllerContext.user(), true);

		JsonNode doneChips = MAPPER.readTree(SseEvents.ofType(out, "done").data).get("safetyWarnings");
		assertEquals(0, doneChips.size(),
				"precondition: an async done publishes no chips — validation runs with grounding");

		JsonNode chips = MAPPER.readTree(SseEvents.ofType(out, "grounded").data).get("safetyWarnings");
		assertNotNull(chips, "the grounded event carried no safetyWarnings array");
		assertEquals(fixtureWarnings().size(), chips.size(),
				"every fixture warning must reach the grounded event");
		JsonNode rated = chips.get(0);
		assertTrue(rated.has("severity"),
				"the async surface must publish the rating like the other two: " + rated);
		assertEquals("Major", rated.get("severity").asText(),
				"and it must be the rating the chip was raised with: " + rated);
		JsonNode unrated = chips.get(3);
		assertTrue(unrated.has("severity"),
				"an unrated chip's key must survive here too rather than being omitted: " + unrated);
		assertTrue(unrated.get("severity").isNull(), "and it must be JSON null: " + unrated);
	}

	/**
	 * Every accessor a client can read off a {@link SafetyWarning} names a key on the wire.
	 *
	 * <p><b>This is a NEW contract, introduced by issue #340 — it does not enforce a rule the class
	 * already stated.</b> Until this change {@code SafetyWarning} documented the opposite for this
	 * very field ({@code getSeverity()}: "Not serialized onto the REST response; the wire shape is
	 * unchanged"), and what it states elsewhere is a different rule — setter/accessor symmetry, "a
	 * caller may set only what it may read back", which is why {@code carriesUnratedRelationship()},
	 * {@code restsOnAnUncorroboratedChartMatch()} and {@code reconciledPartnerNoteName(..)} are
	 * package-private beside package-private factories. This change removes the counterexample, and
	 * this case is what keeps the next one from being added silently: a value the module computes,
	 * orders chips by, and then drops at serialization is exactly the shape of the defect #340
	 * reports, and it survived from #207 to #340 without anything failing.
	 *
	 * <p><b>What it selects is derived, not listed.</b> A declared method of {@code SafetyWarning}
	 * that is public, non-static, zero-argument and non-void, and is not a redeclaration of a
	 * {@link Object} method. The Object check walks {@code Object.class.getDeclaredMethods()} rather
	 * than asking {@code Object.class.getMethod(name)}, because the latter sees only PUBLIC members —
	 * {@code clone()} and {@code finalize()} are protected, so a future covariant
	 * {@code public SafetyWarning clone()} would slip past a {@code getMethod} form and be demanded
	 * on the wire. The prefix-based {@code getX}/{@code isX} filter this case was first written with
	 * is deliberately NOT used: this class names two of its own non-wire accessors without that
	 * prefix, so a prefix rule would stop seeing them the moment one were made public.
	 *
	 * <p><b>It pins that SHAPE, and not every way the defect can recur.</b> An accessor taking a
	 * parameter escapes it ({@code reconciledPartnerNoteName(DrugReference.Interaction)} is one
	 * today), and so does a static one; both are excluded deliberately, because a per-chip wire field
	 * is per-warning and takes no argument. It is ONE-directional — such an accessor implies a key,
	 * never the converse — so a serializer is still free to publish something derived that no
	 * accessor names. And it reflects over whatever {@code SafetyWarning} the omod build resolved:
	 * {@code omod/pom.xml} unpacks the RESOLVED api artifact over {@code omod/target/classes}, so
	 * under {@code mvn -pl omod test} — the natural command for someone who has just edited
	 * {@code SafetyWarning} — it reads a stale {@code ~/.m2} copy and passes over the very accessor it
	 * exists to catch, silently. Build from the ROOT, which is this repo's standing rule for that
	 * reason. Mutate the selection and read the failures rather than trusting this paragraph.
	 */
	@Test
	public void everyPublicZeroArgumentAccessorOfAWarningNamesAKeyOnTheWire() {
		List<Map<String, Object>> chips = searchChips();

		List<String> unpublished = new ArrayList<String>();
		for (Method m : SafetyWarning.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())
					|| m.getParameterCount() != 0 || m.getReturnType() == void.class
					|| redeclaresAnObjectMethod(m)) {
				continue;
			}
			String key = wireKeyOf(m);
			for (Map<String, Object> chip : chips) {
				if (!chip.containsKey(key)) {
					unpublished.add(m.getName() + " -> expected key '" + key + "', chip was " + chip);
					break;
				}
			}
		}
		assertEquals(Collections.emptyList(), unpublished,
				"every accessor a client can read off a SafetyWarning must name a key the wire carries; "
						+ "a value the module computes and then drops at serialization is issue #340");
	}

	/** True where {@code m} redeclares a {@link Object} method — including the protected ones, which
	 *  {@code Object.class.getMethod} cannot see. */
	private static boolean redeclaresAnObjectMethod(Method m) {
		for (Method inherited : Object.class.getDeclaredMethods()) {
			if (inherited.getName().equals(m.getName())
					&& Arrays.equals(inherited.getParameterTypes(), m.getParameterTypes())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The wire key an accessor names: its property name — {@code getSeverity} to {@code severity} — or,
	 * for an accessor this class names without either prefix, the method name verbatim.
	 *
	 * <p>The remainder is decapitalized by {@link Introspector#decapitalize}, the JDK's own rule,
	 * rather than by lower-casing the first character here: the two differ on consecutive capitals
	 * ({@code getURL} yields {@code URL}, not {@code uRL}). No accessor on {@code SafetyWarning} has
	 * that shape today, so this is reuse rather than a fix — but a hand-rolled convention is the thing
	 * that would silently be wrong when one does.
	 */
	private static String wireKeyOf(Method m) {
		String name = m.getName();
		for (String prefix : Arrays.asList("get", "is")) {
			if (name.startsWith(prefix) && name.length() > prefix.length()
					&& Character.isUpperCase(name.charAt(prefix.length()))) {
				return Introspector.decapitalize(name.substring(prefix.length()));
			}
		}
		return name;
	}

	/** Returns the fixture chips on every path the controller can take. */
	private static class SafetyWarningStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return new ChartAnswer("Two interactions and two contraindications were found [1].",
					Arrays.<RecordReference> asList(
							new RecordReference(1, "safety_finding", "sf-1", null, null)),
					0, 0, 0, fixtureWarnings());
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return search(patient, question);
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept("Two interactions and two contraindications were found [1].");
			citationsConsumer.accept(search(patient, question).getReferences());
			// The ungrounded answer carries NO chips, which is the documented async contract —
			// validation runs with grounding, so an async `done` publishes an empty array and the
			// trailing `grounded` event delivers the chips. Firing it is what makes that event
			// reachable at all; without it the controller takes its cache-hit fallback and emits the
			// classic single `done`.
			ungroundedAnswerConsumer.accept(new ChartAnswer(
					"Two interactions and two contraindications were found [1].",
					search(patient, question).getReferences()));
			return search(patient, question);
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
