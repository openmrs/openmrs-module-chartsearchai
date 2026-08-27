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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #315: an answer that NAMES a drug from a drug-order record the module has marked
 * {@code ". Order status: not in force"} must tell the clinician the prescription has ended.
 *
 * <p><strong>Why this is a prompt rule and not another structural change.</strong> Issue #317
 * (Decision 46) already put the discriminator in the record: {@link PatientChartSerializer} appends
 * {@link PatientChartSerializer#ACTIVE_ORDER_LABEL} / {@link PatientChartSerializer#INACTIVE_ORDER_LABEL}
 * to every attributable {@code drug_order} record body, and the model demonstrably READS it — issue
 * #315's own re-measurement records an answer quoting <em>"the order status is not in force"</em>
 * back unprompted. What was missing is any instruction that the field MATTERS, which is ADR Decision
 * 45's own diagnosis in as many words: <em>"The reported defect is not that the model cannot read the
 * marker; it is that nothing tells it the marker MATTERS."</em>
 *
 * <p><strong>Decision 45 REJECTED a prompt clause for this ticket, and this is not a re-proposal of
 * it.</strong> That entry's impossibility argument is explicitly scoped to a BINARY framing over TEXT
 * markers, and both of its load-bearing premises are void since #317. Its item 3 — <em>"a
 * classification is only as good as the discriminator available to the classifier, and here there is
 * none"</em> — described a chart in which an order lapsed by {@code auto_expire_date} rendered no end
 * marker at all; that order now renders {@code ". Order status: not in force"}, pinned by
 * {@code DrugOrderCurrencyMarkTest.anOrderThatLapsedByItsDurationIsMarkedNotActive}. Its item 1 — the
 * ENDED branch needs a CURRENT contrast to fire — described a prompt that had to CONSTRUCT the
 * contrast from markers; the contrast is now published in the record as the field's positive value.
 * So the clause is not a classification instruction at all: it is a REPORTING instruction conditioned
 * on a token the record states, and a record carrying no mark simply fails its antecedent.
 *
 * <p>That is an argument rather than a measurement, which is why the clause was gated on an
 * interleaved A/B over ADR Decision 45's own two decisive cells plus the two residue shapes it
 * records — see the PR for #315 and Decision 47.
 *
 * <p><strong>What is real here, and what is not.</strong> Real: {@code OrderService}, the orders
 * themselves, querystore's own {@link DrugOrderRecordSerializer}, {@link QueryStoreChartBuilder}'s
 * build path through {@code toSerializedRecords} and {@code readOrderCurrency},
 * {@link PatientChartSerializer#serialize}, {@link LlmProvider#DEFAULT_SYSTEM_PROMPT},
 * {@link LlmProvider#buildUserMessage}, {@link LlmProvider#extractResponse}, and the model. Stubbed:
 * querystore's INDEX, because it does not run under {@link BaseModuleContextSensitiveTest} — the same
 * seam {@code DrugOrderCurrencyMarkTest} and {@code QueryStoreChartBuilderTest} use — and the
 * builder's global-property seams, so the mode under test is chosen rather than read.
 *
 * <p>The two answer cases are <b>opt-in</b> and skipped without an endpoint, the convention
 * {@link LlmEndpointTestSupport} exists for. They repeat with a DECOY completion between samples
 * rather than consecutively: Decision 45's methodology finding is that consecutive repeats re-use
 * llama's KV prefix and so reproduce the previous decode rather than re-testing it, and issue #315's
 * own re-measurement caught this exact question class at 1 of 3 on one build.
 */
public class EndedOrderAnswerRuleTest extends BaseModuleContextSensitiveTest {

	private static final Logger log = LoggerFactory.getLogger(EndedOrderAnswerRuleTest.class);

	private static final String ENABLE_PROPERTY = "chartsearchai.ended.order.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.ended.order.endpoint";

	private static final int MAX_TOKENS = ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;

	/** How many times each answer case is sampled, with a decoy completion between samples. */
	private static final int SAMPLES = 3;

	/** {@code DrugOrderCurrencyTestData.xml}: patient 6's only drug order, lapsed by its DURATION —
	 *  {@code auto_expire_date} 2008-01-09, {@code date_stopped} NULL. querystore renders NO end
	 *  marker for it, so the mark is the ONLY evidence in the chart that the prescription ended.
	 *  Not to be confused with order 9317, the OTHER lapsed row (patient 2, {@code auto_expire_date}
	 *  2008-01-08), which {@code DrugOrderCurrencyMarkTest} calls {@code LAPSED_ORDER_ID}. This one
	 *  is that class's {@code ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE}, and it is the row wanted
	 *  here because #315's arrangement is one ended prescription and nothing active. */
	private static final int LAPSED_ORDER_ID = 9318;

	/** Standard test dataset order 2: patient 2, {@code date_stopped} 2007-12-10 — the ticket's own
	 *  shape, where the record text carries an end AND the mark, and the answer still dropped it. */
	private static final int STOPPED_ORDER_ID = 2;

	/** The ticket's row-2 question, the shape its re-measurement records at 3/3 naming the drug and
	 *  0/3 saying it ended, byte-identical across the #318 base and post arms. */
	private static final String PRESCRIBED_QUESTION = "what medications has he been prescribed?";

	/** The decoy fired between samples so each one re-prefills rather than reusing the KV prefix. */
	private static final String DECOY_QUESTION = "what is the patient's most recent weight?";

	private CountingQueryStoreStub queryStore;

	private TestableBuilder builder;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet("DrugOrderCurrencyTestData.xml");
		queryStore = new CountingQueryStoreStub();
		builder = new TestableBuilder(queryStore.asService());
		builder.setChartSerializer(new PatientChartSerializer());
	}

	/**
	 * The rule is in the prompt at all.
	 *
	 * <p><strong>This case cannot see a rename of the mark, and must not be described as if it
	 * could.</strong> javac inlines a {@code static final String} into the using class's constant
	 * pool, so once the clause is composed from {@link PatientChartSerializer#INACTIVE_ORDER_LABEL}
	 * this assertion compares a constant to itself — the blind spot {@code CLAUDE.md} records for
	 * {@code ChartSearchAiAuditSearchModeTest}, whose four spellings are pinned as literals for
	 * exactly this reason. What pins the COUPLING is
	 * {@link #thePromptsTriggerTokenIsTheSerializersConstantAndNotACopy}; what pins the mark's own
	 * spelling is {@code DrugOrderCurrencyMarkTest.theTwoMarksAreSpelledExactlyAsMeasured}.
	 */
	@Test
	public void theSystemPromptStatesTheRuleForAnOrderThatIsNotInForce() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		assertTrue(prompt.contains(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the system prompt must name the mark a drug-order record carries when the module has "
						+ "established the order is not in force — without it the model is left to infer "
						+ "an order's currency from its dates, which is issue #315");
		assertTrue(prompt.contains("say in the same sentence that its order is no longer in force"),
				"the system prompt must require the statement to travel WITH the drug's name — issue "
						+ "#315's defect is an answer that names the drug and drops its status, so a rule that "
						+ "does not bind the two together does not close it. This assertion pins the exact "
						+ "measured wording, so swapping the verb reddens THIS case — and only this case: "
						+ "the two answer cases stay green under that swap, which is why ADR Decision 47 "
						+ "records the verb as chosen on prose and on its other cells rather than on "
						+ "whether the rule fires");
		assertFalse(prompt.contains(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"the clause has ONE branch and says nothing about a record marked in force. ADR Decision "
						+ "45 measured a positive currency half making the model re-state live orders in "
						+ "prose, and this clause was cleared by an A/B in which it says nothing about "
						+ "them: adding a positive half is a new change needing its own measurement, not a "
						+ "symmetry fix (ADR Decision 47)");
	}

	/**
	 * The clause's trigger token is {@link PatientChartSerializer#INACTIVE_ORDER_LABEL} itself, not a
	 * literal copy of its current text.
	 *
	 * <p>Structural because nothing behavioural can be: javac folds the constant into
	 * {@code LlmProvider}'s own constant pool, so a copy and a reference produce byte-identical
	 * output and every assertion over {@code DEFAULT_SYSTEM_PROMPT} passes either way. A copy would
	 * survive until someone changed the mark's wording — which Decision 46's javadoc explicitly
	 * anticipates ("A change to either string is a change to what every chart says to the model") —
	 * and the prompt would then teach the model a token no record carries, silently.
	 *
	 * <p>Read the SOURCE the way {@code ArchitectureGuardTest},
	 * {@code OrderPartnerNameSourceWritePathTest} and
	 * {@code DrugOrderCurrencyMarkTest.theFailedReadPathReturnsTheNotReadState} already do here.
	 * Scoped to the constant's initializer rather than the whole file, for the reason that last case
	 * slices a method body before asserting: this file's convention is a paragraph of comment above
	 * every prompt rule, and such a comment quoting the mark is not a defect.
	 */
	@Test
	public void thePromptsTriggerTokenIsTheSerializersConstantAndNotACopy() throws Exception {
		Path source = ModuleSourceRoot.apiRoot().resolve(
				"src/main/java/org/openmrs/module/chartsearchai/api/impl/LlmProvider.java");
		assertTrue(Files.exists(source), "precondition: LlmProvider.java must be readable at " + source);
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

		int start = text.indexOf("static final String DEFAULT_SYSTEM_PROMPT");
		assertTrue(start > 0, "precondition: the prompt constant's declaration must be findable");
		// The initializer ends at its terminating quote-semicolon. Found by scanning rather than by
		// anchoring on whatever member happens to follow, which is how the first version of this case
		// was FAIL-OPEN: it ended the slice at the next "\n\tstatic ", 125 lines past the constant, so
		// a hardcoded copy in the prompt passed as long as the constant's name appeared anywhere in
		// those lines — demonstrated by mutation, green. An escaped quote inside the prompt text is
		// skipped, because the prompt really does contain \" sequences.
		int end = start;
		do {
			end = text.indexOf("\";", end + 1);
		} while (end > 0 && text.charAt(end - 1) == '\\');
		assertTrue(end > start, "precondition: the prompt constant's terminating quote-semicolon must "
				+ "be findable, or the slice below silently widens to the rest of the file");
		String initializer = text.substring(start, end + 2);
		assertFalse(initializer.contains("@Autowired"),
				"precondition: the slice must stop at the constant, not run on into the rest of the "
						+ "class — that over-wide window is what made this guard fail open. The canary is "
						+ "the FIRST thing after the constant rather than a distant one, because a canary "
						+ "further down leaves a window of lines a widening could take silently");

		// Normalised before the negative assertion, because every shape that defeated an earlier
		// version of this case lives in what normalisation removes, and there have been three. A
		// comment inside the initializer can carry the constant's dotted name while the prompt
		// hardcodes the text beside it (this file's convention is a comment paragraph above every
		// prompt rule, so that is not exotic); a hardcoded copy can be split across the file's own
		// line-wrap — "... not in " + "force" — which a contiguous search cannot see; and the split
		// can be held apart by a BLOCK comment, which survives stripping line comments alone. Strip
		// both comment forms, then join adjacent literals. Each of the three was found by mutation
		// rather than by reasoning, so add the mutation before trusting a fourth to be impossible.
		String normalised = initializer
				.replaceAll("(?s)/\\*.*?\\*/", "")
				.replaceAll("(?m)//.*$", "")
				.replaceAll("\"\\s*\\+\\s*\"", "");
		assertFalse(normalised.contains(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the prompt must not carry the mark's text as a literal, however it is split or "
						+ "commented around — it reads identically to a composed clause today and stops "
						+ "tracking the constant the moment the mark is renamed, which is the whole "
						+ "failure this case exists for");

		// TWO assertions, because either alone is satisfiable by the defect. The name can appear in a
		// comment inside the initializer while the prompt hardcodes the text beside it — which is the
		// same fail-open one bound wider already allowed, one scope in. So the mark's text must ALSO
		// not appear here as a Java string literal: the clause has to reach it through the constant.
		assertTrue(initializer.contains("PatientChartSerializer.INACTIVE_ORDER_LABEL"),
				"the prompt's order-status clause must be BUILT from "
						+ "PatientChartSerializer.INACTIVE_ORDER_LABEL rather than carrying a copy of its "
						+ "text: javac inlines the constant, so a copy is invisible to every behavioural "
						+ "assertion in this file and would go on teaching the old token after the mark "
						+ "was renamed");
	}

	/**
	 * The lapsed-by-duration order — the shape where the mark is the ONLY evidence the prescription
	 * ended, because querystore renders nothing for {@code auto_expire_date}.
	 */
	@Test
	public void anAnswerNamingADrugFromAnOrderThatLapsedByItsDurationSaysSo() throws Exception {
		assertAnswerReportsTheOrderEnded(6, LAPSED_ORDER_ID, "lapsed");
	}

	/**
	 * The {@code date_stopped} shape, where querystore's text carries {@code ". Stopped: <date>"} as
	 * well as the mark.
	 *
	 * <p><strong>This case PASSES on the pre-change code and is a regression guard, not the failing
	 * test for this ticket.</strong> Measured before the clause existed: it answered
	 * <em>"Triomune-30 was stopped on 2007-12-10 [1]"</em>, because the record's own text carries the
	 * end and the model recited it. Issue #315's defect is that on OTHER charts it does not — on the
	 * 3.7.1 standalone the same question over an equivalent single stopped order answers
	 * <em>"Nevirapine was prescribed on 2026-07-26 [1]."</em> 3/3 — and this fixture does not
	 * reproduce that. Saying so rather than implying a guard this file does not have: what fails
	 * today, for the ticket's own reason, is
	 * {@link #anAnswerNamingADrugFromAnOrderThatLapsedByItsDurationSaysSo}, which is also the
	 * stronger case because the mark is the only evidence there.
	 *
	 * <p>What it guards is the direction Decision 45's residue warns about: a clause that makes the
	 * model re-state a drug order in prose can LOSE the end its record already stated. If this case
	 * ever reddens, the clause has taken something away rather than added to it.
	 */
	@Test
	public void anAnswerNamingADrugFromAStoppedOrderStillReportsTheEnd() throws Exception {
		assertAnswerReportsTheOrderEnded(2, STOPPED_ORDER_ID, "stopped");
	}

	/**
	 * Builds the real chart for one patient over one drug order, asks the ticket's own question
	 * through the real prompt, and asserts every sample names the drug AND reports that its order has
	 * ended.
	 *
	 * <p>The oracle is exact containment, case-insensitively, on an OR list of the ways an answer can
	 * report the end — the discipline {@code AbsentDataEvalTest} states for its own expectations. The
	 * drug's name is read off the real {@link DrugOrder} rather than hardcoded, so a change to the
	 * fixture cannot make the case pass by naming nothing.
	 */
	private void assertAnswerReportsTheOrderEnded(int patientId, int orderId, String label)
			throws Exception {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint),
				"Skipping: LLM endpoint not reachable at " + endpoint);

		Patient patient = Context.getPatientService().getPatient(patientId);
		DrugOrder order = (DrugOrder) Context.getOrderService().getOrder(orderId);
		assertTrue(!order.isActive(),
				"precondition [" + label + "]: OrderService must consider order " + orderId
						+ " inactive, or this case proves nothing");

		queryStore.stubChart = new ArrayList<QueryDocument>(
				Arrays.asList(new DrugOrderRecordSerializer().serialize(order)));
		PatientChart chart = builder.build(patient, PRESCRIBED_QUESTION);
		assertTrue(chart.getText().contains(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"precondition [" + label + "]: the chart the model reads must carry the mark, or this "
						+ "case is testing the prompt against evidence that is not there. Chart was: "
						+ chart.getText());

		String drugName = drugNameOf(order);
		for (int sample = 1; sample <= SAMPLES; sample++) {
			decoy(endpoint, chart);
			String raw = LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
					LlmProvider.buildUserMessage(chart.getText(), PRESCRIBED_QUESTION), MAX_TOKENS);
			String answer = LlmProvider.extractResponse(raw).getAnswer();
			log.info("[{} sample {}] {}", label, sample, answer);

			assertNotNull(answer, label + " sample " + sample + ": no answer was produced");
			String lower = answer.toLowerCase(Locale.ROOT);
			assertTrue(lower.contains(drugName.toLowerCase(Locale.ROOT)),
					label + " sample " + sample + ": precondition — the answer must name the drug, or "
							+ "it satisfies this rule vacuously. Was: " + answer);
			assertTrue(reportsAnEndedOrder(lower),
					label + " sample " + sample + ": the answer names " + drugName + " from a record "
							+ "marked \"" + PatientChartSerializer.INACTIVE_ORDER_LABEL + "\" and gives "
							+ "no cue that the prescription ended (issue #315). Was: " + answer);
		}
	}

	/**
	 * Whether an answer reports that the order it names has ended.
	 *
	 * <p>OR over the wordings a correct answer can use, because which one the model reaches for is
	 * its own word choice and not a property under test. Every element states an ENDING; none of them
	 * is satisfiable by an answer that merely names the drug, which is what
	 * {@link #anAnswerNamingNoEndingFailsTheOracle} asserts rather than assumes.
	 */
	private static boolean reportsAnEndedOrder(String lowerAnswer) {
		for (String phrase : Arrays.asList("not in force", "no longer in force", "stopped",
				"discontinued", "no longer being taken", "has ended", "was ended")) {
			if (lowerAnswer.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The oracle rejects what it is supposed to reject. Runs without an endpoint, so the two answer
	 * cases being skipped in CI does not leave the oracle itself unchecked — the guard
	 * {@code AbsentDataEvalTest.anAnswerNamingNoTopicFailsEveryCaseThatNamesOne} exists for.
	 */
	@Test
	public void anAnswerNamingNoEndingFailsTheOracle() {
		for (String answer : Arrays.asList("Nevirapine was prescribed on 2026-07-26 [1].",
				"Nevirapine was ordered on 2026-07-26 [1].",
				"The patient has been prescribed Triomune-30 [1].")) {
			assertTrue(!reportsAnEndedOrder(answer.toLowerCase(Locale.ROOT)),
					"the oracle must reject an answer that names the drug and no ending — this is issue "
							+ "#315's own measured defect text: " + answer);
		}
		assertTrue(reportsAnEndedOrder(
				"nevirapine was prescribed on 2026-07-26 [1]; its order is no longer in force."),
				"the oracle must accept an answer that names the drug and reports the ending");
	}

	/** A completion on different bytes, so the next sample re-prefills instead of reusing the prefix. */
	private void decoy(String endpoint, PatientChart chart) throws Exception {
		LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
				LlmProvider.buildUserMessage(chart.getText(), DECOY_QUESTION), MAX_TOKENS);
	}

	/** The drug's name as the record names it, read off the real order rather than hardcoded. */
	private static String drugNameOf(DrugOrder order) {
		if (order.getDrug() != null && order.getDrug().getName() != null) {
			return order.getDrug().getName();
		}
		return order.getConcept().getName().getName();
	}

	/**
	 * Subclass supplying the two seams this module's tests already use — querystore's service and the
	 * global-property reads. Everything else is the real builder. A private nested testable builder
	 * per test class is this repo's convention; four others exist.
	 */
	private static final class TestableBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		TestableBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 100;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}

		@Override
		protected int resolveProgressiveReasoningTopK() {
			return 10;
		}
	}
}
