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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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
 * The knowledge base is the real bundled DDInter sample parsed by the real source; its 16 drugs are
 * exactly the 16 the question below names, and they carry <b>72</b> above-floor pairs (23 Major,
 * 40 Moderate, 9 Minor) — enough to outrun every cap tested here.
 */
public class PairChipCapContextTest extends BaseModuleContextSensitiveTest {

	/** The 16-drug polypharmacy question, live-measured at 72 above-floor pairs on the full KB and on
	 *  the bundled sample alike — the shape a cap exists for. */
	private static final String POLYPHARMACY_QUESTION = "Reviewing polypharmacy: lisinopril, metformin,"
			+ " methotrexate, omeprazole, sertraline, simvastatin, spironolactone, tramadol, warfarin,"
			+ " aspirin, ciprofloxacin, clarithromycin, digoxin, fluconazole, amiodarone and ibuprofen"
			+ " — any interactions?";

	private static final String SCREENING_QUESTION =
			"Are there any drug interactions with her current medications?";

	/** Above-floor pairs among the sample's 16 drugs — the candidate count every cap here cuts. */
	private static final int CANDIDATE_PAIRS = 72;

	/** How many of those the sample rates Major, i.e. what a cap of 25 or more shows in full. */
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
		return validator.validate("", SCREENING_QUESTION, DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin", "Warfarin", "Ciprofloxacin", "Clarithromycin",
						"Fluconazole", "Amiodarone"),
				DrugReferenceTestSupport.set("C10AA01", "B01AA03", "J01MA02", "J01FA09", "J02AC01",
						"C01BD01"),
				null, null));
	}

	private static List<String> details(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			out.add(warning.getDetail());
		}
		return out;
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
						+ details(lowered));
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
		List<String> full = details(questionPairChips());
		assertEquals(CANDIDATE_PAIRS, full.size(), "precondition: the uncut list is every candidate");

		for (int cap : new int[] { 1, 3, 10, 25, 71 }) {
			configureCap(String.valueOf(cap));
			assertEquals(full.subList(0, cap), details(questionPairChips()),
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
				"and keep the most severe pairs: " + details(capped));

		configureCap("15");
		assertEquals(15, screeningChips().size(),
				"and a cap at the candidate count must show every screened pair");
	}

	@Test
	public void theQuestionPairWarnNamesTheWithheldPairsAndTheConfiguredCap() {
		// The withheld pairs exist only in this log line — chips have no per-question container to show
		// "3 of 72" in — so it is the only thing standing between a capped list and a clinician reading
		// it as complete. It must report the CAP THAT ACTUALLY CUT, not the compiled-in default.
		configureCap("3");
		List<String> logged = capturedWarnings(new Runnable() {

			@Override
			public void run() {
				questionPairChips();
			}
		});

		String line = firstContaining(logged, "question-named drug pairs shown");
		assertTrue(line.contains("3 of " + CANDIDATE_PAIRS) && line.contains("(cap 3)"),
				"the WARN must state the shown count, the candidate count and the configured cap: " + line);
		assertTrue(line.contains("Major"),
				"and must name the withheld pairs' ratings, so a withheld Major is recoverable: " + line);
	}

	@Test
	public void theScreeningWarnNamesTheWithheldPairsAtTheConfiguredCap() {
		configureCap("3");
		List<String> logged = capturedWarnings(new Runnable() {

			@Override
			public void run() {
				screeningChips();
			}
		});

		String line = firstContaining(logged, "Interaction screening across");
		assertTrue(line.contains("found 15 pair(s)") && line.contains("reporting the 3 most severe"),
				"the screening WARN must state the candidate count and the configured cap: " + line);
		assertTrue(line.contains("WITHHOLDING 12") && line.contains(" x ") && line.contains("(Major)"),
				"and must name every withheld pair with its rating: " + line);
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

	/**
	 * Runs {@code work} with the validator's own logger captured, and returns the WARN messages it
	 * emitted — the real log line the real arm writes, since a log-only signal cannot be asserted any
	 * other way and asserting it through a helper method would test the helper, not the arm.
	 */
	private static List<String> capturedWarnings(Runnable work) {
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(DrugSafetyValidator.class);
		// The EFFECTIVE level, which is what there is to restore: setLevel gives this logger name a
		// configuration of its own, so afterwards it no longer inherits later level changes from its
		// parents. Test-JVM only, and nothing here retunes chartsearchai log levels.
		Level previous = logger.getLevel();
		// Level first, so the appender attaches to a config of this logger's own rather than to root's.
		Configurator.setLevel(logger.getName(), Level.WARN);
		CapturingAppender appender = new CapturingAppender();
		appender.start();
		logger.addAppender(appender);
		try {
			work.run();
			// Copied under the list's own monitor: log4j may deliver from another thread, and
			// synchronizedList guards its mutators, not an iteration over it.
			synchronized (appender.messages) {
				return new ArrayList<String>(appender.messages);
			}
		}
		finally {
			logger.removeAppender(appender);
			appender.stop();
			Configurator.setLevel(logger.getName(), previous);
		}
	}

	/** Collects formatted log messages; the minimum an appender can be. */
	private static final class CapturingAppender extends AbstractAppender {

		final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

		CapturingAppender() {
			super("chartsearchai-pair-cap-capture", (Filter) null, (Layout<? extends Serializable>) null,
					true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}
}
