/*
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * to a {@code drug_order} record body wherever the module could establish the answer — and nothing
 * where it could not, which {@code SerializedRecord.getOrderActive()} enumerates. The model
 * demonstrably READS it — issue
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
 * <p>The four answer cases — two in the direction the clause fires, two in the CONVERSE direction
 * ({@link #anAnswerOverAChartOfOneLiveOrderReportsItAsCurrent},
 * {@link #anAnswerOverAMixedChartStillNamesTheLiveDrug}) — are <b>opt-in</b> and skipped
 * without an endpoint, the convention
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

	/** Standard test dataset order 444: ASPIRIN, patient 2, activated 2008-06-25, no stop date and no
	 *  {@code auto_expire_date} — in force. Chosen over {@link #LIVE_ORDER_ID} for the mixed chart
	 *  because it is a DIFFERENT drug from the ended row beside it, which is what lets the oracle
	 *  ask whether the live one survived into the answer at all. */
	private static final int LIVE_ORDER_OF_ANOTHER_DRUG_ID = 444;

	/** Standard test dataset order 3: Triomune-30, patient 2, activated 2008-02-08, never stopped —
	 *  IN FORCE, and so marked {@code ". Order status: in force"}. The row
	 *  {@code DrugOrderCurrencyMarkTest} calls {@code LIVE_ORDER_ID}, reused here for the CONVERSE
	 *  direction: see {@link #anAnswerOverAChartOfOneLiveOrderReportsItAsCurrent}. */
	private static final int LIVE_ORDER_ID = 3;

	/** The ticket's row-2 question, the shape its re-measurement records at 3/3 naming the drug and
	 *  0/3 saying it ended, byte-identical across the #318 base and post arms. */
	private static final String PRESCRIBED_QUESTION = "what medications has he been prescribed?";

	/** The currency-shaped question — the shape ADR Decision 45's clause folded on, where the model
	 *  has to CLASSIFY rather than list, and so the shape the converse case asks. */
	private static final String TAKING_QUESTION = "what medications is he taking?";

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
	 *
	 * <p><strong>What the ONE-BRANCH assertions really catch, measured by mutation on this head and
	 * not reasoned about.</strong> Three assertions, and they are unequal — say which does the work
	 * rather than letting the trio look uniform.
	 * <ul>
	 * <li><b>The region equality</b> is the one that pins the property. The order-status rule must be
	 * exactly the two measured sentences over the WHOLE span between the {@code "Drug reference"}
	 * sentence and the {@code "Safety finding"} one, so a second half added on either side of it
	 * reddens. Demonstrated with a review round's own mutation — appending <em>"A drug-order record
	 * that does not carry it is a record of a prescription the patient is still on; report such a
	 * drug as current."</em> to the clause: RED here, where before this assertion existed the whole
	 * of {@code EndedOrderAnswerRuleTest} and {@code LlmProviderTest} stayed green on it.</li>
	 * <li><b>The occurrence count</b> reaches outside that region, which is the only reason it is a
	 * separate assertion. The same sentence moved down into the answering-rules paragraphs leaves the
	 * region intact and reddens the count: RED, measured.</li>
	 * <li><b>{@code assertFalse(contains(ACTIVE_ORDER_LABEL))}</b> pins almost nothing on its own — a
	 * positive half written without quoting that constant passes it. It is kept because it catches a
	 * half built the way the negative one is, from the sibling constant, which is the shape a
	 * symmetry-minded change reaches for first.</li>
	 * </ul>
	 *
	 * <p><strong>The residue, stated rather than papered over.</strong> A positive currency
	 * instruction placed OUTSIDE the region and worded with neither the phrase {@code "drug-order
	 * record"} nor the mark's constant gets past all three — measured GREEN with <em>"A prescription
	 * whose record states no such status is one the patient is still on; report it as current."</em>
	 * appended after the citation rule. Closing that would need a blocklist of positive-half
	 * phrasings, which {@code CLAUDE.md}'s own {@code score_probe_safety} lesson says not to build:
	 * three successive marker lists there were each refuted by a wording nobody had thought of. So
	 * the guard is stated positively — this REGION is exactly this TEXT, and the record class is
	 * named ONCE — and a shape that evades it is a new assertion here, not a looser one.
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
						+ "the two ENDED-direction answer cases stay green under that swap, which is why "
						+ "ADR Decision 47 "
						+ "records the verb as chosen on prose and on its other cells rather than on "
						+ "whether the rule fires");
		assertFalse(prompt.contains(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"the clause must not name the mark a record carries when the order IS in force. This is "
						+ "the narrowest of the three one-branch assertions and on its own it pins almost "
						+ "nothing — a positive half written without quoting that constant passes it "
						+ "(demonstrated by mutation, see this method's javadoc). What it does catch is a "
						+ "half built the way the negative one is, from the sibling constant");

		// THE ONE-BRANCH PROPERTY, asked as a REGION rather than as a list of words the clause may not
		// contain. The prompt's record-type section is a run of sentences; this rule owns exactly the
		// span between the "Drug reference" sentence and the "Safety finding" one, and that span must
		// be the two measured sentences and nothing else. Stated positively on purpose: a blocklist of
		// positive-half phrasings is unbounded (CLAUDE.md's own score_probe_safety lesson — three
		// marker lists were each refuted by a wording nobody had thought of), whereas "this region is
		// exactly this text" cannot be got past from inside the region at all.
		String clause = "A drug-order record carrying \"" + PatientChartSerializer.INACTIVE_ORDER_LABEL
				+ "\" is a record of an order that has ended. Whenever your answer names a drug from "
				+ "such a record, say in the same sentence that its order is no longer in force. ";
		String precedingRule = "already recorded for the patient. ";
		String followingRule = "Records beginning with \"Safety finding\"";
		int windowStart = prompt.indexOf(precedingRule);
		assertTrue(windowStart > 0, "precondition: the record-type sentence BEFORE the order-status "
				+ "clause must be findable, or the window below is not the one this case means to read");
		windowStart += precedingRule.length();
		int windowEnd = prompt.indexOf(followingRule, windowStart);
		assertTrue(windowEnd > windowStart, "precondition: the record-type sentence AFTER the "
				+ "order-status clause must be findable, or the window below silently runs to the end "
				+ "of the prompt and this assertion stops meaning anything");
		assertEquals(clause, prompt.substring(windowStart, windowEnd),
				"the order-status rule must be exactly the two measured sentences — the whole span "
						+ "between the \"Drug reference\" sentence and the \"Safety finding\" one, so a "
						+ "second half added on EITHER side of it reddens here. ADR Decision 45 measured a "
						+ "positive currency half making the model re-state live orders in prose, and this "
						+ "clause was cleared by an A/B in which it says nothing about a record marked in "
						+ "force. Adding a positive half is a new change needing its own measurement, not "
						+ "a symmetry fix (ADR Decision 47)");

		assertEquals(1, countOf(prompt, "drug-order record"),
				"the prompt must speak of a drug-order record exactly ONCE. This reaches OUTSIDE the "
						+ "window above, which is why it is a separate assertion: a positive half moved "
						+ "down into the answering-rules paragraphs leaves the window intact, and the "
						+ "natural wording of one (\"A drug-order record that does not carry it ...\") "
						+ "names the record class here");
	}

	/** Non-overlapping occurrences of {@code needle} in {@code haystack}. */
	private static int countOf(String haystack, String needle) {
		int count = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
			count++;
		}
		return count;
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
	 *
	 * <p>The two halves are asked of DIFFERENT windows, and which window each gets is the whole
	 * substance of this case. The NEGATIVE half — no hardcoded copy of the mark's text — is asked of
	 * the whole file, because a copy does not have to live inside the prompt to be a copy: a sibling
	 * {@code private static final} declared beside the constant reads identically at the call site
	 * and sits outside any slice of the initializer. The POSITIVE half — the clause reaches the
	 * serializer's constant — is asked of the initializer, because a dotted name elsewhere in the
	 * file says nothing about how the clause is built. Both are asked of the COMMENT-STRIPPED text,
	 * which is what lets the negative half widen safely: this file's convention is a paragraph of
	 * comment above every prompt rule, and such a comment quoting the mark is not a defect.
	 */
	@Test
	public void thePromptsTriggerTokenIsTheSerializersConstantAndNotACopy() throws Exception {
		Path source = ModuleSourceRoot.apiRoot().resolve(
				"src/main/java/org/openmrs/module/chartsearchai/api/impl/LlmProvider.java");
		assertTrue(Files.exists(source), "precondition: LlmProvider.java must be readable at " + source);
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

		// The slice is cut from the COMMENT-STRIPPED file, not from the raw source, and that is a
		// correction rather than a convenience: the terminator scanned for below is a
		// quote-semicolon, and this file's own convention is a paragraph of prose above every prompt
		// rule, so a comment writing `the word "ended"; ...` ends the slice tens of lines early. It
		// fails CLOSED when it happens (the positive half stops finding the dotted name and this case
		// goes red — observed, on the comment added for the fabricated-stop-date cost), so nothing
		// was ever admitted by it; what it cost was a build reddened by a semicolon in prose. Cutting
		// after stripping removes the coupling entirely.
		String normalisedFile = stripCommentsAndJoinLiterals(text);
		int start = normalisedFile.indexOf("static final String DEFAULT_SYSTEM_PROMPT");
		assertTrue(start > 0, "precondition: the prompt constant's declaration must be findable");
		// The initializer ends at its terminating quote-semicolon. Found by scanning rather than by
		// anchoring on whatever member happens to follow, which is how the first version of this case
		// was FAIL-OPEN: it ended the slice at the next "\n\tstatic ", 125 lines past the constant, so
		// a hardcoded copy in the prompt passed as long as the constant's name appeared anywhere in
		// those lines — demonstrated by mutation, green. An escaped quote inside the prompt text is
		// skipped, because the prompt really does contain \" sequences.
		int end = start;
		do {
			end = normalisedFile.indexOf("\";", end + 1);
		} while (end > 0 && normalisedFile.charAt(end - 1) == '\\');
		assertTrue(end > start, "precondition: the prompt constant's terminating quote-semicolon must "
				+ "be findable, or the slice below silently widens to the rest of the file");
		String normalisedInitializer = normalisedFile.substring(start, end + 2);
		assertFalse(normalisedInitializer.contains("@Autowired"),
				"precondition: the slice must stop at the constant, not run on into the rest of the "
						+ "class — that over-wide window is what made this guard fail open. The canary is "
						+ "the FIRST thing after the constant rather than a distant one, because a canary "
						+ "further down leaves a window of lines a widening could take silently");

		// FIVE shapes have defeated a version of this case, closed by three mechanisms. Every one was
		// found by MUTATION rather than by reasoning about the regex, so add the mutation before
		// trusting a sixth to be impossible.
		//
		// The slice bound above closes the first: a window running past the constant.
		//
		// Normalisation closes three, and the third of them is why it is applied to BOTH assertions
		// rather than only to the negative one. A hardcoded copy can be split across the file's own
		// line-wrap — "... not in " + "force" — which a contiguous search cannot see; the split can be
		// held apart by a BLOCK comment, which survives stripping line comments alone; and a comment
		// can carry the constant's dotted name, which satisfies the positive half while the prompt
		// hardcodes the text beside it.
		//
		// The WINDOW of the negative half closes the fifth: a copy held in a sibling private static
		// final declared beside the prompt constant, outside any slice of the initializer, used from
		// the clause. That one and the comment above are a PAIR, which is the part worth keeping —
		// each alone is caught (a copy inside the prompt is seen by the negative half wherever the
		// positive half was satisfied; a sibling copy leaves no dotted name for the positive half to
		// find), and together they passed green: a hand-kept constant plus the "keep in sync with X"
		// comment people write when they make one. So the negative half reads the whole FILE and the
		// positive half reads the comment-stripped initializer. Both were re-demonstrated by mutation
		// on this head: the pair above reddens the negative half, and a sibling constant assigned
		// PatientChartSerializer.INACTIVE_ORDER_LABEL with the dotted name left only in a comment
		// reddens the positive half. That second arrangement TRACKS the constant and is harmless —
		// say so rather than claiming it as a caught defect. What it demonstrates is only that the
		// positive half now reads code and not prose; the price is that it insists the clause be
		// composed at THIS site rather than one indirection away.
		assertFalse(normalisedFile.contains(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"LlmProvider must not carry the mark's text as a literal ANYWHERE — not in the prompt "
						+ "and not in a sibling constant the prompt uses, however it is split or "
						+ "commented around. It reads identically to a composed clause today and stops "
						+ "tracking the constant the moment the mark is renamed, which is the whole "
						+ "failure this case exists for");

		assertTrue(normalisedInitializer.contains("PatientChartSerializer.INACTIVE_ORDER_LABEL"),
				"the prompt's order-status clause must be BUILT from "
						+ "PatientChartSerializer.INACTIVE_ORDER_LABEL in CODE rather than carrying a "
						+ "copy of its text with the constant named in a comment beside it: javac inlines "
						+ "the constant, so a copy is invisible to every behavioural assertion in this "
						+ "file and would go on teaching the old token after the mark was renamed");
	}

	/**
	 * Java source with both comment forms removed and adjacent string literals joined, so a copy of
	 * the mark cannot hide in a comment, behind a line-wrap, or behind a block comment holding a
	 * line-wrap apart.
	 *
	 * <p>Applied ONCE, to the whole file, and both windows are cut from its result — the initializer
	 * slice included. Two expressions, or a slice cut from the raw source and normalised afterwards,
	 * would let the windows disagree about what they can see, which is the class of defect this whole
	 * case is about; cutting after stripping also decouples the slice's quote-semicolon terminator
	 * from prose written above the constant. Not exact, and the inexactness has a direction:
	 * {@code //} inside a string literal truncates the rest of that line, which removes text rather
	 * than adding it — so it costs RECALL (a copy sharing a line with a URL literal, after it, would
	 * be missed) and cannot manufacture one. Do not read that as a proof of soundness; it is an
	 * argument about one substitution, and the way to check a sixth shape is still to write it.
	 */
	private static String stripCommentsAndJoinLiterals(String javaSource) {
		return javaSource
				.replaceAll("(?s)/\\*.*?\\*/", "")
				.replaceAll("(?m)//.*$", "")
				.replaceAll("\"\\s*\\+\\s*\"", "");
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
	 * <strong>The CONVERSE direction, which nothing in this suite asserted until it was asked for.</strong>
	 * Both cases above drive the direction the clause is supposed to fire in. This one drives the
	 * direction it must NOT: a chart of one LIVE prescription, marked
	 * {@code ". Order status: in force"}, asked the currency-shaped question — the answer must name
	 * the drug and must attach no ending to it.
	 *
	 * <p><strong>Why this direction is the dangerous one.</strong> It is what got ADR Decision 45's
	 * clause REVERTED: a currency instruction that reads well on an ended order made the model report
	 * live prescriptions as not being taken, and issue #317's own wording A/B then found four
	 * candidate mark labels each driving <em>"No active medications are recorded"</em> for a patient
	 * with two live prescriptions, 3/3 on every one of them. The mark is also deliberately SILENT
	 * wherever the module cannot say — {@code SerializedRecord.getOrderActive()} owns that list and
	 * it is not restated here — and one entry on it is a failed order read, which strips the mark
	 * from every record of a chart at once, so a clause reading an absent or positive mark as licence
	 * to report an ending would report a whole chart as stopped. That is the fold, and this case is
	 * where it would show.
	 *
	 * <p><strong>What it pins and what it does not.</strong> It pins the property on the arrangement
	 * where the model has to classify from the mark alone: ONE record, live, currency-shaped
	 * question. It does NOT pin the mixed chart, and cannot with this oracle — the fixture's live and
	 * lapsed rows are deliberately the same drug (Triomune-30, so patient 2's chart is the renewal
	 * shape), so an answer correctly reporting the lapsed one as ended is textually indistinguishable
	 * from one wrongly reporting the live one. A review round measured the mixed direction by hand
	 * instead — 12 candidate-arm samples over three mixed charts, no live drug reported as not in
	 * force — and {@link #anAnswerOverAMixedChartStillNamesTheLiveDrug} carries the half of it an
	 * oracle can express.
	 *
	 * <p><strong>NO MUTATION TRIED MOVES THIS CASE, and that is stated here rather than left for the
	 * next reader to discover.</strong> Four were run against the live endpoint on this head, every
	 * one 3/3 GREEN: composing the clause's antecedent from {@code ACTIVE_ORDER_LABEL} instead (so
	 * the LIVE record satisfies it and the clause asks for an ending on it); dropping the antecedent
	 * altogether (<em>"A drug-order record is a record of an order that has ended. Whenever your
	 * answer names a drug from such a record, say ... no longer in force."</em>); the same two
	 * against {@link #anAnswerOverAMixedChartStillNamesTheLiveDrug}; and renaming BOTH marks to issue
	 * #317's rejected {@code ". Order status: active"} / {@code ". Order status: not active"} pair,
	 * the wording that produced the fold on the standalone. On these fixture charts the model reads
	 * the record's own mark and declines the instruction. So this is a CANARY over a property that
	 * currently holds robustly, not a guard with demonstrated sensitivity, and it must not be
	 * described as one. What IS demonstrated is that the oracle is reachable: pointing the mixed case
	 * at a question no drug order can answer reddens it on sample 1
	 * (<em>"No weight measurements are recorded."</em>). Both folds this direction exists for were
	 * seen on the standalone's real charts, which are far larger and noisier than these two rows; an
	 * attempt to make the case discriminate should move the ARRANGEMENT, never loosen the oracle.
	 *
	 * <p>Opt-in like the two above, and skipped without an endpoint.
	 */
	@Test
	public void anAnswerOverAChartOfOneLiveOrderReportsItAsCurrent() throws Exception {
		DrugOrder order = (DrugOrder) Context.getOrderService().getOrder(LIVE_ORDER_ID);
		String drugName = drugNameOf(order);
		List<String> answers = answersOverOneOrder(2, LIVE_ORDER_ID, true, TAKING_QUESTION, "live");
		for (int sample = 1; sample <= answers.size(); sample++) {
			String answer = answers.get(sample - 1);
			String lower = answer.toLowerCase(Locale.ROOT);
			assertTrue(lower.contains(drugName.toLowerCase(Locale.ROOT)),
					"live sample " + sample + ": the chart holds ONE prescription and it is in force, "
							+ "so an answer to \"" + TAKING_QUESTION + "\" that does not name it has "
							+ "dropped a live drug — the fold ADR Decision 45's clause was reverted "
							+ "over. Was: " + answer);
			assertFalse(reportsAnEndedOrder(lower),
					"live sample " + sample + ": the answer attaches an ending to " + drugName
							+ ", whose record is marked \"" + PatientChartSerializer.ACTIVE_ORDER_LABEL
							+ "\". The #315 clause has ONE branch and this record does not satisfy its "
							+ "antecedent; reporting a live prescription as stopped is the same clinical "
							+ "error as #315 with its sign reversed. Was: " + answer);
		}
	}

	/**
	 * <strong>The CONVERSE direction on the arrangement where the fold actually reproduced</strong> —
	 * a MIXED chart: one ended prescription (Triomune-30, marked not in force) beside one live
	 * prescription of a different drug (Aspirin, marked in force), asked the currency-shaped
	 * question. The answer must still name the live drug.
	 *
	 * <p>This is the shape both reverted attempts folded on, and both folded the same way: the live
	 * drug LEFT the answer. ADR Decision 45's clause answered <em>"the patient is currently taking
	 * Simvastatin"</em> about a lapsed drug on a chart whose two live drugs it had dropped; issue
	 * #317's wording A/B found four candidate mark labels each answering <em>"No active medications
	 * are recorded"</em> for a patient with two live prescriptions, 3/3 on every one. Neither fold
	 * needs the answer to say anything false ABOUT the live drug — it simply stops mentioning it — so
	 * the assertion is that the live drug is named, and there is deliberately no attempt to check
	 * what is said about it. On a two-drug answer that would need sentence splitting, and
	 * <em>"The patient is taking Aspirin [2]; Triomune-30 was stopped [1]"</em> is one clause away
	 * from being read as an ending attached to Aspirin.
	 *
	 * <p>The ended row is asserted to be marked too, so this is not the single-order case with an
	 * extra document: the clause has something to fire on, and the question is whether it stays
	 * where it belongs.
	 *
	 * <p><strong>Sensitivity: read
	 * {@link #anAnswerOverAChartOfOneLiveOrderReportsItAsCurrent}'s javadoc.</strong> The same four
	 * mutations leave this case green too, the mark rename among them. Its oracle IS reachable —
	 * asked a question no drug order can answer, it reddens on sample 1 — so what is unproven is this
	 * fixture's ability to reproduce the fold, not the assertion.
	 */
	@Test
	public void anAnswerOverAMixedChartStillNamesTheLiveDrug() throws Exception {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint),
				"Skipping: LLM endpoint not reachable at " + endpoint);

		Patient patient = Context.getPatientService().getPatient(2);
		DrugOrder ended = (DrugOrder) Context.getOrderService().getOrder(STOPPED_ORDER_ID);
		DrugOrder live = (DrugOrder) Context.getOrderService().getOrder(LIVE_ORDER_OF_ANOTHER_DRUG_ID);
		assertFalse(ended.isActive(), "precondition [mixed]: order " + STOPPED_ORDER_ID
				+ " must be inactive, or the clause has nothing to fire on");
		assertTrue(live.isActive(), "precondition [mixed]: order " + LIVE_ORDER_OF_ANOTHER_DRUG_ID
				+ " must be active, or this case is not the converse of anything");
		String liveDrug = drugNameOf(live);
		assertFalse(liveDrug.equalsIgnoreCase(drugNameOf(ended)), "precondition [mixed]: the two "
				+ "orders must name DIFFERENT drugs, or naming one is indistinguishable from naming "
				+ "the other");

		DrugOrderRecordSerializer serializer = new DrugOrderRecordSerializer();
		queryStore.stubChart = new ArrayList<QueryDocument>(
				Arrays.asList(serializer.serialize(ended), serializer.serialize(live)));
		PatientChart chart = builder.build(patient, TAKING_QUESTION);
		assertTrue(chart.getText().contains(PatientChartSerializer.INACTIVE_ORDER_LABEL)
				&& chart.getText().contains(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"precondition [mixed]: the chart must carry BOTH marks. Chart was: " + chart.getText());

		for (int sample = 1; sample <= SAMPLES; sample++) {
			decoy(endpoint, chart);
			String raw = LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
					LlmProvider.buildUserMessage(chart.getText(), TAKING_QUESTION), MAX_TOKENS);
			String answer = LlmProvider.extractResponse(raw).getAnswer();
			log.info("[mixed sample {}] {}", sample, answer);
			assertNotNull(answer, "mixed sample " + sample + ": no answer was produced");
			assertTrue(answer.toLowerCase(Locale.ROOT).contains(liveDrug.toLowerCase(Locale.ROOT)),
					"mixed sample " + sample + ": the answer to \"" + TAKING_QUESTION + "\" drops "
							+ liveDrug + ", whose order is in force, from a chart where the other "
							+ "record is marked not in force. That is the fold ADR Decision 45's clause "
							+ "was reverted over and the one issue #317's four rejected mark labels "
							+ "each produced. Was: " + answer);
		}
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
		DrugOrder order = (DrugOrder) Context.getOrderService().getOrder(orderId);
		String drugName = drugNameOf(order);
		List<String> answers = answersOverOneOrder(patientId, orderId, false, PRESCRIBED_QUESTION, label);
		for (int sample = 1; sample <= answers.size(); sample++) {
			String answer = answers.get(sample - 1);
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
	 * Builds the real chart for one patient over ONE drug order, asserts the mark the module is
	 * expected to render is actually in it, and returns {@link #SAMPLES} answers to {@code question}
	 * with a decoy completion between each.
	 *
	 * <p>Shared by the two directions rather than copied, because the arrangement is the same one
	 * seen from both sides and a second copy is where the two would drift on what counts as a
	 * precondition. {@code expectInForce} selects which mark the chart must carry AND asserts
	 * {@code OrderService}'s own reading of the row agrees, so a fixture edit that flipped an order's
	 * currency reddens the precondition rather than quietly re-pointing the case.
	 */
	private List<String> answersOverOneOrder(int patientId, int orderId, boolean expectInForce,
			String question, String label) throws Exception {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint),
				"Skipping: LLM endpoint not reachable at " + endpoint);

		Patient patient = Context.getPatientService().getPatient(patientId);
		DrugOrder order = (DrugOrder) Context.getOrderService().getOrder(orderId);
		assertEquals(expectInForce, order.isActive(),
				"precondition [" + label + "]: OrderService's own reading of order " + orderId
						+ " must be " + (expectInForce ? "active" : "inactive")
						+ ", or this case proves nothing");

		queryStore.stubChart = new ArrayList<QueryDocument>(
				Arrays.asList(new DrugOrderRecordSerializer().serialize(order)));
		PatientChart chart = builder.build(patient, question);
		String expectedMark = expectInForce ? PatientChartSerializer.ACTIVE_ORDER_LABEL
				: PatientChartSerializer.INACTIVE_ORDER_LABEL;
		assertTrue(chart.getText().contains(expectedMark),
				"precondition [" + label + "]: the chart the model reads must carry \"" + expectedMark
						+ "\", or this case is testing the prompt against evidence that is not there. "
						+ "Chart was: " + chart.getText());

		List<String> answers = new ArrayList<String>();
		for (int sample = 1; sample <= SAMPLES; sample++) {
			decoy(endpoint, chart);
			String raw = LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
					LlmProvider.buildUserMessage(chart.getText(), question), MAX_TOKENS);
			String answer = LlmProvider.extractResponse(raw).getAnswer();
			log.info("[{} sample {}] {}", label, sample, answer);
			assertNotNull(answer, label + " sample " + sample + ": no answer was produced");
			answers.add(answer);
		}
		return answers;
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
