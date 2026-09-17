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

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What the question-pair arm's rule reading COSTS, as a count per pass (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/447">#447</a>).
 *
 * <p>The arm asked "which of subject's above-floor rules name {@code other}?" by scanning subject's
 * whole interaction list, once per ORDERED pair — twice per unordered pair from
 * {@code collectQuestionPairInteraction} and again from {@code pairKeyNames} — so its cost was
 * quadratic in a number the QUESTION chooses, times the rules on each row, neither bounded by
 * anything but the controller's 1000-character question cap. Measured through the real
 * {@code validate} over the shipped knowledge base on a chart with no active orders, single pass:
 * a question resolving 2 rows cost 8 ms and 4,872 rule reads, one resolving 95 rows cost 1,895 ms
 * and 6,575,839, and one resolving 195 rows cost 7,109 ms — and {@code validate} runs twice per
 * request, the first pass outside the serialised engine lock. The walk count at 95 was 9,142
 * against the 8,930 the pair loop alone predicts, which is what says the scan is where the time is
 * rather than anything the arm does per pair.
 *
 * <p>So this is a COUNT and not a timing — the instrument shape issue #256 already uses for the
 * co-medication resolution ({@code CoMedicationResolutionPerPassTest}), because a wall-clock
 * assertion on a build machine measures the machine. Each entry's interaction list is wrapped,
 * through the public {@link DrugReference#setInteractions}, in a delegate holding the SAME elements
 * in the SAME order and counting the times production asks it for an iterator; the real
 * {@link DrugSafetyValidator#validate} then runs over real parsed data.
 *
 * <p><b>Read the counts as INCREMENTS, never as totals.</b> Other arms of the same pass read the
 * same lists — {@code bestRulePerPartner} walks every in-play row's rules — so a total is not this
 * arm's, while what each ADDITIONAL drug in the question costs is.
 */
public class QuestionPairRuleScanPerPassTest {

	/** Excerpt drugs whose own name resolves exactly one row, asserted per step below. */
	private static final List<String> EXCERPT_DRUGS = Arrays.asList("warfarin", "simvastatin",
			"clarithromycin", "amiodarone", "digoxin", "fluconazole", "sertraline", "tramadol");

	/**
	 * Every chip the eight-drug excerpt question raises, in order, as {@code type | severity | lead} —
	 * the lead being the detail up to its em dash, which is the half naming the two drugs. The
	 * mechanism prose is deliberately not pinned: it is the dataset's and another case's business.
	 *
	 * <p>Here because the walk counts above are a COST assertion, and a cost assertion is satisfied by
	 * an arm that screens less. Ten is {@code maxPairChips}, so this also says the screen is still
	 * reaching its cap at this drug count rather than running out of pairs.
	 */
	private static final List<String> EIGHT_DRUG_CHIPS = Arrays.asList(
			"interaction | Major | Sertraline interacts with Tramadol, also named in the question",
			"interaction | Major | Sertraline interacts with Amiodarone, also named in the question",
			"interaction | Major | Simvastatin interacts with Clarithromycin, also named in the question",
			"interaction | Major | Simvastatin interacts with Fluconazole, also named in the question",
			"interaction | Major | Simvastatin interacts with Amiodarone, also named in the question",
			"interaction | Major | Tramadol interacts with Amiodarone, also named in the question",
			"interaction | Major | Warfarin interacts with Clarithromycin, also named in the question",
			"interaction | Major | Warfarin interacts with Fluconazole, also named in the question",
			"interaction | Major | Warfarin interacts with Amiodarone, also named in the question",
			"interaction | Major | Clarithromycin interacts with Digoxin, also named in the question");

	/** Drugs no rule of any other relates, so {@code pairKeyNames}' break never fires — the shape the
	 *  excerpt cannot express, its 120 links being all 120 pairs its 16 drugs admit. */
	private static final String UNRELATED_FIXTURE = "chartsearchai-test/drug-reference-unrelated-pairs.json";

	private static final List<String> UNRELATED_DRUGS = Arrays.asList("alfazine", "betazine",
			"gammazine", "deltazine", "epsilzine", "zetazine", "iotazine", "kappazine");

	/** Counts the times production asks a rule list for an iterator. Same elements, same order. */
	private static final class CountingRules extends AbstractList<DrugReference.Interaction> {

		private static int walks;

		private final List<DrugReference.Interaction> delegate;

		CountingRules(List<DrugReference.Interaction> delegate) {
			this.delegate = delegate;
		}

		@Override
		public DrugReference.Interaction get(int index) {
			return delegate.get(index);
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public Iterator<DrugReference.Interaction> iterator() {
			walks++;
			return super.iterator();
		}
	}

	private static List<DrugReference> counting(List<DrugReference> entries) {
		for (DrugReference entry : entries) {
			entry.setInteractions(new CountingRules(
					new ArrayList<DrugReference.Interaction>(entry.getInteractions())));
		}
		return entries;
	}

	private static String questionNaming(List<String> drugs, int count) {
		StringBuilder question = new StringBuilder("Can I give her ");
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				question.append(" and ");
			}
			question.append(drugs.get(i));
		}
		return question.append("?").toString();
	}

	/**
	 * @return the rule-list walks one {@code validate} pass spends at each drug count from 2 up to
	 *         {@code drugs.size()}, indexed from 0 for the two-drug pass. Asserts on the way that each
	 *         question resolves exactly as many reference rows as it names words, so a step that
	 *         resolved a route family cannot be read as a step in this arm's own cost.
	 */
	private static List<Integer> walksByDrugCount(List<DrugReference> entries, List<String> drugs) {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(counting(entries));
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext noOrders = DrugReferenceTestSupport.ctx(60, null, null, null, null, null);

		List<Integer> walks = new ArrayList<Integer>();
		for (int count = 2; count <= drugs.size(); count++) {
			String question = questionNaming(drugs, count);
			assertEquals(count, service.findImpliedByQuery(question).size(),
				"the question naming " + count + " drugs must resolve exactly that many reference rows,"
						+ " or its step is about a route family rather than about this arm's cost: "
						+ question);
			CountingRules.walks = 0;
			validator.validate("", question, noOrders);
			walks.add(CountingRules.walks);
		}
		return walks;
	}

	/**
	 * @param walks the answer of {@link #walksByDrugCount}
	 */
	private static void assertEachAddedDrugCostsTheSame(List<Integer> walks, String shape) {
		int first = walks.get(1) - walks.get(0);
		for (int i = 2; i < walks.size(); i++) {
			int increment = walks.get(i) - walks.get(i - 1);
			assertEquals(first, increment,
				"naming a " + (i + 2) + "th drug " + shape + " cost " + increment + " rule-list walks"
						+ " where naming a 3rd cost " + first + ", so the arm's rule reading still grows"
						+ " with the drugs the question names (issue #447). Walks by drug count from 2: "
						+ walks + ". Invert the rules once per pass; do not scan a subject's list per"
						+ " pair.");
		}
	}

	@Test
	public void eachDrugAQuestionNamesCostsTheSameRuleReadingHoweverManyItAlreadyNamed() {
		List<Integer> walks = walksByDrugCount(DrugReferenceTestSupport.ddinterEntries(), EXCERPT_DRUGS);

		assertTrue(walks.get(walks.size() - 1) > walks.get(0),
			"the added drugs must reach the arm at all, or the invariant below is vacuous: " + walks);
		assertEachAddedDrugCostsTheSame(walks, "the excerpt relates to the others");
	}

	/**
	 * And that the arm still reports what it reported. The counts above say each added drug costs the
	 * same rule reading; an arm that screened FEWER PAIRS would satisfy that too, and would satisfy it
	 * most convincingly at the drug count where the cost used to be worst. So the chips the same
	 * question raises are pinned as text.
	 *
	 * <p>Not a duplicate of {@code AboveFloorRuleJoinAgreementTest}, which pins the JOIN's answer for
	 * every ordered pair: this one runs the whole arm and reads what a clinician is shown, including
	 * the grouping, the chart-precedence cede, the severity ordering and the cap.
	 */
	@Test
	public void theEightDrugQuestionStillRaisesTheChipsItRaised() {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.ddinterEntries());
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);

		List<String> leads = new ArrayList<String>();
		for (SafetyWarning warning : validator.validate("",
			questionNaming(EXCERPT_DRUGS, EXCERPT_DRUGS.size()),
			DrugReferenceTestSupport.ctx(60, null, null, null, null, null))) {
			String detail = warning.getDetail();
			int emDash = detail.indexOf(" \u2014 ");
			leads.add(warning.getType() + " | " + warning.getSeverity() + " | "
					+ (emDash < 0 ? detail : detail.substring(0, emDash)));
		}

		assertEquals(EIGHT_DRUG_CHIPS, leads,
			"the question-pair screen no longer reports what it reported over this dataset; a walk count"
					+ " that stopped growing because the arm screens FEWER PAIRS would pass the cases"
					+ " above and fail here (issue #447)");
	}

	/**
	 * The same invariant where NO rule of any named drug relates another. {@code pairKeyNames} breaks
	 * out of its inner loop at the first {@code other} whose rules name the drug it is naming, so on
	 * the excerpt — whose 16 drugs carry all 120 pairs they admit — that break always fires on the
	 * first candidate and the arm's worst shape is unreachable. Here nothing names anything, so the
	 * loop runs to the end of every list.
	 */
	@Test
	public void aQuestionNamingDrugsThatRelateNothingCostsTheSamePerDrugToo() throws IOException {
		List<Integer> walks = walksByDrugCount(
				DrugReferenceTestSupport.fixtureEntries(UNRELATED_FIXTURE), UNRELATED_DRUGS);

		assertTrue(walks.get(walks.size() - 1) > walks.get(0),
			"the added drugs must reach the arm at all, or the invariant below is vacuous: " + walks);
		assertEachAddedDrugCostsTheSame(walks, "no rule of the fixture relates");
	}

	/**
	 * And that no future arm re-introduces the scan. The counts above are what the arms do over the
	 * arrangements these cases build; a NEW read of an entry's rule list inside one of them — a
	 * condition, a tie-break, a second pass over the pair — would reinstate the per-pair walk in a
	 * shape no fixture here exercises, which is the residue ADR Decision 54 records for the sibling
	 * invariant it added at issue #256.
	 *
	 * <p>Scoped to each arm's own BODY and not to a name at class scope: {@code DrugSafetyValidator}
	 * reads {@link DrugReference#getInteractions} legitimately elsewhere — {@code bestRulePerPartner}
	 * walks every in-play row once, which is linear and is not this defect — so a class-scoped needle
	 * would forbid something correct and pass something wrong. {@link SourceScan} blanks comments and
	 * string literals and hard-fails on a declaration it cannot locate uniquely, so this cannot
	 * quietly start forbidding nothing.
	 */
	@Test
	public void noArmOfTheQuestionPairScreenReadsARuleListOfItsOwn() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "DrugSafetyValidator.java");
		List<String> arms = Arrays.asList(
			"private PairChipExtent addQuestionPairInteractions(List<SafetyWarning> warnings,",
			"private void collectQuestionPairInteraction(Map<List<String>, PairFinding> candidates,",
			"private static Map<DrugReference, String> pairKeyNames(List<DrugReference> drugs,");

		for (String arm : arms) {
			SourceScan.Region body = scan.body(arm);
			for (int at : scan.literalOffsets("getInteractions")) {
				assertTrue(!body.contains(at), "\"" + arm.trim() + "\" reads an entry's interaction list"
						+ " directly, at line " + scan.lineOf(at) + ": " + scan.statementAt(at)
						+ ". That is the per-pair scan issue #447 removed — a question resolving 195"
						+ " reference rows cost 7,109 ms of the shared JVM that way against 47 ms"
						+ " through the pass's own AboveFloorRules. Ask that join instead; it reads"
						+ " each list once for the whole pass.");
			}
		}
	}
}
