/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The pair-chip cap as a global property (issue #131): {@code chartsearchai.drugSafety.maxPairChips},
 * read by BOTH pairwise arms — the question's own drugs checked against each other (#114) and the
 * patient's active orders checked against each other (#113).
 *
 * <p>The cap was hardcoded to 10 in two places. Measured live on the 3.7.1 standalone against the
 * FULL DDInter knowledge base (2026-08-05), the question below logged {@code 10 of 72 … (cap 10)} and
 * withheld {@code [Major ×13, Moderate ×40, Minor ×9]} — sixteen medications reviewed, ten Majors
 * shown, thirteen withheld — and the number that decides which is a clinical judgement no module
 * should fix at build time. (Issue #131 reports {@code 10 of 65} for a differently-worded 16-drug
 * question, not in the tree and not reproduced here.) Uncapped is not the alternative: this question
 * produced 72 chips carrying 42,708 characters of injected finding text.
 *
 * <p>Context-sensitive because the point is the GP: the cases write a real global property through the
 * admin service and read it back through the real {@code validate} path, so a cap assertion cannot pass
 * on a hardcoded default (the exception is the absent-row case, whose whole point is to write none).
 * The knowledge base is the real DDInter excerpt parsed by the real source; its 16 drugs are
 * exactly the 16 the question below names, and they carry <b>72</b> above-floor pairs (23 Major,
 * 40 Moderate, 9 Minor) — enough to outrun every cap tested here.
 */
public class PairChipCapContextTest extends BaseModuleContextSensitiveTest {

	/** The 16-drug polypharmacy question, live-measured at 72 above-floor pairs on the full KB and on
	 *  the DDInter excerpt alike — the shape a cap exists for. Shared with the extent cases, which
	 *  assert about the same 72 from the other side; see the constant's own javadoc. */
	private static final String POLYPHARMACY_QUESTION = DrugReferenceTestSupport.POLYPHARMACY_QUESTION;

	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	/** The package both cap WARNs are written from. Captured whole, not just
	 *  {@code DrugSafetyValidator}'s own logger, because a drug name leaking from a neighbour on this
	 *  pass is the same disclosure — the argument {@code FindingPartnerLogDisclosureTest} makes for
	 *  the other half of the answer path. */
	private static final String REFERENCE_PACKAGE = "org.openmrs.module.chartsearchai.reference";

	/** Above-floor pairs among the excerpt's 16 drugs — the candidate count every cap here cuts. */
	private static final int CANDIDATE_PAIRS = 72;

	/** How many of those the excerpt rates Major, i.e. what a cap of 25 or more shows in full. */
	private static final int MAJOR_PAIRS = 23;

	private DrugSafetyValidator validator;

	@BeforeEach
	public void setUp() {
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	/** Writes the GP the way an implementation would. */
	private void configureCap(String value) {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS, value);
	}

	/** The question-pair arm on a patient taking nothing, so only that arm can chip. The empty answer
	 *  is the pre-answer production shape ({@code DrugReferenceInjector.preAnswerFindings}). */
	private List<SafetyWarning> questionPairChips() {
		return validator.validate("", POLYPHARMACY_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));
	}

	/** The screening arm over six active orders interacting 15 ways, 10 of them Major — the same
	 *  arrangement the un-capped screening test uses, so the two cannot drift apart. */
	private List<SafetyWarning> screeningChips() {
		return validator.validate("", SCREENING_QUESTION,
				DrugReferenceTestSupport.screenedSixOrderChart());
	}

	/** Chips whose SOURCE RATING is Major. Matched on the severity segment both arms render ahead of
	 *  the mechanism prose ({@code "— Major."}), not on the bare word: real DDInter notes talk about
	 *  "the risk of major bleeding", and counting those would make a severity assertion pass on prose. */
	private static long majors(List<SafetyWarning> warnings) {
		long count = 0;
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains("— Major.")) {
				count++;
			}
		}
		return count;
	}

	@Test
	public void withNoGpRowTheCapIsTheDocumentedDefault() {
		// Absence means the default, like every other chartsearchai GP — and the default is the number
		// both arms were hardcoded to, so registering the property changes no deployment's behaviour.
		List<SafetyWarning> defaulted = questionPairChips();

		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS, defaulted.size(),
				"with no GP row the cap must be the documented default, was: " + defaulted.size());
		// Precondition, asserted rather than assumed: this question really does outrun that default, so
		// the cases below are measuring a cap and not an empty candidate list.
		configureCap("1000");
		assertEquals(CANDIDATE_PAIRS, questionPairChips().size(),
				"precondition: the question must resolve more above-floor pairs than the default shows");
	}

	@Test
	public void aRaisedCapShowsThePairsTheDefaultWithheld() {
		// The clinical point of the issue: at the default this question shows 10 of 23 Major pairs. An
		// implementation that reviews polypharmacy must be able to see all of them.
		assertEquals(10, majors(questionPairChips()),
				"precondition: the default cap shows only ten of the Major pairs");

		configureCap("25");
		List<SafetyWarning> raised = questionPairChips();

		assertEquals(25, raised.size(), "a raised cap must be honoured, was: " + raised.size());
		assertEquals(MAJOR_PAIRS, majors(raised),
				"and raising it past the Major count must show every Major pair, was: " + majors(raised));
	}

	@Test
	public void aLoweredCapKeepsOnlyTheMostSeverePairs() {
		configureCap("3");
		List<SafetyWarning> lowered = questionPairChips();

		assertEquals(3, lowered.size(), "a lowered cap must be honoured, was: " + lowered.size());
		assertEquals(3, majors(lowered),
				"and what survives a lowered cap must be the most severe, not the dataset's first: "
						+ DrugReferenceTestSupport.details(lowered));
	}

	@Test
	public void aNonPositiveCapFallsBackToTheDefaultRatherThanDisablingTheCap() {
		// The fail-safe direction, and it matters twice over: zero must not mean "no chips" (a silently
		// disabled safety net) and it must not mean "no cap" (an unbounded, question-controlled prompt
		// expansion — the 42,708-character shape). It means "the operator typed something unusable".
		configureCap("0");
		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS,
				questionPairChips().size(), "a zero cap must fall back to the default");

		configureCap("-5");
		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS,
				questionPairChips().size(), "a negative cap must fall back to the default");
	}

	@Test
	public void anUnparseableCapFallsBackToTheDefault() {
		configureCap("ten");
		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS,
				questionPairChips().size(), "an unparseable cap must fall back to the default");
	}

	@Test
	public void theShownSetIsAlwaysThePrefixOfOneSeverityOrderedList() {
		// Stronger than "the retained set is severity-descending", and it is what makes a cap change
		// safe to reason about: whatever the cap, the chips shown are the FIRST n of the same
		// most-severe-first ordering. A future change that sorted after cutting, or capped while
		// collecting, would still be descending at every setting and would still break this.
		configureCap("1000");
		List<String> full = DrugReferenceTestSupport.details(questionPairChips());
		assertEquals(CANDIDATE_PAIRS, full.size(), "precondition: the uncut list is every candidate");

		for (int cap : new int[] { 1, 3, 10, 25, 71 }) {
			configureCap(String.valueOf(cap));
			assertEquals(full.subList(0, cap), DrugReferenceTestSupport.details(questionPairChips()),
					"at cap " + cap + " the shown chips must be the first " + cap + " of the ordered list");
		}
	}

	@Test
	public void theScreeningArmReadsTheSameCap() {
		// Both arms, one property. Their gates are mutually exclusive — this question names no drug the
		// dataset carries, the one above names sixteen — so a question can never be subject to both, and
		// two separately tunable limits for one concept would be arbitrary. Pinned on the arm the other
		// tests do not exercise, because "reads the same GP" is exactly the kind of claim that decays.
		configureCap("3");
		List<SafetyWarning> capped = screeningChips();
		assertEquals(3, capped.size(), "the screening arm must honour the cap too, was: " + capped.size());
		assertEquals(3, majors(capped),
				"and keep the most severe pairs: " + DrugReferenceTestSupport.details(capped));

		configureCap("15");
		assertEquals(15, screeningChips().size(),
				"and a cap at the candidate count must show every screened pair");
	}

	@Test
	public void theQuestionPairWarnRatesTheWithheldPairsAndStatesTheConfiguredCap() {
		// HOW MANY pairs went, and at what ratings, exists only in this log line: since issue #336 the
		// response states the two COUNTS (PairChipExtentContextTest) and never the ratings, so this is
		// still the only place an operator can see how severe what was dropped was. It must report the
		// CAP THAT ACTUALLY CUT, not the compiled-in default.
		//
		// It never named the pairs, in this arm: the list it builds is each withheld candidate's
		// severity. That is why #439 could give the sibling screening arm the same shape — see the cap
		// WARN in addActiveOrderPairInteractions, and ADR Decision 102. The old method name and the old
		// first line of this comment both said "names", against a loop that adds `finding.severity`.
		configureCap("3");
		try (LogCapture capture = LogCapture.on(REFERENCE_PACKAGE, Level.DEBUG)) {
			questionPairChips();

			String line = firstContaining(capture.messagesAt(Level.WARN), "question-named drug pairs shown");
			assertTrue(line.contains("3 of " + CANDIDATE_PAIRS) && line.contains("(cap 3)"),
					"the WARN must state the shown count, the candidate count and the configured cap: " + line);
			assertTrue(line.contains("Major"),
					"and must report the withheld pairs' ratings, so a withheld Major is recoverable: " + line);
		}
	}

	@Test
	public void theScreeningWarnRatesTheWithheldPairsAtTheConfiguredCapAndNamesNoDrug() {
		// A DELIBERATE spec change, issue #439 and ADR Decision 102: this case asserted `" x "` and
		// `"(Major)"`, i.e. that the line named every withheld pair on both sides. Both sides of a
		// screened pair are this patient's own active orders, so that list was her medication list on a
		// line the default log configuration keeps — the disclosure #439 reported one site of. The
		// diagnostic the issue's own criterion asks for survives as the RATINGS, which is what the
		// sibling question-pair arm's cap WARN above has always logged; an operator who needs the pairs
		// themselves raises the cap and re-asks, which puts them on the wire as chips.
		configureCap("3");
		try (LogCapture capture = LogCapture.on(REFERENCE_PACKAGE, Level.DEBUG)) {
			screeningChips();

			String line = firstContaining(capture.messagesAt(Level.WARN), "Interaction screening across");
			assertTrue(line.contains("found 15 pair(s)") && line.contains("reporting the 3 most severe"),
					"the screening WARN must state the candidate count and the configured cap: " + line);
			assertTrue(line.contains("WITHHOLDING 12"),
					"and how many pairs it withheld: " + line);
			// The whole tail, matched as a closed vocabulary rather than by hunting for names: anything
			// the line reports a withheld pair BY other than its rating reddens this, including a name no
			// case here thought to look for.
			int tail = line.indexOf("least severe last: ");
			assertTrue(tail >= 0, "the withheld ratings must be reported: " + line);
			String ratings = line.substring(tail + "least severe last: ".length()).trim();
			assertTrue(ratings.matches("\\[(Major|Moderate|Minor|unrated)(, (Major|Moderate|Minor|unrated))*\\]"),
					"the withheld pairs must be reported as ratings and nothing else, so a withheld Major "
							+ "is recoverable without naming a drug: " + ratings);
			assertTrue(ratings.contains("Major"),
					"and a withheld Major must be recoverable from them: " + ratings);
			// And the negative, over every captured line at EVERY level: none of the six drugs this
			// patient is prescribed. Read from the chart's own list, so it cannot drift from what was
			// screened.
			//
			// describeAll() and not messagesAt(WARN), and the capture is opened at DEBUG, because the
			// alternative #439 explicitly declined was these names at a lower level — ADR Decision 102,
			// "the names at DEBUG, was not taken". A WARN-only capture leaves that alternative
			// implementable with this case green: the reviewer's probe for round 2 of this PR's review
			// put `log.info("Withheld pairs in full: {}", …)` beside the cap WARN, carrying each withheld
			// chip's detail (which names both drugs), and the whole build stayed green. Under this
			// capture that probe reddens exactly here.
			for (String prescribed : DrugReferenceTestSupport.SCREENED_SIX_ORDER_NAMES) {
				for (String captured : capture.describeAll()) {
					assertFalse(captured.toLowerCase(Locale.ROOT).contains(prescribed.toLowerCase(Locale.ROOT)),
							"no captured line may name a drug this patient is prescribed — a screened pair is "
									+ "two of her active orders. Found \"" + prescribed + "\" in: " + captured);
				}
			}
		}
	}

	/** @return the first captured line containing {@code needle}; fails the test when none does. */
	private static String firstContaining(List<String> lines, String needle) {
		for (String line : lines) {
			if (line.contains(needle)) {
				return line;
			}
		}
		throw new AssertionError("no WARN line contained \"" + needle + "\"; captured: " + lines);
	}
}
