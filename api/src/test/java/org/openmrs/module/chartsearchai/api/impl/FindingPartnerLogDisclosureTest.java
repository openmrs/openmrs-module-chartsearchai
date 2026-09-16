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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingPartnerCoverage;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * What {@link FindingPartnerCoverageCheck} may write to the server log about a patient
 * (issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/439">#439</a>).
 *
 * <p><b>The defect.</b> The shortfall WARN carried the {@code unstated} list itself — the names of
 * the active orders the prose left out — on the reasoning, written into the comment above it, that a
 * partner name is "this module's own reference vocabulary, not the patient's data". The vocabulary is
 * the module's; WHICH of its words appear is decided entirely by what this patient is prescribed, an
 * interaction chip being raised only for a partner the patient actually has an order for. Patient id
 * beside medication names is PHI, and core ships {@code org.openmrs} at WARN, so the line reached the
 * default server log and any log shipping a deployment configures — a wider audience than the
 * clinicians holding <i>AI Query Patient Data</i>.
 *
 * <p><b>Nothing is lost by taking the names out.</b> ADR Decision 100 has the module APPEND them to
 * the answer, so the reader who holds the privilege already receives every name; the counts the log
 * keeps are the two {@code findingPartners} publishes. {@link #theOrdersReachTheAnswerAClinicianIsHanded}
 * and {@link #searchStreaming_theOrdersReachTheAnswerItReturns} are what make that a move rather than
 * a deletion — remove the append and they redden, so a fix that simply stopped naming the orders
 * anywhere cannot pass this file. They assert it of the answer each method RETURNS; the one surface
 * that does not carry the appended sentence is the early {@code done} the REST layer emits under
 * {@code chartsearchai.grounding.async=true}, and ADR Decision 102 states that residue rather than
 * this file pinning it, since pinning it would forbid the fix.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming}
 * orchestration over real merged chips the real {@code DrugSafetyValidator} raised from the shared
 * fixture ({@code DrugReferenceTestSupport.sharedMechanismInteractionChips}). Only the model is
 * stubbed: answer prose is not reproducible on a live engine, and the answer is the one input this
 * check reads.
 */
public class FindingPartnerLogDisclosureTest {

	/** The package the orchestration logs from, so the claim is about every logger in it and not only
	 *  this check's — a name leaking from a neighbour is the same disclosure. It is NOT a claim about
	 *  the whole module: the answer path's other half logs from {@code ...chartsearchai.reference},
	 *  which this harness stubs out (the validator and the injector are overridden below), and that
	 *  half's own two sites are pinned where they are written —
	 *  {@code ActiveOrderReconciliationTest.theReconciliationWarnIdentifiesTheOrderByUuidAndNeverByItsDrugName}
	 *  and {@code PairChipCapContextTest.theScreeningWarnRatesTheWithheldPairsAtTheConfiguredCapAndNamesNoDrug}.
	 *
	 *  <p>Widening it to the module root was tried and reverted: {@link LogCapture} raises the level of
	 *  the NAMED logger only, so with the root named this file's captures received this pass's WARN but
	 *  not its INFO or DEBUG once the whole suite ran, and the liveness precondition each case asserts
	 *  failed (measured 2026-09-16, on the one case that logs no WARN of its own). Named here, the
	 *  capture is live: {@code LlmInferenceService}'s own [timing] INFO line arrives on every case, so a
	 *  negative cannot pass because nothing was ever captured ({@link LogCapture}'s javadoc). */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	/** The check's own WARN, by a phrase no neighbour writes — for the cases whose negative needs
	 *  this check to have RUN. */
	private static final String SHORTFALL_WARN = "active order(s) its safety finding(s) name";

	/** An answer that names NO order the chips cover, so every name is in the shortfall the WARN
	 *  reports — the maximal-exposure shape, and the one the old line wrote out in full. */
	private static final String NAMES_NO_ORDER =
			"No — aspirin should not be started; her current medicines make it unsafe.";

	/** The chips production is handed: the REAL ones, one merged over two active orders. */
	private List<SafetyWarning> chips;

	/** Every order those chips name, as they name it. */
	private List<String> partners;

	private TestableService service;

	@BeforeEach
	public void setUp() throws IOException {
		chips = DrugReferenceTestSupport.sharedMechanismInteractionChips(new PairChipExtent.Sink());
		partners = DrugReferenceTestSupport.namedPartners(chips);
		assertTrue(partners.size() >= 2,
				"the premise every case below rests on: the real validator raised chips naming more "
						+ "than one of this patient's active orders, so there is a list to leak. Was: "
						+ partners);
		for (String partner : partners) {
			assertFalse(NAMES_NO_ORDER.toLowerCase(Locale.ROOT).contains(partner.toLowerCase(Locale.ROOT)),
					"and the arranged answer names none of them, so the shortfall the WARN reports is "
							+ "the whole list: " + partner);
		}
		service = newService();
	}

	@Test
	public void noLogLineNamesAnOrderTheFindingsCover() {
		service.setLlmProvider(answering(NAMES_NO_ORDER));
		// DEBUG, so the claim covers every level and not only the one core ships: the finding's own
		// criterion is "nothing at INFO or above", and below it the module has no reason to write
		// these names at all — the clinician's answer carries them.
		try (LogCapture capture = LogCapture.on(PACKAGE, Level.DEBUG)) {
			ChartAnswer answer = service.search(patient(), DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own lines, or the assertions below pass "
							+ "vacuously");
			assertNotNull(answer.getFindingPartnerCoverage(),
					"the premise: the check ran and measured this response");
			assertTrue(capture.hasMessageAt(Level.WARN, SHORTFALL_WARN),
					"and reported the shortfall, so the assertions below are about a line that was "
							+ "written. Captured: " + capture.describeAll());
			for (String partner : partners) {
				for (String logged : capture.describeAll()) {
					assertFalse(logged.toLowerCase(Locale.ROOT).contains(partner.toLowerCase(Locale.ROOT)),
							"no log line may name an active order of this patient's: the names are "
									+ "selected by what the patient is prescribed, and the patient id is "
									+ "on the same line. Found \"" + partner + "\" in: " + logged);
				}
			}
		}
	}

	@Test
	public void theWarnReportsTheShortfallAsTheTwoCountsTheWirePublishes() {
		// The other half: taking the names out may not take the diagnostic out. The maintainer's
		// channel still says a shortfall happened, for which patient, and how big — and says it with
		// the numbers findingPartners publishes, so the log and the wire cannot come to disagree.
		service.setLlmProvider(answering(NAMES_NO_ORDER));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

			FindingPartnerCoverage coverage = answer.getFindingPartnerCoverage();
			assertNotNull(coverage, "the premise: the check measured this response");
			assertTrue(capture.hasMessageAt(Level.WARN, "patient=" + patient().getPatientId(),
					"stated " + coverage.getStated() + " of " + coverage.getNamed()),
					"the shortfall must still be reported, with the patient and the same two counts "
							+ "the response states. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerNamingEveryOrderReportsNoShortfallAndIsReturnedUntouched() {
		// The guard's OTHER value. Everything above arranges a shortfall, so `stated < named` was only
		// ever asked in the state that makes it true: mutate it to `stated <= named` and the WARN fires
		// on every measured response, with the whole suite green. This is the case that reddens on that
		// — an answer that names every order the findings cover, where the module has nothing to report
		// and nothing to append.
		//
		// Built FROM the chips' own names rather than spelled here, so it cannot drift from what the
		// real validator raised.
		StringBuilder namesThemAll = new StringBuilder("Aspirin is unsafe here given her");
		for (String partner : partners) {
			namesThemAll.append(' ').append(partner).append(',');
		}
		String answerNamingAll = namesThemAll.append(" so it should not be started.").toString();
		service.setLlmProvider(answering(answerNamingAll));
		try (LogCapture capture = LogCapture.on(PACKAGE, Level.DEBUG)) {
			ChartAnswer answer = service.search(patient(), DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own lines, or the assertion below passes "
							+ "vacuously");
			FindingPartnerCoverage coverage = answer.getFindingPartnerCoverage();
			assertNotNull(coverage, "the premise: the check ran and measured this response");
			assertEquals(coverage.getNamed(), coverage.getStated(),
					"the premise this case exists for: the arranged answer states every name the "
							+ "findings carry, so there is no shortfall to report");
			assertFalse(capture.hasMessageAt(Level.WARN, SHORTFALL_WARN),
					"and then no shortfall may be reported at any level. Captured: "
							+ capture.describeAll());
			assertEquals(answerNamingAll, answer.getAnswer(),
					"and the answer is returned byte for byte, with nothing appended");
		}
	}

	@Test
	public void theOrdersReachTheAnswerAClinicianIsHanded() {
		// What makes the case above a MOVE and not a deletion: the names the log gives up are the ones
		// ADR Decision 100 appends to the answer, which reaches a reader holding the privilege. Delete
		// the append and this reddens; delete the names from the log and it does not.
		service.setLlmProvider(answering(NAMES_NO_ORDER));
		ChartAnswer answer = service.search(patient(), DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		String completed = answer.getAnswer().toLowerCase(Locale.ROOT);
		for (String partner : partners) {
			assertTrue(completed.contains(partner.toLowerCase(Locale.ROOT)),
					"every order the findings cover must reach the clinician, which is the channel that "
							+ "may carry a name. Missing \"" + partner + "\" from: " + answer.getAnswer());
		}
	}

	@Test
	public void searchStreaming_theOrdersReachTheAnswerItReturns() {
		// The compensating delivery on the path users hit, and the pointer ADR Decision 102 makes to
		// this file claims both paths. What it does NOT claim, and what the decision now states as the
		// residue: under chartsearchai.grounding.async=true the REST layer emits `done` from the
		// UNGROUNDED answer handed to its consumer mid-pass, which is response.getAnswer() — the
		// pre-append text — and the trailing `grounded` event carries no `answer` key. There the names
		// reach a client on that event's safetyWarnings[].namedPartners and not in the prose.
		service.setLlmProvider(answering(NAMES_NO_ORDER));
		ChartAnswer answer = service.searchStreaming(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, token -> { });

		String completed = answer.getAnswer().toLowerCase(Locale.ROOT);
		for (String partner : partners) {
			assertTrue(completed.contains(partner.toLowerCase(Locale.ROOT)),
					"every order the findings cover must reach the clinician on this path too. Missing \""
							+ partner + "\" from: " + answer.getAnswer());
		}
	}

	@Test
	public void searchStreaming_namesNoOrderInTheLogEither() {
		// /search/stream is the path users hit, and it is the second call site the finding names. A
		// fix applied to one of the two would leave the disclosure in production traffic while every
		// case above stayed green.
		service.setLlmProvider(answering(NAMES_NO_ORDER));
		try (LogCapture capture = LogCapture.on(PACKAGE, Level.DEBUG)) {
			ChartAnswer answer = service.searchStreaming(patient(),
					DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, token -> { });

			assertNotNull(answer.getFindingPartnerCoverage(),
					"the premise: the streaming path ran the check too");
			// This check's OWN line, by its wording. A bare "something warned at WARN" is satisfied
			// by a neighbour — the preliminary-reasoning WARN fires on this harness — so the negative
			// below would pass on a streaming path that never ran the check at all.
			assertTrue(capture.hasMessageAt(Level.WARN, SHORTFALL_WARN),
					"and reported the shortfall, or the negative below is about a check that never "
							+ "ran. Captured: " + capture.describeAll());
			for (String partner : partners) {
				for (String logged : capture.describeAll()) {
					assertFalse(logged.toLowerCase(Locale.ROOT).contains(partner.toLowerCase(Locale.ROOT)),
							"the streaming path writes the same line and must not name an order either. "
									+ "Found \"" + partner + "\" in: " + logged);
				}
			}
		}
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static StubProvider answering(String answer) {
		return new StubProvider(answer);
	}

	/** The chart, rendered by the REAL serializer: this patient's own drug orders, so the answer path
	 *  runs over a chart of the shape the chips were raised against. */
	private static PatientChart chart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Prednisone 5mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Heparin 5000 units, subcutaneous", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	/** The same seam the sibling fidelity tests use, and defined here for the same reason they each
	 *  define their own: these are private harnesses, not a shared one. The one difference is the
	 *  validator, which returns the REAL chips this file is about rather than none. */
	private TestableService newService() {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(chart()));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question,
					ChartReadStatus readStatus) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing a shorter one instead leaves this stub
			// INERT — production would not reach it — which is why this names both parameters.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<PatientChartSerializer.RecordMapping> mappings,
					PairChipExtent.Sink pairExtentSink) {
				return chips;
			}
		});
		return created;
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private StubProvider(String answer) {
			this.answer = answer;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			return canned();
		}
	}
}
