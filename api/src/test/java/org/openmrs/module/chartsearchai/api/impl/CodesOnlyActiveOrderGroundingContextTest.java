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
 * none and is why the judge is asked at all. Whether a codes-only record's REAL e5 embedding falls
 * under a given {@code chartsearchai.grounding.minCosine} is a question only the live measurement on
 * #294 can answer, and the floor is an operator setting the module's own global-property text says
 * to raise. So the assertion below is conditional by construction: given a judge that refuses, the
 * refusal is what the answer carries. The live run is what says whether a real judge refuses.
 */
public class CodesOnlyActiveOrderGroundingContextTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order, order 111. */
	private static final int ORDERED_CONCEPT = 88;

	private static final int ORDER = 111;

	/** Two codes in one ATC subgroup, neither carried by the curated seed, so the display the order
	 *  falls back to is codes and nothing else. */
	private static final String NAPROXEN_ATC = "M01AE02";

	private static final String KETOPROFEN_ATC = "M01AE04";

	/** A plain medication question: it resolves no drug of its own, so nothing here depends on the
	 *  question-driven injection arm. */
	private static final String QUESTION = "What medications is this patient currently taking?";

	/** The one obs the stubbed chart carries. Deliberately NOT a drug-order record: the injection
	 *  under measurement happens only for an order the retrieved chart substantiates none of, which
	 *  is what {@code unrepresentedActiveOrders} looks for. */
	private static final int OBS_RECORD = 1;

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
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, NAPROXEN_ATC, KETOPROFEN_ATC);
		DrugReferenceTestSupport.makeOrderNameless(ORDER, ORDERED_CONCEPT);
	}

	private TestableService serviceUnderTest(LlmProvider provider, Boolean judgeVerdict) {
		DrugReferenceService reference = DrugReferenceTestSupport.curatedService();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		TestableVerifier verifier = new TestableVerifier();
		verifier.setLlmProvider(new FixedJudge(judgeVerdict));
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
	 * <p>The claim unit is the record's own sentence and cites nothing else, which is what keeps
	 * issue #284's withholding out of it: {@code claimRestsOn} is built from CITED indexes, so the
	 * injected reference material that shares this chart is not in the intersection. Cite a finding
	 * in the same sentence and the verdict is withheld instead — already pinned, see the class
	 * javadoc.
	 */
	@Test
	public void aCodesOnlyActiveOrderCitationCarriesTheJudgesRefusalThroughTheComposedPath() {
		TestableService service = serviceUnderTest(new CitesTheActiveOrderAlone(), Boolean.FALSE);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertFalse(ChartSearchAiUtils.isGroundingDemoteOnly(order.getResourceType()),
				"precondition: this type is chart evidence, so its verdict is NOT withheld at the "
						+ "wire — that is the exposure #294 is about");
		assertFalse(order.isAttachedByTheModule(),
				"precondition: the MODEL cited this record inline, so it is not the module's own "
						+ "attachment, which would be UNVERIFIABLE in either mode (#305)");
		assertEquals(Boolean.FALSE, order.getGrounded(),
				"a refused claim about a record naming no drug arrives as false — the value "
						+ "groundedForWire publishes for a chart-group citation, and the one a client "
						+ "renders as Unsupported");
	}

	/**
	 * The other direction, and it is the one the live measurement on #294 actually observed: the
	 * same arrangement with the judge accepting publishes {@code true}, so the composed path is not
	 * hardwired to either verdict and the exposure above is a property of the JUDGE's answer rather
	 * than of the record's type.
	 *
	 * <p>Worth pinning beside its sibling because the deliberate non-extension of the demote-only
	 * carve-out to this type means a pass VERIFIES here rather than rendering unverified — ADR
	 * Decision 25's carve-out is scoped to reference prose, and
	 * {@code CitationGroundingVerifierTest.activeDrugOrder_highCosinePassRendersVerifiedNotDemoted}
	 * is where that decision is recorded.
	 */
	@Test
	public void theSameCitationCarriesAnAcceptanceThroughToo() {
		TestableService service = serviceUnderTest(new CitesTheActiveOrderAlone(), Boolean.TRUE);

		ChartAnswer answer = service.search(patient, QUESTION);

		RecordReference order = activeOrderReference(answer);
		assertNotNull(order, "the unrepresented order must reach the answer as a cited record, was: "
				+ answer.getReferences());
		assertEquals(Boolean.TRUE, order.getGrounded(),
				"an accepted claim publishes true for this type — demote-only is scoped to "
						+ "drug-reference prose, not to everything the module injects");
	}

	/** Exposes the seams, and keeps warmup out of a test about a reference list. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/**
	 * A one-record chart carrying no drug-order record at all, so patient 7's order 111 is
	 * unrepresented and the reconciliation injects it. Built through {@code chartOf}, which is the
	 * one home of the {@code "[N] text"} numbered rendering the serializer produces — the rendering
	 * the answer's own citation number is parsed back out of below.
	 */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(
					DrugReferenceTestSupport.obsRecord(OBS_RECORD, "BP 120/80"));
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/**
	 * A verifier with no Tier-1 embedding model, which is a real deployment shape and not a
	 * convenience: {@code resolveEmbedder()} returns null in production wherever querystore's
	 * provider cannot be resolved, Tier-1 cosine is then skipped, and the authoritative Tier-2 pass
	 * still applies. It is also the only honest choice here — see the class javadoc on why no cosine
	 * is measured.
	 */
	private static final class TestableVerifier extends CitationGroundingVerifier {

		@Override
		TextEmbedder resolveEmbedder() {
			return null;
		}
	}

	/** A judge that answers the same way for every pair it is handed. */
	private static final class FixedJudge extends LlmProvider {

		private final Boolean verdict;

		private FixedJudge(Boolean verdict) {
			this.verdict = verdict;
		}

		@Override
		public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
			List<Boolean> out = new ArrayList<Boolean>();
			for (int i = 0; i < sources.size(); i++) {
				out.add(verdict);
			}
			return out;
		}
	}

	/**
	 * Cites the injected active-order record ALONE, in a sentence of its own that makes a medication
	 * claim about the patient — the shape #294's text describes, and the shape issue #284's
	 * withholding does not reach since the sentence cites no reference material.
	 *
	 * <p><b>This shape is hypothetical, and the live measurement is why that is worth saying.</b> Over
	 * the nine cells ADR Decision 38's owed-measurement section records, the model wrote no such
	 * sentence about a record naming no drug: where it cited the record at all it wrote one that names
	 * no drug either, which the record entails. So what the cases here measure is the module's HANDLING
	 * of this shape, and the ADR section is what says whether a real model produces it. Do not read a
	 * green run here as evidence that it does.
	 */
	private static final class CitesTheActiveOrderAlone extends LlmProvider {

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
			return new LlmResponse("The patient is taking naproxen 500mg twice daily [" + order + "].",
					Collections.singletonList(Integer.valueOf(order)));
		}
	}
}
