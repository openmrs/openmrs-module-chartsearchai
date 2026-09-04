/*
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
 * a count cannot, because both spellings call the folded matcher exactly once per entry. Identity
 * says WHERE and never WHAT: a hoist folding only half of {@link DrugReference#foldedLower} hands
 * every entry of a sweep one instance just as a correct one does, so
 * {@link #theProseOperandIsDiacriticFoldedAndNotMerelyLowerCased} asks the other half of that line
 * by reaching a folded alias from an ACCENTED question.
 * {@link #anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches} drives the two WITNESS scans,
 * whose compared operand the class-scope whitelist cannot see because both are entitled to name the raw
 * list; it needs a SECOND matching entry, since the narrowing those scans serve returns early on fewer
 * than two matches.
 * {@link #aWitnessIsTheAliasThatMatchedAndNotItsNeighbour} and
 * {@link #everyAliasOfALoadedEntryIsItsOwnWitness} ask the index alignment those two witness accessors
 * rest on: the first over the one fixture shape that exposes a skipped null, the second as a property
 * of whole loaded datasets, which is what reaches the edits — a collapsed duplicate, a filter — the
 * first cannot see.
 * {@link #anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches} drives the one shape in
 * which the alias-side fold is load-bearing at all — over every dataset the module ships,
 * {@link DrugReference#foldDiacritics} takes its ASCII fast path and returns the alias unchanged, so
 * a guard comparing a stored folded alias against {@code foldedLower} of the raw one is satisfied
 * element-for-element by the raw list and proves nothing. And the source scan pins what no
 * behavioural case reaches: {@code PatientClinicalContext.hasActiveDrug} compares through a static
 * matcher with no seam a probe can override, and a memo-shaped regression in {@code setAliases}
 * would be invisible to both.
 */
public class FoldedOperandTest {

	/** A statement DECLARING something called {@code aliases}/{@code foldedAliases} — a type token in
	 *  front of the name — as opposed to assigning the field of that name. */
	private static final Pattern DECLARATION = Pattern.compile(
			"^(?:private\\s+|public\\s+|protected\\s+|static\\s+|final\\s+)*[\\w.<>\\[\\], ?]+\\s+"
					+ "(?:folded)?[aA]liases\\s*=");

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
	 * therefore reach neither probe. Nothing sits between them, so a scan that
	 * stops short of the pair reaches neither and the two lists stay aligned. It could still stop
	 * BETWEEN them, if the first probe itself claimed the display name — no arrangement here spells a
	 * probe's own name as the token, and if one ever does the size assertion fails loudly rather than
	 * comparing two lists that describe different scans.
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
	 * and the shape in which storing the RAW alias list where the folded one belongs is visible at all.
	 *
	 * <p>Over the shipped knowledge base it is not visible: measured 2026-08-30 through the real
	 * loader, 0 of its 8300 alias slots (5169 distinct) folds to a different String — its one alias
	 * above U+007F carries an EN DASH rather than a combining mark — so the two lists are equal
	 * element for element and, on that dataset, the very same instances.
	 * {@code drug-reference-accented-tokens.json} does not reach it either — its entries each carry an
	 * unaccented sibling alias, so the accented one is never the only way in.
	 *
	 * <p><b>The fixture's alias carries an upper-case letter as well as its combining marks, and that
	 * is the case rather than a detail of it.</b> {@link DrugReference#foldedLower} is a lower-case
	 * AND a diacritic fold, {@code foldedAll} is the sole producer of both folded lists, and its body
	 * names neither alias list, so {@link #everyAliasScanReadsTheStoredFoldedList} — which scans the
	 * READING accessors' bodies and every mention of the raw list at class scope — does not reach it.
	 * With a lower-case alias this case saw only half of it: writing
	 * {@code foldedAll} as {@code foldDiacritics(value)} — the spelling CLAUDE.md's own fold rule names
	 * — leaves every alias carrying an upper-case letter matching nothing, which is a shape the
	 * {@code json} parser reaches (it trims its aliases without lower-casing them), and a review
	 * measured the whole api suite green under it. With the alias spelled {@code Kétoprofène} that
	 * mutation reddens this case, {@link #theProseOperandIsDiacriticFoldedAndNotMerelyLowerCased} and
	 * {@link #anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches} — whose fixture carries this
	 * entry beside a sibling — and nothing else in the api suite. Re-measured 2026-08-30 over the whole
	 * api suite when the third of those was added; mutate the line and read the failures rather than
	 * trusting the list.
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
						+ "\"Kétoprofène\" — the alias is matched in its FOLDED form, lower-cased and "
						+ "diacritic-stripped, and an implementation storing the raw list where the folded "
						+ "one belongs, or folding only one of the two, reaches it only for a chart that "
						+ "spells the alias as it is written, was: " + byOrderName);

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
	 * The prose operand is {@link DrugReference#foldedLower}-folded, not merely lower-cased.
	 *
	 * <p>The other side of the case above, over the same fixture and differing from its prose leg only
	 * in how the question spells the drug: there an unaccented question reaches a stored alias the
	 * fold made unaccented, here an ACCENTED one does, which it can only do if the QUESTION was folded
	 * too. Before this change {@code findByQuery}'s operand was folded by {@code containsBoundedToken}
	 * at the innermost comparison and the hoist could not be got wrong; now it is one line's
	 * responsibility, and {@code question.toLowerCase(Locale.ROOT)} — the expression that line
	 * replaced, so the most likely "restore" edit — is a hoist that folds half of what it must.
	 * Measured: with that expression in place this case is the only one in the api suite that reddens,
	 * and a review measured the same expression green across the whole suite before it existed.
	 *
	 * <p>{@link #theProseScanFoldsItsTextOnceForTheWholeSweep} cannot see that, and the two are asking
	 * different questions of one line: that one asks WHERE the fold happens, by handing every entry of
	 * a sweep the same instance, which a lower-case-only hoist satisfies just as well.
	 */
	@Test
	public void theProseOperandIsDiacriticFoldedAndNotMerelyLowerCased() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-accented-alias-only.json"));

		List<SafetyWarning> byAccentedProse = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give her kétoprofène?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("aspirine"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(byAccentedProse,
				SafetyWarning.TYPE_CONTRAINDICATION, "Kétoprofène"),
				"an accented question must reach an entry whose stored alias the fold made unaccented; "
						+ "a scan that only lower-cases its prose reaches it for no spelling at all, was: "
						+ byAccentedProse);
	}

	/**
	 * The two WITNESS scans compare the folded alias, and an entry whose witness goes missing is dropped
	 * from the candidate set.
	 *
	 * <p>The behavioural channel for the two accessors {@link #everyAliasScanReadsTheStoredFoldedList}
	 * can say least about. {@code DrugReference.aliasesIn} and {@code aliasesNaming} walk the folded list
	 * while reporting the RAW alias at that index, so both must name the raw list and its whitelist must
	 * let them — and their "names the folded list" assertion is then satisfied by the
	 * {@code foldedAliases.size()} in the loop header, whatever the comparison one line below reads. That
	 * swap is not the "same answers" shape the class javadoc calls forced: it calls the folded primitive
	 * with an unfolded operand, so it answers DIFFERENTLY, and this is where the difference shows. The
	 * two {@code assertContains} calls naming each compared expression are the structural channel for the
	 * same property; each mutation reddens both.
	 *
	 * <p><b>It needs a SECOND matching entry, which is the whole of what separates this fixture from
	 * {@code drug-reference-accented-alias-only.json}.</b> {@code findImpliedByQuery} and
	 * {@code findImpliedByDrugName} return early on fewer than two matches, so over the one-entry fixture
	 * neither witness scan runs; and where the witness pass DOES run, {@code rowsOf} hands back the whole
	 * matched list when nothing at all is in play, so the sibling has to carry its own substance into
	 * play under the mutation as well — which its plain-ASCII alias does. Then the accented entry alone
	 * is dropped, and with it the contraindication the arms would have raised: issue #209's surface,
	 * fail-closed and silent.
	 *
	 * <p>Both legs, because the two scans are separate methods under the two different boundary rules:
	 * the recorded name reaches {@code aliasesNaming} and the prose {@code aliasesIn}. Measured — with
	 * only {@code aliasesIn} mutated the recorded-name leg above stays green and this one fails. The
	 * converse was not measured and cannot be from here: mutating {@code aliasesNaming} fails the first
	 * assertion, so the prose leg is never reached.
	 */
	@Test
	public void anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-accented-alias-with-sibling.json"));

		List<SafetyWarning> byOrderName = DrugReferenceTestSupport.validator(service).validate("",
				"What are her current medications?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Ketoprofene 100mg"), null,
						DrugReferenceTestSupport.set("aspirine"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(byOrderName,
				SafetyWarning.TYPE_CONTRAINDICATION, "Kétoprofène"),
				"the recorded-name witness must be found by comparing the FOLDED alias; comparing the raw "
						+ "one leaves this entry naming no substance of its own, and the sibling's alias then "
						+ "carries the narrowing past rowsOf's empty-set fallback, was: " + byOrderName);

		List<SafetyWarning> byProse = DrugReferenceTestSupport.validator(service).validate("",
				"Can I give her ketoprofene?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("aspirine"), null));
		assertTrue(DrugReferenceTestSupport.detailContains(byProse,
				SafetyWarning.TYPE_CONTRAINDICATION, "Kétoprofène"),
				"and the prose witness likewise, which is a different scan under a different boundary rule, "
						+ "was: " + byProse);
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
	 *
	 * <p><b>One shape of that edit, not the class of them.</b> A review measured a second edit breaking
	 * the same invariant with this case green: see {@link #everyAliasOfALoadedEntryIsItsOwnWitness},
	 * which asks the invariant as a property instead. This case stays because it names the failure at
	 * one row an operator can read — {@code [null]} where a witness belongs.
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

	/** An entry carrying the alias-list shapes no bundled dataset does — a null before the first real
	 *  alias, one name spelled twice in two cases, and a third alias after both. See the fixture's own
	 *  {@code description} for why each is needed. */
	private static final String ALIAS_SPELLED_TWICE_FIXTURE =
			"chartsearchai-test/drug-reference-alias-spelled-twice.json";

	/**
	 * Every name a loaded entry carries is found, AS ITSELF, by a scan for that very name — the
	 * alignment invariant asked as the property the witness accessors rest on, over whole loaded
	 * datasets rather than over one arrangement.
	 *
	 * <p><b>Why this stands beside the case above rather than being covered by it.</b> That case pins
	 * ONE edit to {@code DrugReference.foldedAll}: dropping nulls. A review measured a SECOND edit
	 * breaking the same invariant with the whole api suite green, that case included — collapsing
	 * duplicates, {@code if (!folded.contains(one)) folded.add(one);} — after which
	 * {@code setAliases(["Aspirin", "aspirin", "acetylsalicylic acid"])} answered
	 * {@code aliasesNaming("Acetylsalicylic acid 100mg")} with {@code [aspirin]} while
	 * {@code matchesDrugName} still answered true. Those witnesses are what
	 * {@code DrugReferenceService.namesSubstanceOf} narrows a candidate set by, so an entry naming the
	 * recorded drug resolves to another substance or to none and {@code findImpliedByQuery} /
	 * {@code findImpliedByDrugName} drop it — issue #209's surface, fail-closed and silent.
	 *
	 * <p><b>What it covers.</b> Any edit that leaves the folded list a DIFFERENT sequence from
	 * {@code foldedLower} of the raw one: skipping nulls, collapsing duplicates, filtering, reordering.
	 * It reads no field and asks no shape — it asks the two witness accessors, which is where a
	 * misalignment does its damage, so it covers a misaligned READ as well as a misaligned STORE:
	 * measured, reporting {@code aliases.get(aliases.size() - 1 - i)} reddens it, and so does comparing
	 * the RAW alias instead of the folded one, because the fixture spells one alias with a capital and
	 * {@code foldedLower} is a lower-case fold too — a third channel for the shape
	 * {@link #anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches} and the source guard
	 * already hold, not a replacement for either.
	 *
	 * <p><b>What it does not.</b> It says nothing about WHERE an operand is folded, which is what the
	 * identity cases above ask, nor that the alias side is folded at ALL, which needs an accented alias
	 * no shipped dataset carries ({@link #anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches}).
	 * It skips null and blank aliases, which name nothing under either boundary rule, so an edit that
	 * only moves those among themselves is invisible to it. It sees only the datasets it loads, so an
	 * alias-list shape none of them carries escapes — which is the whole reason
	 * {@link #ALIAS_SPELLED_TWICE_FIXTURE} exists, and the reason to add to that fixture rather than to
	 * assume the real datasets grew the shape. And a collapse of two aliases whose RAW spellings are
	 * identical, with no distinct alias after them, shifts nothing this can observe. <b>It GUARDS the
	 * class of edits; it does not make it unconstructible.</b> Carrying the raw and folded halves
	 * together — one list of pairs, which is what {@link DrugReference.FoldedName} is for the recorded
	 * name — would; ADR Decision 55 records why that was not taken here.
	 *
	 * <p>Over three loaded datasets and not one: the fixture for the shapes, the null-alias fixture the
	 * case above already reaches, and the shipped knowledge base, so the property is stated over the
	 * whole alias corpus the module actually ships rather than over hand-picked rows.
	 */
	@Test
	public void everyAliasOfALoadedEntryIsItsOwnWitness() throws IOException {
		List<DrugReference> shapes = DrugReferenceTestSupport.fixtureEntries(ALIAS_SPELLED_TWICE_FIXTURE);
		assertEquals(java.util.Arrays.asList(null, "Aspirin", "aspirin", "acetylsalicylic acid"),
				DrugReferenceTestSupport.row(shapes, "Aspirin").getAliases(),
				"precondition: the fixture must still carry a null, then one name in two cases, then a "
						+ "DIFFERENT alias after them — drop any of the three and the dedupe, null-skip, "
						+ "filter and reorder edits stop shifting an observable index");

		assertEveryAliasIsItsOwnWitness(shapes, ALIAS_SPELLED_TWICE_FIXTURE);
		assertEveryAliasIsItsOwnWitness(DrugReferenceTestSupport.fixtureEntries(
				"chartsearchai-test/drug-reference-null-interaction-and-alias.json"),
				"the null-alias fixture");
		assertEveryAliasIsItsOwnWitness(DrugReferenceTestSupport.shippedEntries(),
				"the shipped knowledge base");
	}

	/** Asserts the property above on both boundary rules — the recorded-name scan and the prose one —
	 *  for every alias of every entry, and fails loudly if a dataset yielded no alias to check, which is
	 *  the {@code 0 == 0} shape that left the ranking assertions above asserting nothing. */
	private static void assertEveryAliasIsItsOwnWitness(List<DrugReference> entries, String dataset) {
		int checked = 0;
		for (DrugReference entry : entries) {
			for (String alias : entry.getAliases()) {
				if (alias == null || alias.trim().isEmpty()) {
					continue;
				}
				checked++;
				assertTrue(entry.aliasesNaming(DrugReference.fold(alias)).contains(alias),
						"in " + dataset + ", entry \"" + entry.getName() + "\" does not report \"" + alias
								+ "\" as the witness for that very name; the recorded-name scan walks the "
								+ "folded alias list and reports the raw one at the same index, so this is the "
								+ "two lists having come out of step. Was: "
								+ entry.aliasesNaming(DrugReference.fold(alias)));
				assertTrue(entry.aliasesIn(alias.toLowerCase(java.util.Locale.ROOT)).contains(alias),
						"in " + dataset + ", entry \"" + entry.getName() + "\" does not report \"" + alias
								+ "\" as the witness for that name in prose, which is the same alignment under "
								+ "the other boundary rule. Was: "
								+ entry.aliasesIn(alias.toLowerCase(java.util.Locale.ROOT)));
			}
		}
		assertTrue(checked > 0, dataset + " yielded no alias to check, so this asserts nothing");
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
			// A DECLARATION of something called aliases is not a write to the field, whether it is one
			// of the two fields or an ordinary local. Recognised by the type token in front of the
			// name rather than by one hard-coded modifier prefix, which reddened on a local and whose
			// natural repair — loosening the needle — is how the two earlier needle rounds were lost.
			if (DECLARATION.matcher(scan.statementAt(at)).find()) {
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
		// The ARGUMENTS and not only the call, plus the hoist above the loop. Requiring the call alone
		// left `matchesFoldedOrderName(folded, tokenFor(n))` — a private helper doing the fold — green
		// across the whole build, which is the same extract-a-helper escape that forced the alias
		// guard to be stated at class scope. A token computed per comparison is what this exists to
		// forbid, whatever the expression computing it is called.
		assertContains(scan, loop, "matchesFoldedOrderName(folded, token)", "hasActiveDrug's loop");
		assertContains(scan, scan.body("boolean hasActiveDrug(String nameToken, String atcCode) {"),
				"DrugReference.fold(n)", "hasActiveDrug, above its loop");
		assertForbids(scan, loop, "hasActiveDrug's loop", "matchesOrderName(", "containsWord(",
				"foldedLower(", "foldDiacritics(", "toLowerCase(", "fold(");
	}

	/**
	 * Every scan of an entry's own names reads {@link DrugReference}'s stored FOLDED alias list, and
	 * none of them folds an alias itself.
	 *
	 * <p>This is the half of the change worth most — by ADR Decision 55's own decomposition the alias
	 * side is five sixths of the win — and <b>the shape that reinstates the folds is invisible to every
	 * behavioural case</b>, deliberately: the unfolded primitives fold the raw alias for themselves, so
	 * an accessor rewritten to iterate {@code aliases} through {@code containsWord} or
	 * {@code matchesOrderName} returns exactly the same answers while reinstating ~1.4 million
	 * {@code foldedLower} calls a pass. A review did precisely that to the two hottest accessors and the
	 * whole api suite stayed green.
	 * {@link #anAccentedAliasIsTheOnlyNameOfAnEntryAnUnaccentedOrderReaches} cannot reach it either: it
	 * pins that {@code setAliases} STORES a folded list, not that anything reads it.
	 *
	 * <p><b>A different shape IS behaviourally visible, and it lives in the two witness accessors.</b>
	 * They walk the folded list and report {@code aliases.get(i)}, so each must name the raw list and the
	 * class-scope whitelist below has to let it — which leaves the folded primitive called with the RAW
	 * operand — the expression already sitting one line below, on the {@code carried.add} line — which
	 * satisfied every structural assertion in this class until the two {@code assertContains} calls below
	 * named each compared expression. That one answers DIFFERENTLY rather than identically, so it is not
	 * the "forced" shape above: {@link #anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches}
	 * reddens on either swap, and those two say which line went wrong.
	 *
	 * <p>Positive for the reason the case above is. {@code foldedLower(} is deliberately NOT forbidden:
	 * two of these accessors legitimately fold the invariant TEXT once per call, which is the same rule
	 * one operand along. What is forbidden is reaching an alias through a primitive that folds it.
	 */
	@Test
	public void everyAliasScanReadsTheStoredFoldedList() throws IOException {
		SourceScan scan = new SourceScan("src/main/java/org/openmrs/module/chartsearchai/reference/"
				+ "DrugReference.java");
		// Both operands already folded, so nothing in these four may fold anything at all.
		String[] foldNothing = {
				"boolean matchesFoldedText(String foldedLowerText) {",
				"boolean matchesDrugName(FoldedName drugName) {",
				"List<String> aliasesNaming(FoldedName drugName) {",
				"List<NamedOccurrence> namedOccurrences(String foldedLowerText, int pos) {" };
		for (String accessor : foldNothing) {
			SourceScan.Region body = scan.body(accessor);
			assertContains(scan, body, "foldedAliases", accessor);
			assertForbids(scan, body, accessor, "containsWord(", "matchesOrderName(", "matchesText(",
					"containsBoundedToken(", "foldDiacritics(", "foldedLower(");
		}
		// aliasesIn is the one that still folds, and only its TEXT: it takes prose lower-cased but not
		// folded, which is the invariant operand of its own loop, so foldedLower( is permitted here and
		// nowhere above. Everything that would reach an ALIAS through a folding primitive is not.
		SourceScan.Region prose = scan.body("List<String> aliasesIn(String lowerText) {");
		assertContains(scan, prose, "foldedAliases", "aliasesIn");
		assertForbids(scan, prose, "aliasesIn", "containsWord(", "matchesOrderName(", "matchesText(",
				"containsBoundedToken(", "foldDiacritics(");

		// The two WITNESS accessors are entitled to name the raw list — the witness they return IS
		// aliases.get(i) — so their "foldedAliases" assertion above is already satisfied by the
		// foldedAliases.size() in the loop header and says nothing about which list the COMPARISON
		// reads. Swapping the compared operand for the raw alias — the expression already one line
		// below, on the carried.add line — is a spelling both bodies admit and it answers DIFFERENTLY,
		// so require the compared expression itself. anAccentedEntryKeepsItsPlaceBesideASiblingTheSameNameReaches is the behavioural half
		// of this and reddens on either swap; this names the line.
		assertContains(scan, scan.body("List<String> aliasesNaming(FoldedName drugName) {"),
				"foldedOrderNameMatch(drugName.folded, foldedAliases.get(i))", "aliasesNaming");
		assertContains(scan, prose, "foldedWordMatch(foldedText, foldedAliases.get(i))", "aliasesIn");

		// And the property stated ONCE, at class scope, because the per-body form above is anchored on
		// a method declaration and satisfied by any mention of the folded list inside it. A review beat
		// it with an emptiness fast-path — `return !foldedAliases.isEmpty() && anyAliasIn(text);` beside
		// a private helper looping the RAW list through containsWord — which is the shape hasActiveDrug
		// itself models, and the build stayed green. Whitelisting the bodies that may READ the raw list
		// catches that helper wherever it is written, which no per-accessor rule can. It reads TEXT and
		// not scopes, so a local called aliases inside this class reddens it too; the failure says to
		// rename the local, which is the cheaper of the two ways to make the identifier unambiguous.
		// The getter as well as the field: reading the raw list through {@code getAliases()} inside this
		// class reaches the same list by another name, and a helper spelled that way escaped the field
		// needle. Its own declaration is inside a whitelisted body, so it is not caught by itself.
		// The lookbehind excludes a word character and NOT a dot, so a qualified read — this.aliases,
		// entry.aliases — is matched too. Its first version excluded both, and `for (String a :
		// this.aliases)` inside an extracted helper then escaped the very guard whose javadoc names
		// that refactor. The companion write needle below has anticipated a receiver from the start.
		for (Integer at : scan.matches(Pattern.compile("(?<!\\w)aliases\\b|getAliases\\s*\\("))) {
			if (DECLARATION.matcher(scan.statementAt(at)).find()) {
				continue;
			}
			assertTrue(readsRawAliases(scan, at), "\"" + scan.statementAt(at) + "\" (line "
					+ scan.lineOf(at) + ") reads the RAW alias list outside the bodies entitled to it. "
					+ "Every scan of an entry's names reads the FOLDED list; the raw one is for storing "
					+ "it, handing it out, and reporting a witness by index. A helper extracted out of a "
					+ "scan to loop the raw list is the shape that beat the per-accessor form of this "
					+ "guard with the whole build green. If this is a LOCAL that merely shares the name, "
					+ "rename it: inside this class the bare identifier is the field, and saying so is "
					+ "cheaper than a guard that resolves scopes.");
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

	/** The bodies entitled to read {@link DrugReference}'s RAW alias list: the two that store and
	 *  publish it, the derivation of what is stored, the IDENTITY predicate (which compares
	 *  {@code normalizeName}, a different form, and is a named residue of issue #330), and the two
	 *  witness accessors, which report the raw alias at an index taken from the folded list — entitled
	 *  to it for that and nothing else, which is why the expression each COMPARES is required by name
	 *  above. */
	private static boolean readsRawAliases(SourceScan scan, int at) throws IOException {
		String[] entitled = {
				"public void setAliases(List<String> aliases) {",
				"public List<String> getAliases() {",
				"private static List<String> trimmedAliases(List<String> aliases) {",
				"boolean isNamed(String token) {",
				"List<String> aliasesIn(String lowerText) {",
				"List<String> aliasesNaming(FoldedName drugName) {" };
		for (String body : entitled) {
			// declarationAndBody, not body: two of these name the list in their SIGNATURE, and a
			// parameter called aliases is not a read of the field either.
			if (scan.declarationAndBody(body).contains(at)) {
				return true;
			}
		}
		return false;
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
