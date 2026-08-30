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
import org.opentest4j.AssertionFailedError;

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

	/** A name no shipped or fixture entry carries, aliased by BOTH probes — see {@code Probe}. */
	private static final String SHARED_ALIAS = "zzq-probe";

	/**
	 * An entry that names nothing in any fixture, so inserting it perturbs no chip, whose only job is
	 * to record WHICH operand instance each scan handed it. An instrument, not a mock: every method
	 * below delegates to {@code super} and the answer the pipeline gets is the real one.
	 */
	private static final class Probe extends DrugReference {

		private final List<DrugReference.FoldedName> named = new ArrayList<DrugReference.FoldedName>();

		private final List<DrugReference.FoldedName> ranked = new ArrayList<DrugReference.FoldedName>();

		private final List<String> prose = new ArrayList<String>();

		private final List<DrugReference.FoldedName> witnessed =
				new ArrayList<DrugReference.FoldedName>();

		Probe(String id) {
			setId(id);
			setName(id);
			// Two aliases: one of its own, and one BOTH probes share. The shared one is what puts the two
			// of them in one candidate set, which is the only way the witness pass and the ranking scans
			// reach them at all — a probe that matches nothing is never in `matched` and never outranks
			// anything, so those scans would visit it and record nothing.
			setAliases(java.util.Arrays.asList(id, SHARED_ALIAS));
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

		@Override
		List<String> aliasesNaming(DrugReference.FoldedName drugName) {
			witnessed.add(drugName);
			return super.aliasesNaming(drugName);
		}

		@Override
		boolean matchesFoldedText(String foldedLowerText) {
			prose.add(foldedLowerText);
			return super.matchesFoldedText(foldedLowerText);
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
	 * <p>Identity discriminates here whatever the name looks like, because {@link DrugReference#fold}
	 * allocates a carrier per call — unlike
	 * {@link #theProseScanFoldsItsTextOnceForTheWholeSweep}, whose operand is a folded {@code String}
	 * and which therefore needs an accented text to discriminate at all. That difference is measured,
	 * not asserted: with the prose fold moved into its loop, the accented question reddens that case
	 * and an ASCII one leaves it green.
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

		// The order and the allergy both spell the shared alias, which is what reaches the RANKING scans
		// (lookupByToken and findImpliedSubstances resolve a recorded allergen) and the WITNESS pass
		// (findImpliedByDrugName runs it only for a name matching two or more entries — which is what
		// the shared alias makes true). Without them only findByDrugName is exercised, and reverting
		// the two nameMatchStrength hoists leaves the whole suite green.
		DrugReferenceTestSupport.validator(service).validate("", "Can I give her warfarin?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirin 81mg", "Ibuprofen 400mg", SHARED_ALIAS + " 5mg"),
						null, DrugReferenceTestSupport.set(SHARED_ALIAS), null));

		assertReached(first.named, second.named, "matchesDrugName");
		assertReached(first.ranked, second.ranked, "nameMatchStrength");
		assertReached(first.witnessed, second.witnessed, "aliasesNaming");
	}

	/** Both probes were reached the same number of times, and every one of those times by the SAME
	 *  operand instance. The non-empty half is what stops {@code 0 == 0} standing in for the property:
	 *  the ranking and witness lists WERE empty in this case's first arrangement, and reverting both
	 *  {@code nameMatchStrength} hoists then left the whole build green. */
	private static void assertReached(List<DrugReference.FoldedName> a,
			List<DrugReference.FoldedName> b, String what) {
		assertFalse(a.isEmpty(), "the probes were never reached by a " + what + " scan, so this case "
				+ "asserts nothing about where that scan folds");
		assertEquals(a.size(), b.size(), "two entries sharing an alias and adjacent in the dataset must "
				+ "be reached by the same " + what + " scans, or the lists are not comparable");
		assertSameOperands(a, b, what);
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
	 * The prose a whole-dataset scan is resolving is folded ONCE for that scan — the same property as
	 * above for the other boundary rule, asked the same way and at {@code findByQuery}.
	 *
	 * <p><b>The question must carry an accent, and that is the case rather than a detail of it.</b>
	 * Identity separates a hoisted fold from one written inside the loop only where folding actually
	 * produces a new string: {@code findByQuery} lower-cases before it folds, and for an ASCII question
	 * {@link DrugReference#foldDiacritics} takes its fast path and returns the argument, so a fold
	 * written per entry would hand every entry that SAME instance and this case would pass on a
	 * defective implementation. An accented question allocates per fold, so it discriminates.
	 */
	@Test
	public void theProseScanFoldsItsTextOnceForTheWholeSweep() {
		Probe first = new Probe("zzq-probe-alpha");
		Probe second = new Probe("zzq-probe-beta");
		List<DrugReference> entries = new ArrayList<DrugReference>();
		entries.add(first);
		entries.add(second);
		entries.addAll(DrugReferenceTestSupport.ddinterEntries());
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(entries);

		DrugReferenceTestSupport.validator(service).validate("",
				"Puis-je lui donner de la warfarine avec de l'aspirine \u00e0 c\u00f4t\u00e9 ?",
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null));

		assertFalse(first.prose.isEmpty(), "the probes must have been reached by a prose scan at all");
		assertEquals(first.prose.size(), second.prose.size(),
				"two adjacent entries must be reached by the same prose scans");
		for (int i = 0; i < first.prose.size(); i++) {
			assertSame(first.prose.get(i), second.prose.get(i), "call " + i + " of matchesFoldedText "
					+ "handed two entries of ONE scan two different folded strings, so the question is "
					+ "being folded per entry rather than once for the scan");
		}
	}

	/**
	 * An entry every one of whose names carries an accent is still reached by an unaccented recorded
	 * name — the shape {@code containsBoundedToken}'s javadoc says the reference-side fold exists for,
	 * and the only shape in which storing the RAW alias list where the folded one belongs is visible.
	 *
	 * <p>Over the shipped knowledge base it is not visible: measured 2026-08-30 through the real
	 * loader, 0 of its 8300 aliases folds to a different String — its one alias above U+007F carries
	 * an EN DASH rather than a combining mark — so the two lists are equal element for element and,
	 * on that dataset, the very same instances. {@code drug-reference-accented-tokens.json} does not reach it either — its entries each
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
	 * A witness is the alias that MATCHED, not whatever sits at its index in the other list.
	 *
	 * <p>The two lists are index-aligned and {@link DrugReference#aliasesIn} /
	 * {@code aliasesNaming} walk the folded one while reading the raw one, so alignment is the change's
	 * central invariant — and nothing pinned it. Measured by a review: making
	 * {@code DrugReference.foldedAll} SKIP nulls instead of carrying them, one line and exactly the
	 * edit its second caller invites (a context's name set has no nulls to carry), left the whole api
	 * suite green while both witness accessors began reporting {@code [null]}. Those witnesses are what
	 * {@code DrugReferenceService.namesSubstanceOf} narrows a candidate set by, so the failure is issue
	 * #209's, fail-silent.
	 *
	 * <p>Through the real {@code JsonDrugReferenceSource} over a fixture that already carries the one
	 * shape that can expose it — a null BEFORE a real alias — rather than through a hand-built entry.
	 */
	@Test
	public void aWitnessIsTheAliasThatMatchedAndNotItsNeighbour() throws IOException {
		DrugReference row = DrugReferenceTestSupport.row(DrugReferenceTestSupport.fixtureEntries(
				"chartsearchai-test/drug-reference-null-interaction-and-alias.json"), "Ibuprofen");
		assertEquals(java.util.Arrays.asList(null, "ibuprofen"), row.getAliases(),
				"precondition: the fixture entry must still carry a null BEFORE a real alias, or nothing "
						+ "below can tell an aligned read from a misaligned one");
		assertEquals(java.util.Collections.singletonList("ibuprofen"),
				row.aliasesNaming(DrugReference.fold("Ibuprofen 400mg")),
				"the recorded-name witness must be the alias that matched");
		assertEquals(java.util.Collections.singletonList("ibuprofen"),
				row.aliasesIn("she takes ibuprofen daily"),
				"and so must the prose witness");
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
		// The receiver is deliberately optional and unpinned. Written as "this.foldedAliases =" the
		// needle was defeated three ways in one review — a bare assignment with no receiver at all, a
		// line wrap between "this" and the dot, and a static helper writing "entry.foldedAliases =" —
		// and each is how a copy, a merge or a lazy repair would most naturally be spelled. What is
		// left out is the two field DECLARATIONS, which are assignments to the same names and are
		// recognised by their own statement text rather than by their position.
		for (Integer at : scan.matches(Pattern.compile(
				"(?s)(?<![\\w.])(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)?(?:aliases|foldedAliases)\\s*=(?!=)"))) {
			if (scan.statementAt(at).startsWith("private List<String>")) {
				continue;
			}
			assertTrue(setter.contains(at), "\"" + scan.statementAt(at) + "\" (line " + scan.lineOf(at)
					+ ") assigns an alias list outside setAliases; the folded list is derived there and "
					+ "nowhere else, so a second writer is how it comes to describe aliases the entry no "
					+ "longer has — invisible to every existing case, because every dataset the module "
					+ "ships folds to itself");
		}
	}

	/**
	 * {@code PatientClinicalContext.hasActiveDrug} compares through the FOLDED matcher and folds
	 * nothing inside its loop.
	 *
	 * <p>Structural because that comparison goes through a static matcher: there is no instance a probe
	 * can subclass, so the identity argument the first case makes is unavailable here. This is the site
	 * whose comparison count grows with the number of drugs in play — 3,952 at one drug and 108,214 at
	 * ten, by the caller-attributed counts in ADR Decision 55.
	 *
	 * <p><b>Stated POSITIVELY, because a list of forbidden spellings has been defeated once for every
	 * time one was written.</b> Its first version forbade the literals {@code foldedLower(} and
	 * {@code fold(}, and a review then reverted the loop body to the UNFOLDED
	 * {@code DrugReference.matchesOrderName(folded, n)} — which restores two folds per comparison and
	 * additionally double-folds the haystack — with the whole build green, because neither literal
	 * appears in that spelling. Requiring the one call that is correct forbids every spelling that is
	 * not, including the ones nobody has thought of; the forbidden list is kept beside it only so a
	 * failure names what went in rather than what went missing.
	 */
	@Test
	public void hasActiveDrugComparesThroughTheFoldedMatcherAndFoldsNothingInItsLoop() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "PatientClinicalContext.java");
		SourceScan.Region loop = scan.body("for (String folded : foldedActiveDrugNames) {");
		assertContains(scan, loop, "DrugReference.matchesFoldedOrderName(", "hasActiveDrug's loop");
		assertForbids(scan, loop, "hasActiveDrug's loop", "matchesOrderName(", "containsWord(",
				"foldedLower(", "foldDiacritics(", "toLowerCase(", "fold(");
	}

	/**
	 * Every scan of an entry's own names reads {@link DrugReference}'s stored FOLDED alias list, and
	 * none of them folds an alias itself.
	 *
	 * <p>This is the half of the change worth most — by ADR Decision 55's own decomposition the alias
	 * side is five sixths of the win — and <b>nothing behavioural can see it</b>, deliberately: the
	 * unfolded primitives fold the raw alias for themselves, so an accessor rewritten to iterate
	 * {@code aliases} through {@code containsWord} or {@code matchesOrderName} returns exactly the same
	 * answers while reinstating ~1.4 million {@code foldedLower} calls a pass. A review did precisely
	 * that to the two hottest accessors and the whole api suite stayed green.
	 * {@link #anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches} cannot reach it either: it
	 * pins that {@code setAliases} STORES a folded list, not that anything reads it.
	 *
	 * <p>Positive for the reason the case above is. {@code foldedLower(} is deliberately NOT forbidden:
	 * two of these accessors legitimately fold the invariant TEXT once per call, which is the same rule
	 * one operand along. What is forbidden is reaching an alias through a primitive that folds it.
	 */
	@Test
	public void everyAliasScanReadsTheStoredFoldedList() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "DrugReference.java");
		String[] accessors = {
				"boolean matchesFoldedText(String foldedLowerText) {",
				"boolean matchesDrugName(FoldedName drugName) {",
				"List<String> aliasesIn(String lowerText) {",
				"List<String> aliasesNaming(FoldedName drugName) {",
				"List<NamedOccurrence> namedOccurrences(String foldedLowerText, int pos) {" };
		for (String accessor : accessors) {
			SourceScan.Region body = scan.body(accessor);
			assertContains(scan, body, "foldedAliases", accessor);
			assertForbids(scan, body, accessor, "containsWord(", "matchesOrderName(", "matchesText(",
					"containsBoundedToken(", "foldDiacritics(");
		}
	}

	/**
	 * The dose arm's clause-level gate reads the clause the arm already folded.
	 *
	 * <p>{@code DrugSafetyValidator.attributedDoses} folds the clause once and every position the arm
	 * compares is an index into THAT string, so a gate re-folding it reads a different one — measured
	 * on {@code substanceOwnsDose}, where an entry matched by the gate could not then locate itself.
	 * Closing that meant an accessor taking prose already folded, which issue #330 built.
	 *
	 * <p>Guarded here because the prose operand is a bare {@code String} by design — see
	 * {@link DrugReference#matchesFoldedText} for why it is not a {@link DrugReference.FoldedName} —
	 * so unlike the recorded-name operand, handing this one to the unfolded arity COMPILES, and
	 * reverting the call left the whole api suite green when a review tried it.
	 */
	@Test
	public void theDoseArmsClauseGateReadsTheClauseTheArmFolded() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "DrugSafetyValidator.java");
		SourceScan.Region gate = scan.body(
				"private static boolean namesSubstance(String foldedClause, List<DrugReference> rows) {");
		assertContains(scan, gate, "matchesFoldedText(", "namesSubstance");
		assertForbids(scan, gate, "namesSubstance", "matchesText(", "foldedLower(", "containsWord(");
	}

	/** @param what names the body in the failure message. */
	private static void assertContains(SourceScan scan, SourceScan.Region body, String needle,
			String what) {
		for (Integer at : scan.literalOffsets(needle)) {
			if (body.contains(at)) {
				return;
			}
		}
		throw new AssertionFailedError("\"" + needle + "\" is gone from " + what + "; that call is what "
				+ "the guard requires, and requiring it is what forbids every spelling that is not it — "
				+ "a list of forbidden spellings has been defeated once for every time one was written");
	}

	private static void assertForbids(SourceScan scan, SourceScan.Region body, String what,
			String... needles) {
		for (String needle : needles) {
			for (Integer at : scan.literalOffsets(needle)) {
				assertFalse(body.contains(at), "\"" + scan.statementAt(at) + "\" (line " + scan.lineOf(at)
						+ ") reaches an operand through \"" + needle + "\" inside " + what + ", which folds "
						+ "for itself — the operand is already folded where it was produced");
			}
		}
	}
}
