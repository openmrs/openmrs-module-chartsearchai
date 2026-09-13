/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/294">#294</a>: what
 * the module does with a grounding verdict for an injected {@code active_drug_order} record whose
 * display names no drug.
 *
 * <p><b>This recorded a measurement until the remedy landed, and now records the remedy.</b> The
 * exposure was that {@code RESOURCE_TYPE_ACTIVE_DRUG_ORDER} groups as {@code REFERENCE_GROUP_CHART},
 * so unlike {@code drug_reference} and {@code safety_finding} its verdict is NOT withheld at the wire
 * by {@code ChartSearchAiRestController.groundedForWire} (#201) — a {@code false} reached a client as
 * <em>Unsupported</em>, in red, on the module's own reconciliation record. The measurement #294 asked
 * for first was run and answered yes (ADR Decision 38's owed-measurement section), and the remedy is
 * that such a citation is now {@code Disposition.UNVERIFIABLE}: no verdict in either direction, in
 * either mode. <b>The cases below therefore assert the opposite of what they asserted when this class
 * was written</b>, which is the behaviour change the issue asks for and not a loosened assertion —
 * the preconditions and the judge-recording are unchanged and what moved is the published value. The
 * arrangement each case RUNS is unchanged too, though it is now built per case rather than in
 * {@code setUp}, so that the named non-regression added beside them differs from them in one call.
 *
 * <p><b>Nothing is withheld at the WIRE, and that is the point of siting it here.</b> The remedy
 * leaves {@code RecordReference.getGrounded()} null, so {@code groundedForWire} publishes null with
 * no second carve-out of its own and the guard #294's text worried about is untouched. Which is why
 * these cases read the verdict off the answer's reference and assert nothing about serialization.
 *
 * <p><b>What this class adds, and what it deliberately does not.</b> Two things about this record
 * were already pinned and are NOT repeated here:
 * <ul>
 * <li>the wire half, by {@code ChartSearchAiReferenceGroundingWithholdingTest}, which cites every
 * declared {@code RESOURCE_TYPE_*} at {@code TRUE} and at {@code FALSE} and derives its expectation
 * from {@link ChartSearchAiUtils#isGroundingDemoteOnly} — so a chart-group type's attached verdict
 * is asserted to survive serialization at all four emission sites;</li>
 * <li>the co-cited arrangement, by
 * {@code CitationGroundingVerifierTest.compositeClaim_chartCitationCoCitedWithAFindingRendersUnverifiedNotUnsupported},
 * over an {@code active_drug_order} record built by the real render chain. A codes-only display
 * cannot change that outcome: the #284 withholding branch reads no record TEXT, only
 * {@code llmVerdict}, the disposition and index-set membership.</li>
 * </ul>
 * What was unpinned is the COMPOSED path. The only other test that installs a verifier into
 * {@link LlmInferenceService} overrides {@code verify} wholesale over a pass-through injector, so
 * nothing joined the real {@code DrugReferenceInjector} to the real
 * {@link CitationGroundingVerifier} through {@code search}. That is what this drives.
 *
 * <p><b>It does not measure a cosine, and cannot.</b> Tier-1 compares embeddings, and no embedding
 * model runs here — {@code resolveEmbedder()} returns {@code null}, which models a deployment with
 * none. That means the {@code null} these cases read is OVER-DETERMINED for Tier-1: a change that
 * merely dropped this record from Tier-2 candidacy would leave them green. <b>So the discriminating
 * half of each case below is that the judge was never ASKED</b>, and the tier the null must survive a
 * real cosine in is pinned where a cosine can be arranged —
 * {@code CitationGroundingVerifierTest.codesOnlyActiveOrder_aCosineFailIsWithheldRatherThanPublished}
 * is the case that separates this remedy from demote-only, because demote-only keeps a fail. What
 * this class adds over those is the COMPOSED path: the real injector minting the record and stamping
 * it, the real verifier reading the stamp, joined through {@code search}.
 */
public class CodesOnlyActiveOrderGroundingContextTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order, order 111. */
	private static final int ORDERED_CONCEPT = 88;

	private static final int ORDER = 111;

	/**
	 * The two ATC codes mapped onto the ordered concept. What the arrangement needs of them is that
	 * the order carries at least one normalized code — the code-only rung's own precondition, which
	 * {@link DrugReferenceTestSupport#makeOrderNameless} documents beside the other condition, that
	 * none of {@code addDrugName}'s three sources yields a readable name. Together those route the
	 * order through {@code PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly}, whose display
	 * {@code PatientClinicalContextBuilder.codeOnlyDisplay} builds out of these codes themselves and
	 * {@code DrugReferenceInjector.renderActiveOrder} wraps. Neither of those two reads the loaded
	 * reference data, so the display {@link #CODES_ONLY_RECORD} pins does not depend on whether that
	 * data could name a code; what it does depend on is these two values, which is why it spells
	 * them.
	 *
	 * <p>Which drug each code denotes is irrelevant here and asserting it would be an unverified
	 * claim, so nothing below names a substance for them.
	 */
	private static final String ORDER_ATC = "M01AE02";

	private static final String ORDER_ATC_SIBLING = "M01AE04";

	/**
	 * The exact record the arrangement must produce — the precondition the whole issue rests on, so it
	 * is asserted rather than assumed. Without this a concept that regained a name would leave these
	 * cases measuring a NAMED active order, which is a different record and is covered elsewhere.
	 * Byte-identical to the string {@code NamelessActiveOrderPartnerTest} pins for the same
	 * arrangement, and it names no drug: that is what a medication claim about it cannot be entailed
	 * by.
	 */
	private static final String CODES_ONLY_RECORD = "Active drug order: [ATC M01AE02, M01AE04].";

	/** A plain medication question: it resolves no drug of its own, so nothing here depends on the
	 *  question-driven injection arm. */
	private static final String QUESTION = "What medications is this patient currently taking?";

	/**
	 * The medication claim the stub model makes about the record, without its citation marker. The
	 * judge's premise is asserted against {@link #CODES_ONLY_RECORD} and its statement against this,
	 * so a regression that handed Tier-2 a truncated premise cannot pass.
	 *
	 * <p>It names a drug the record's own codes do NOT denote — metformin is {@code A10BA02}, the
	 * codes are {@code M01AE} — so the claim is one the record could not entail even if a reader
	 * resolved the codes. That keeps the arrangement honest about what it is: a medication claim
	 * aimed at a record naming no drug. An earlier version named the drug {@code M01AE02} actually
	 * is, which contradicted the two javadocs above that decline to assert what these codes denote.
	 */
	private static final String CLAIM = "The patient is taking metformin 500mg twice daily";

	/** Reads the injected record's own number out of the numbered chart the provider is handed, so a
	 *  change to how many records the injector appends cannot quietly turn this into an arrangement
	 *  that cites something else. */
	private static final Pattern ACTIVE_ORDER_LINE = Pattern.compile("\\[(\\d+)\\] Active drug order");

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, ORDER_ATC, ORDER_ATC_SIBLING);
	}

	/**
	 * Voids every name the order could be read by, which is what routes it through
	 * {@code namedByCodesOnly}. Per-case rather than in {@link #setUp}, so the named non-regression
	 * below differs from its siblings in this ONE call and nothing else.
	 *
	 * <p>Order matters and is not incidental: the ATC map goes on through the real
	 * {@code ConceptService} while the concept still validates, and only then are its names voided —
	 * which is why the map stays in {@code setUp} and this does not.
	 * {@code DrugReferenceTestSupport.makeOrderNameless} carries why.
	 */
	private static void makeTheOrderNameless() {
		DrugReferenceTestSupport.makeOrderNameless(ORDER, ORDERED_CONCEPT);
	}

	private TestableService serviceUnderTest(LlmProvider provider, FixedJudge judge) {
		DrugReferenceService reference = DrugReferenceTestSupport.curatedService();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		TestableVerifier verifier = new TestableVerifier();
		verifier.setLlmProvider(judge);
		service.setCitationGroundingVerifier(verifier);
		return service;
	}

	private static RecordReference activeOrderReference(ChartAnswer answer) {
		RecordReference found = null;
		for (RecordReference reference : answer.getReferences()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER
					.equals(reference.getResourceType())) {
				assertTrue(found == null,
						"the arrangement must inject exactly one active-order record, was: "
								+ answer.getReferences());
				found = reference;
			}
		}
		return found;
	}

	/**
	 * The remedy, through the composed path. A codes-only {@code active_drug_order} record, injected
	 * by the real reconciliation and cited by the model in a sentence of its own, publishes NO verdict
	 * — and the judge is never asked, which is the half of this that a null Tier-1 embedder cannot
	 * account for.
	 *
	 * <p>This case asserted {@code false} when it was written, and that {@code false} is what issue
	 * #294 was filed about; a live query produced it on a sentence the record supports. The
	 * preconditions below are unchanged, so what moved is the published value and nothing about the
	 * arrangement.
	 *
	 * <p>Issue #284's withholding cannot reach this arrangement, and the reason is simpler than
	 * the intersection rule: this chart carries NO reference material at all — one obs and the
	 * injected order record — so {@code demoteOnlyIndexes} is empty and the branch has nothing to
	 * find, whatever the sentence cites. The arrangement that DOES exercise the withholding needs a
	 * finding in the chart, and is already pinned; see the class javadoc.
	 */
	@Test
	public void aCodesOnlyActiveOrderCitationPublishesNoVerdictThroughTheComposedPath() {
		makeTheOrderNameless();
		CitesTheActiveOrderAlone provider = new CitesTheActiveOrderAlone();
		FixedJudge judge = new FixedJudge(Boolean.FALSE);
		TestableService service = serviceUnderTest(provider, judge);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertEquals("[" + order.getIndex() + "] " + CODES_ONLY_RECORD, provider.citedRecord,
				"precondition: the cited record's display must name no drug, or these cases measure a "
						+ "different record than #294 is about");
		assertFalse(ChartSearchAiUtils.isGroundingDemoteOnly(order.getResourceType()),
				"precondition: this type is still chart evidence and still not reference material — "
						+ "the remedy did NOT re-key isGroundingDemoteOnly, which would blank the "
						+ "verdict for every active-order citation including the named ones");
		assertFalse(order.isAttachedByTheModule(),
				"precondition: the MODEL cited this record inline, so it is not the module's own "
						+ "attachment, which would be UNVERIFIABLE for a different reason (#305)");
		assertNull(order.getGrounded(),
				"a record that names no drug gives neither tier a question that is this citation's "
						+ "own, so nothing is published — the false this case used to assert is what "
						+ "a client keying its badge on resourceType rendered as Unsupported, in red, "
						+ "on the module's own reconciliation record (#294)");
		assertTheJudgeWasNotAsked(judge);
	}

	/**
	 * The other direction, so the rule is not mistaken for a one-sided demotion: with the judge
	 * ACCEPTING, the same citation still publishes nothing. That is what separates
	 * {@code Disposition.UNVERIFIABLE} from {@code DEMOTE_ONLY} on the yes side; the no side is
	 * separated by a cosine, which this class cannot arrange — see the class javadoc for where it is.
	 *
	 * <p>Since the judge is never asked, the verdict it would have given is not what this case
	 * varies — it varies the stub, and asserts the answer does not depend on it. Worth keeping as its
	 * own case rather than folding into the one above: a regression that asked the judge and then
	 * dropped a {@code false} would be green on the sibling alone.
	 */
	@Test
	public void theSameCitationPublishesNothingWhicheverWayTheJudgeWouldHaveAnswered() {
		makeTheOrderNameless();
		CitesTheActiveOrderAlone provider = new CitesTheActiveOrderAlone();
		FixedJudge judge = new FixedJudge(Boolean.TRUE);
		TestableService service = serviceUnderTest(provider, judge);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertEquals("[" + order.getIndex() + "] " + CODES_ONLY_RECORD, provider.citedRecord,
				"precondition: the cited record's display must name no drug");
		assertNull(order.getGrounded(),
				"nothing is published in either direction; demote-only would have withheld this yes "
						+ "and kept a no, which is the exposure #294 is about");
		assertTheJudgeWasNotAsked(judge);
	}

	/**
	 * The non-regression, on the same composed path and differing in ONE thing: the order has a name.
	 * Its citation is still graded and its verdict still published, which is the property a carve-out
	 * keyed on the TYPE could not have preserved — ADR Decision 38's remedies sub-section measured
	 * that cost, and this case is what would have reddened.
	 *
	 * <p>Its unit counterpart is
	 * {@code CitationGroundingVerifierTest.aNamedActiveOrderRecordIsStillGradedThroughTheRealInjector},
	 * which asserts the stamp's TRUE directly; this one is the composed-path half and asserts only
	 * what the ANSWER carries. <b>They are not interchangeable, and an earlier draft of this sentence
	 * said they were.</b> Measured: collapsing the writer to "FALSE or nothing" reddens the unit case
	 * and {@code DrugReferenceInjectorTest.theInjectedActiveOrderRecordStatesWhetherItNamesItsDrug},
	 * and leaves all three cases HERE green — because a record stamped {@code null} is graded exactly
	 * as one stamped {@code TRUE}, which is all this case can see. What it covers that they do not is
	 * the wiring: the real injector and the real verifier joined through {@code search}.
	 *
	 * <p>The arrangement is this class's own minus {@link #makeTheOrderNameless}: order 111 keeps
	 * concept 88's names, so {@code PatientClinicalContextBuilder} takes the display from a name and
	 * never reaches the code-only rung. The ATC map stays in {@code setUp}, so the two arrangements
	 * differ in the naming and not in what the dictionary knows.
	 */
	@Test
	public void aNamedActiveOrderCitationStillCarriesTheJudgesVerdictThrough() {
		CitesTheActiveOrderAlone provider = new CitesTheActiveOrderAlone();
		FixedJudge judge = new FixedJudge(Boolean.FALSE);
		TestableService service = serviceUnderTest(provider, judge);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertFalse(provider.citedRecord.contains("[ATC "),
				"precondition: this arrangement's order must be NAMED, or it measures the same record "
						+ "as the cases above; cited record was: " + provider.citedRecord);
		assertEquals(Boolean.FALSE, order.getGrounded(),
				"a named active-order citation is graded as before (#118): the rule reaches the "
						+ "record that names no drug and no other");
		assertTheJudgeWasAskedAbout(judge, stripMarker(provider.citedRecord));
	}

	/** Exposes the seams, and keeps warmup out of a test about a reference list. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/**
	 * The shared one-record chart: an obs and no drug-order record at all, so patient 7's order 111 is
	 * unrepresented and the reconciliation injects it. Deliberately NOT a drug-order record — the
	 * injection under measurement happens only for an order the retrieved chart substantiates none of,
	 * which is what {@code unrepresentedActiveOrders} looks for. Taken from
	 * {@link DrugReferenceTestSupport#oneRecordChart} rather than assembled here, so the numbered
	 * {@code "[N] text"} rendering the answer's citation number is parsed back out of is the one the
	 * serializer produces.
	 */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.oneRecordChart();
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/**
	 * A verifier with no Tier-1 embedding model — a real deployment shape rather than a convenience,
	 * for the reasons {@code CitationGroundingVerifierTest.TestableVerifier}'s javadoc gives. Fixed at
	 * null here rather than settable, because a cosine this class could set would be a number it
	 * invented; see the class javadoc.
	 */
	private static final class TestableVerifier extends CitationGroundingVerifier {

		@Override
		TextEmbedder resolveEmbedder() {
			return null;
		}
	}

	/**
	 * A judge that answers the same way for every pair it is handed, and RECORDS the pairs. Recording
	 * is not decoration: a constant-returning stub that discarded its inputs would leave both cases
	 * green if the composed path handed Tier-2 a truncated premise, so the phrase "a judge that
	 * refuses a medication claim ABOUT THIS RECORD" would be untested. What it does NOT test is claim
	 * SELECTION — this answer offers one sentence, so there is one unit to pick.
	 * {@code CitationGroundingVerifierTest.ConjunctionAwareJudge} records for the same reason.
	 */
	private static final class FixedJudge extends LlmProvider {

		private final Boolean verdict;

		/** The (premise, statement) pairs Tier-2 was asked about, in call order. */
		private final List<String> sourcesSeen = new ArrayList<String>();

		private final List<String> statementsSeen = new ArrayList<String>();

		private FixedJudge(Boolean verdict) {
			this.verdict = verdict;
		}

		@Override
		public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
			sourcesSeen.addAll(sources);
			statementsSeen.addAll(statements);
			List<Boolean> out = new ArrayList<Boolean>();
			for (int i = 0; i < sources.size(); i++) {
				out.add(verdict);
			}
			return out;
		}
	}

	/**
	 * That Tier-2 was asked about THIS record: one pair, whose premise is the cited record's text
	 * WHOLE and whose statement carries the model's own medication claim.
	 *
	 * <p>The premise side is an EQUALITY and is the load-bearing half — it is the difference between
	 * "the judge refused a claim about this record" and "the judge refused something", so a composed
	 * path that handed Tier-2 a truncated premise could not pass. The statement side is a
	 * containment, so a marker or prefix left on it still passes. Parameterised on the expected
	 * record rather than fixed to the codes-only one, because the case that still asks this is the
	 * NAMED non-regression; an earlier draft asserted only the pair COUNT there, which is the weaker
	 * form this javadoc exists to argue against.
	 */
	private static void assertTheJudgeWasAskedAbout(FixedJudge judge, String record) {
		assertEquals(Collections.singletonList(record), judge.sourcesSeen,
				"Tier-2's premise must be the cited record's text, whole");
		assertEquals(1, judge.statementsSeen.size(),
				"one citation, one claim unit, was: " + judge.statementsSeen);
		assertTrue(judge.statementsSeen.get(0).contains(CLAIM),
				"and the statement must be the model's own medication claim, was: "
						+ judge.statementsSeen.get(0));
	}

	/** The record text out of the numbered chart line the provider cited, which carries a leading
	 *  {@code "[N] "} the judge's premise does not. */
	private static String stripMarker(String citedChartLine) {
		return citedChartLine.substring(citedChartLine.indexOf("] ") + 2);
	}

	/**
	 * That Tier-2 was not asked at ALL — no premise and no statement. This is the discriminating
	 * assertion of the two codes-only cases, because the {@code null} verdict beside it is
	 * over-determined here: {@code resolveEmbedder()} returns null, so Tier-1 reaches no verdict
	 * either way.
	 *
	 * <p>It is the counterpart of {@link #assertTheJudgeWasAskedAbout}, which those cases used before
	 * the remedy. The premise-side equality that made "the judge refused a claim about this record"
	 * stronger than "the judge refused something" moved with the behaviour: there is now no pair to
	 * inspect, so the recording exists to prove its ABSENCE rather than its content.
	 * {@link FixedJudge} still records, for that.
	 */
	private static void assertTheJudgeWasNotAsked(FixedJudge judge) {
		assertEquals(Collections.emptyList(), judge.sourcesSeen,
				"the judge must be handed no premise at all: it refuses a medication claim about a "
						+ "record naming no drug by construction, so its answer would be about the "
						+ "record's silence rather than about the citation — and the pair would spend "
						+ "a slot of the per-answer entailment cap chart claims rely on");
		assertEquals(Collections.emptyList(), judge.statementsSeen,
				"and no statement either, was: " + judge.statementsSeen);
	}

	/**
	 * Cites the injected active-order record ALONE, in a sentence of its own that makes a medication
	 * claim about the patient — the shape #294's text describes, and the shape issue #284's
	 * withholding does not reach since the sentence cites no reference material.
	 *
	 * <p><b>This shape is no longer hypothetical.</b> A real query has produced it — asked whether the
	 * patient has an active order whose drug the chart does not name, the model says so, cites the
	 * record, and the judge refuses; the published verdict was {@code false} on a sentence the record
	 * supports. ADR Decision 38's owed-measurement section carries the arrangement, the wording and the
	 * regime split. The claim text below is not that sentence — it is a plain medication claim, which
	 * is the shape #294's own text describes — so these cases still measure the module's HANDLING
	 * rather than the model's behaviour.
	 */
	private static final class CitesTheActiveOrderAlone extends LlmProvider {

		/** The record line this provider cited, for the caller to assert the display on. */
		private String citedRecord;

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			Matcher matcher = ACTIVE_ORDER_LINE.matcher(numberedRecords);
			if (!matcher.find()) {
				throw new IllegalStateException(
						"the arrangement must inject an active-order record, chart was: "
								+ numberedRecords);
			}
			int order = Integer.parseInt(matcher.group(1));
			int lineEnd = numberedRecords.indexOf('\n', matcher.start());
			citedRecord = (lineEnd < 0 ? numberedRecords.substring(matcher.start())
					: numberedRecords.substring(matcher.start(), lineEnd)).trim();
			return new LlmResponse(CLAIM + " [" + order + "].",
					Collections.singletonList(Integer.valueOf(order)));
		}
	}
}
