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
 * <p><b>This records a measurement, not a desired end state.</b> The exposure is that
 * {@code RESOURCE_TYPE_ACTIVE_DRUG_ORDER} groups as {@code REFERENCE_GROUP_CHART}, so unlike
 * {@code drug_reference} and {@code safety_finding} its verdict is NOT withheld at the wire by
 * {@code ChartSearchAiRestController.groundedForWire} (#201) — a {@code false} reaches a client as
 * <em>Unsupported</em>, in red, on the module's own reconciliation record. #294 asks for the
 * measurement before any remedy, and takes no decision on which remedy. ADR Decision 38 records the
 * exposure; Decision 41's residue narrows it.
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
 * none. What that buys here is that the judge's answer is the only thing that can decide, so the
 * verdict these cases read is unambiguously the one under test. It is NOT that a null embedder is
 * irrelevant to whether the judge is asked: candidacy needs a claim SENTENCE, and selecting one
 * embeds wherever more than one sentence cites the record — give this stub a second citing sentence
 * and both cases publish {@code null} instead. The single-sentence answer below is what keeps that
 * path out, and nothing more general about it is claimed here. Whether a
 * codes-only record's REAL e5 embedding falls
 * under a given {@code chartsearchai.grounding.minCosine} is a question only the live measurement on
 * #294 can answer, and the floor is an operator setting the module's own global-property text says
 * to raise. So the assertion below is conditional by construction: given a judge that refuses, the
 * refusal is what the answer carries. The live run is what says whether a real judge refuses.
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
		// Order matters and is not incidental: the ATC map goes on through the real ConceptService
		// while the concept still validates, and only then are its names voided. makeOrderNameless
		// carries why.
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, ORDER_ATC, ORDER_ATC_SIBLING);
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
	 * The measurement. A codes-only {@code active_drug_order} record, injected by the real
	 * reconciliation and cited by the model in a sentence of its own, is GRADED: the judge's refusal
	 * arrives on the answer's reference as {@code false}, with nothing in the composed path
	 * interposing on it — and the type is not grounding-demote-only, so the wire publishes what it
	 * finds there.
	 *
	 * <p>Issue #284's withholding cannot reach this arrangement, and the reason is simpler than
	 * the intersection rule: this chart carries NO reference material at all — one obs and the
	 * injected order record — so {@code demoteOnlyIndexes} is empty and the branch has nothing to
	 * find, whatever the sentence cites. The arrangement that DOES exercise the withholding needs a
	 * finding in the chart, and is already pinned; see the class javadoc.
	 */
	@Test
	public void aCodesOnlyActiveOrderCitationCarriesTheJudgesRefusalThroughTheComposedPath() {
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
				"precondition: this type is chart evidence, so its verdict is NOT withheld at the "
						+ "wire — that is the exposure #294 is about");
		assertFalse(order.isAttachedByTheModule(),
				"precondition: the MODEL cited this record inline, so it is not the module's own "
						+ "attachment, which would be UNVERIFIABLE in either mode (#305)");
		assertEquals(Boolean.FALSE, order.getGrounded(),
				"a refused claim about a record naming no drug arrives as false — the value "
						+ "groundedForWire publishes for a chart-group citation, and the one a client "
						+ "that keys its badge on resourceType rather than on group renders as "
						+ "Unsupported (#201's defect, which README tells a conforming client to "
						+ "avoid by rendering false and null alike as unverified)");
		assertTheJudgeWasAskedAboutTheRecord(judge);
	}

	/**
	 * The other direction: the same arrangement with the judge accepting publishes {@code true}, so
	 * the composed path is not hardwired to either verdict and the exposure above is a property of
	 * what the pass CONCLUDES rather than of the record's type. What a LIVE run publishes for such a
	 * record, and under which regime, is ADR Decision 38's owed-measurement section — read it there
	 * rather than inferring it from here. Nothing it records was measured on this case's claim shape,
	 * a medication claim naming a drug the record does not name, and it says so of its own regime
	 * table.
	 *
	 * <p>Worth pinning beside its sibling because the deliberate non-extension of the demote-only
	 * carve-out to this type means a pass VERIFIES here rather than rendering unverified — ADR
	 * Decision 25's carve-out is scoped to reference prose, and
	 * {@code CitationGroundingVerifierTest.activeDrugOrder_highCosinePassRendersVerifiedNotDemoted}
	 * is where that decision is recorded.
	 */
	@Test
	public void theSameCitationCarriesAnAcceptanceThroughToo() {
		CitesTheActiveOrderAlone provider = new CitesTheActiveOrderAlone();
		FixedJudge judge = new FixedJudge(Boolean.TRUE);
		TestableService service = serviceUnderTest(provider, judge);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertEquals("[" + order.getIndex() + "] " + CODES_ONLY_RECORD, provider.citedRecord,
				"precondition: the cited record's display must name no drug");
		assertEquals(Boolean.TRUE, order.getGrounded(),
				"an accepted claim publishes true for this type — demote-only is scoped to "
						+ "drug-reference prose, not to everything the module injects");
		assertTheJudgeWasAskedAboutTheRecord(judge);
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
	 * What Tier-2 was actually asked: one pair, whose premise is the codes-only record WHOLE and whose
	 * statement carries the model's own medication claim. Asserted in both cases, because it is the
	 * difference between "the judge refused a claim about this record" and "the judge refused
	 * something". The premise side is an equality and is the load-bearing half; the statement side is
	 * a containment, so a marker or prefix left on it still passes.
	 */
	private static void assertTheJudgeWasAskedAboutTheRecord(FixedJudge judge) {
		assertEquals(Collections.singletonList(CODES_ONLY_RECORD), judge.sourcesSeen,
				"Tier-2's premise must be the codes-only record text, whole");
		assertEquals(1, judge.statementsSeen.size(),
				"one citation, one claim unit, was: " + judge.statementsSeen);
		assertTrue(judge.statementsSeen.get(0).contains(CLAIM),
				"and the statement must be the model's own medication claim, was: "
						+ judge.statementsSeen.get(0));
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
