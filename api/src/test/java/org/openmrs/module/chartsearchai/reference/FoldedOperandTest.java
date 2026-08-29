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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Each operand of the boundary scan is {@link DrugReference#foldedLower}-folded ONCE, where it is
 * produced, rather than once per comparison (issue #330).
 *
 * <p>{@code containsBoundedToken} folds BOTH of its operands at the innermost comparison, so every
 * caller holding one of them fixed re-derived it once per comparison. Measured through the real
 * {@code DrugSafetyValidator.validate} over the shipped 19 MB knowledge base and a 43-order chart,
 * that was 1,420,480 {@code foldedLower} calls in a one-drug pass and 1,927,548 in a ten-drug one —
 * two per comparison, one per operand, for two values that do not change.
 *
 * <p><b>The two operands have two different levels, and this class pins both.</b> An entry's own
 * ALIAS is fixed when the alias list is set; a scan's RECORDED NAME is fixed for the scan. So the
 * alias is folded in {@code setAliases} and the recorded name in the scan, and neither is folded
 * again below.
 *
 * <p><b>What each case can and cannot see.</b> A timing assertion would be flaky and machine-shaped,
 * so nothing here times anything — the argument this class makes is about WHERE the fold happens.
 * {@link #theRecordedNameIsFoldedOnceForAWholeScanAndNotOncePerEntry} asks it by object IDENTITY,
 * which is the only thing that separates a fold hoisted out of the loop from one written inside it:
 * a count cannot, because both spellings call the folded matcher exactly once per entry.
 * {@link #anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches} is the one shape in which
 * the alias-side fold is load-bearing at all — over every dataset the module ships,
 * {@link DrugReference#foldDiacritics} takes its ASCII fast path and returns the alias unchanged, so
 * a guard comparing a stored folded alias against {@code foldedLower} of the raw one is satisfied
 * element-for-element by the raw list and proves nothing. And the source scan pins the half no
 * behavioural case reaches: {@code PatientClinicalContext.hasActiveDrug} compares through a static
 * matcher with no seam a probe can override, and a memo-shaped regression in {@code setAliases}
 * would be invisible to both.
 */
public class FoldedOperandTest {

	/**
	 * An entry that names nothing in any fixture, so inserting it perturbs no chip, whose only job is
	 * to record WHICH operand instance each scan handed it. An instrument, not a mock: every method
	 * below delegates to {@code super} and the answer the pipeline gets is the real one.
	 */
	private static final class Probe extends DrugReference {

		private final List<DrugReference.FoldedName> named = new ArrayList<DrugReference.FoldedName>();

		private final List<DrugReference.FoldedName> ranked = new ArrayList<DrugReference.FoldedName>();

		Probe(String id) {
			setId(id);
			setName(id);
			setAliases(java.util.Collections.singletonList(id));
		}

		@Override
		boolean matchesDrugName(DrugReference.FoldedName drugName) {
			named.add(drugName);
			return super.matchesDrugName(drugName);
		}

		@Override
		int nameMatchStrength(DrugReference.FoldedName drugName) {
			ranked.add(drugName);
			return super.nameMatchStrength(drugName);
		}
	}

	/**
	 * The recorded name a whole-dataset scan is resolving is folded ONCE for that scan.
	 *
	 * <p>Asked by IDENTITY, across two probes sitting at ADJACENT positions in the dataset, because a
	 * count cannot tell the two implementations apart: {@code fold} hoisted above the loop and
	 * {@code fold(name)} written inside it both call the folded matcher exactly once per entry, and
	 * both leave the unfolded entry point uncalled. What separates them is that the hoisted one hands
	 * every entry the SAME object and the other hands each a fresh one.
	 *
	 * <p>Adjacent, and compared element-wise rather than as sets, because a scan may legitimately stop
	 * early — {@code lookupByToken} returns as soon as an entry claims the display name — and may
	 * therefore reach neither probe. Nothing sits between them, so every scan that reaches one reaches
	 * the other, and the two lists stay aligned however many scans stop short.
	 */
	@Test
	public void theRecordedNameIsFoldedOnceForAWholeScanAndNotOncePerEntry() {
		Probe first = new Probe("zzq-probe-alpha");
		Probe second = new Probe("zzq-probe-beta");
		List<DrugReference> entries = new ArrayList<DrugReference>();
		entries.add(first);
		entries.add(second);
		entries.addAll(DrugReferenceTestSupport.ddinterEntries());
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(entries);

		DrugReferenceTestSupport.validator(service).validate("", "Can I give her warfarin?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirin 81mg", "Ibuprofen 400mg", "Methotrexate 2.5mg"),
						null, null, null));

		assertFalse(first.named.isEmpty(), "the probes must have been reached by a name-driven scan at "
				+ "all, or this case asserts nothing about where the fold happened");
		assertEquals(first.named.size(), second.named.size(), "two adjacent entries must be reached by "
				+ "the same scans; if they are not, the lists below are not comparable");
		assertEquals(first.ranked.size(), second.ranked.size(), "likewise for the ranking scans");
		assertSameOperands(first.named, second.named, "matchesDrugName");
		assertSameOperands(first.ranked, second.ranked, "nameMatchStrength");
	}

	private static void assertSameOperands(List<DrugReference.FoldedName> a,
			List<DrugReference.FoldedName> b, String what) {
		for (int i = 0; i < a.size(); i++) {
			assertSame(a.get(i), b.get(i), "call " + i + " of " + what + " handed two entries of ONE "
					+ "scan two different folded operands, so the recorded name is being folded per "
					+ "entry rather than once for the scan");
		}
	}

	/**
	 * An entry every one of whose names carries an accent is still reached by an unaccented recorded
	 * name — the shape {@code containsBoundedToken}'s javadoc says the reference-side fold exists for,
	 * and the only shape in which storing the RAW alias list where the folded one belongs is visible.
	 *
	 * <p>Over the shipped knowledge base it is not visible: 0 of its 5169 aliases carry a combining
	 * mark, so {@code foldDiacritics} returns its argument and the two lists are equal element for
	 * element. {@code drug-reference-accented-tokens.json} does not reach it either — its entries each
	 * carry an unaccented sibling alias, so the accented one is never the only way in.
	 */
	@Test
	public void anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-accented-alias-only.json"));

		List<SafetyWarning> byOrderName = DrugReferenceTestSupport.validator(service).validate("",
				"What are her current medications?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Ketoprofene 100mg"), null,
						DrugReferenceTestSupport.set("aspirine"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(byOrderName,
				SafetyWarning.TYPE_CONTRAINDICATION, "Kétoprofène"),
				"an order recorded as \"Ketoprofene 100mg\" must reach an entry whose only name is "
						+ "\"kétoprofène\" — the alias is matched in its FOLDED form, and an "
						+ "implementation storing the raw list where the folded one belongs reaches it "
						+ "only for a chart that spells the accent, was: " + byOrderName);

		List<SafetyWarning> byProse = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give her ketoprofene?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("aspirine"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(byProse,
				SafetyWarning.TYPE_CONTRAINDICATION, "Kétoprofène"),
				"and the same entry must be reached from unaccented PROSE, which scans the same aliases "
						+ "under the other boundary rule, was: " + byProse);
	}

	/**
	 * {@code setAliases} is the only writer of either alias list, so the folded one cannot go stale.
	 *
	 * <p>Structural because nothing behavioural can see it, which is the situation ADR Decision 54
	 * records the mechanism for: the folded list is DERIVED in the setter rather than memoised, and a
	 * second assignment somewhere else — a lazy memo, a copy constructor — would leave a folded list
	 * describing aliases the entry no longer has, with every existing case green because the shipped
	 * datasets fold to themselves.
	 */
	@Test
	public void onlyTheAliasSetterWritesEitherAliasList() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "DrugReference.java");
		SourceScan.Region setter = scan.body("public void setAliases(List<String> aliases) {");
		for (Integer at : scan.matches(Pattern.compile("this\\.(folded)?[aA]liases\\s*="))) {
			assertTrue(setter.contains(at), "\"" + scan.statementAt(at) + "\" (line " + scan.lineOf(at)
					+ ") assigns an alias list outside setAliases; the folded list is derived there and "
					+ "nowhere else, so a second writer is how it comes to describe aliases the entry no "
					+ "longer has — invisible to every existing case, because every dataset the module "
					+ "ships folds to itself");
		}
	}

	/**
	 * {@code PatientClinicalContext.hasActiveDrug} folds its token, and the order names it scans,
	 * OUTSIDE its loop.
	 *
	 * <p>Structural because that comparison goes through a static matcher: there is no instance a
	 * probe can subclass, so the identity argument the first case makes is unavailable here. This is
	 * the site with the residue that grows with the number of drugs in play — 3,952 comparisons at one
	 * drug and 108,214 at ten, by the caller-attributed counts in the issue's re-derivation.
	 */
	@Test
	public void hasActiveDrugFoldsNeitherOperandInsideItsLoop() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "PatientClinicalContext.java");
		SourceScan.Region loop = scan.body("for (String folded : foldedActiveDrugNames) {");
		for (Integer at : scan.literalOffsets("foldedLower(")) {
			assertFalse(loop.contains(at), "\"" + scan.statementAt(at) + "\" (line " + scan.lineOf(at)
					+ ") folds inside hasActiveDrug's loop; both of its operands are fixed for the pass, "
					+ "so both are folded above it");
		}
		for (Integer at : scan.literalOffsets("DrugReference.fold(")) {
			assertFalse(loop.contains(at), "\"" + scan.statementAt(at) + "\" (line " + scan.lineOf(at)
					+ ") folds the rule token inside hasActiveDrug's loop, once per order name");
		}
	}
}
