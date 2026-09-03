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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link DrugReferenceService#nameIndex()} answers the same question {@link DrugReference#isNamed}
 * answers, over whole LOADED datasets.
 *
 * <p><b>Why this is a property and not a pair of examples.</b> Issue #339 asks
 * {@code DrugSafetyValidator.unambiguouslyNames} once per rule chip rather than once per folded chip,
 * and the {@code getAll()} walk it used to collect a token's rival claimants by then grows with the
 * drugs in play — which {@code CoMedicationResolutionPerPassTest} forbids. So the dataset is inverted
 * once per pass and read back. The set of rival claimants is what decides whether one substance's
 * rated mechanism may be printed under another substance's name (issue #296), so an index that admits
 * or loses ONE claimant relative to the predicate is a silent mis-attribution rather than a slow
 * lookup — and it would be silent in exactly the tail nobody writes an example for.
 *
 * <p>Driven over datasets read by the real parsers, and asked of every name every entry carries as
 * well as of every rule TOKEN those datasets publish, which is the corpus the production caller
 * actually asks about.
 */
public class NameIndexAgreesWithIsNamedTest {

	/** An operator-authored dataset one of whose entries spells a name ONLY in mixed case, beside a
	 *  second entry claiming the lower-case form. The {@code json} parser trims but does not
	 *  lower-case, so this is the shape in which the index's KEY-side normalisation has work to do —
	 *  and a fixture carrying BOTH spellings on ONE entry is not, since the lower-case key is then
	 *  present either way (measured: such a fixture passes under the mutation). */
	private static final String NOT_PRE_NORMALISED_FIXTURE =
			"chartsearchai-test/drug-reference-capitalised-only-alias.json";

	/** @return the entries {@code token} names, found the way the predicate finds them: by asking each
	 *          loaded row. This is the walk the index replaces, written out here and nowhere in
	 *          production. */
	private static List<DrugReference> byPredicate(List<DrugReference> entries, String token) {
		List<DrugReference> named = new ArrayList<DrugReference>();
		for (DrugReference entry : entries) {
			if (entry.isNamed(token)) {
				named.add(entry);
			}
		}
		return named;
	}

	/** @return every name and every rule token the dataset publishes, plus a padded and an upper-cased
	 *          spelling of each — the three shapes {@link DrugReference#normalizeName} exists to make
	 *          one, and the ones a curated file can carry (the {@code ddinter} parser lower-cases and
	 *          trims, the operator-editable json sanitizes neither). */
	private static Set<String> corpus(List<DrugReference> entries) {
		Set<String> tokens = new LinkedHashSet<String>();
		for (DrugReference entry : entries) {
			tokens.addAll(entry.getAliases());
			for (DrugReference.Interaction rule : entry.getInteractions()) {
				if (rule.getToken() != null) {
					tokens.add(rule.getToken());
				}
			}
		}
		Set<String> spellings = new LinkedHashSet<String>(tokens);
		for (String token : tokens) {
			if (token != null) {
				spellings.add("  " + token + " ");
				spellings.add(token.toUpperCase(java.util.Locale.ROOT));
			}
		}
		return spellings;
	}

	private static void assertAgrees(List<DrugReference> entries, String dataset) {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		Map<String, List<DrugReference>> index = service.nameIndex();
		int asked = 0;
		for (String token : corpus(entries)) {
			assertEquals(byPredicate(entries, token),
				DrugReferenceService.entriesNamedBy(token, index),
				"the index and DrugReference.isNamed disagree about which entries of " + dataset
						+ " are named by \"" + token + "\"; the rival-claimant set is what decides"
						+ " whether one substance's mechanism may be printed under another's name"
						+ " (issue #296), so a divergence here is a silent mis-attribution");
			asked++;
		}
		assertTrue(asked > 0, dataset + " published no name to ask about, so this says nothing");
	}

	@Test
	public void theIndexAgreesOverTheBundledDdinterExcerpt() {
		assertAgrees(DrugReferenceTestSupport.ddinterEntries(), "the pinned DDInter excerpt");
	}

	@Test
	public void theIndexAgreesOverTheDatasetTheModuleShips() throws IOException {
		// The shipped knowledge base, not the excerpt: 25 of its 2093 rule tokens are named by more than
		// one substance, and a token with several claimants is the only shape where admitting or losing
		// one changes an answer. An excerpt with one claimant per token cannot see it.
		assertAgrees(DrugReferenceTestSupport.shippedEntries(), "the shipped knowledge base");
	}

	@Test
	public void aTokenNoEntryNamesAndABlankOneFindNothing() {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.ddinterEntries());
		Map<String, List<DrugReference>> index = service.nameIndex();

		assertTrue(DrugReferenceService.entriesNamedBy("no-such-drug", index).isEmpty(),
			"a name no entry carries names nothing");
		assertTrue(DrugReferenceService.entriesNamedBy("   ", index).isEmpty(),
			"a blank token names nothing — the answer DrugReference.normalizeName gives it");
		assertTrue(DrugReferenceService.entriesNamedBy(null, index).isEmpty(),
			"and so does no token at all");
	}

	/**
	 * The answer is not the index's own list to be sorted or filtered in place.
	 *
	 * <p>Same contract and same reason as {@code findForActiveOrders}' (ADR Decision 58): this list is
	 * held for the whole pass and read by every reconciliation in it, so a consumer that reordered it
	 * would change what a later chip is told about a token's claimants — which decides whether one
	 * substance's rated mechanism may be printed under another's name. Nothing in the module mutates
	 * it, so the guarantee cost nothing to take, and nothing behavioural can see it: this is what
	 * reddens if the wrapper is removed.
	 */
	@Test
	public void theClaimantsOfATokenCannotBeMutatedByAHolder() {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.ddinterEntries());
		Map<String, List<DrugReference>> index = service.nameIndex();
		List<DrugReference> named = DrugReferenceService.entriesNamedBy("warfarin", index);

		assertTrue(!named.isEmpty(), "precondition: the excerpt must name warfarin, or this asserts "
				+ "nothing about a list with anything in it");
		try {
			named.clear();
			org.junit.jupiter.api.Assertions.fail("the claimant list must not be mutable by a holder");
		}
		catch (UnsupportedOperationException expected) {
			// the contract
		}
	}

	/**
	 * A dataset whose aliases are NOT already normalised — which is where the KEY side of the
	 * agreement is actually asked to do something.
	 *
	 * <p>The two cases above drive datasets the {@code ddinter} parser produced, and that parser
	 * lower-cases and trims every alias it writes. So {@code corpus()}'s padded and upper-cased
	 * spellings exercise only the QUERY side — {@code entriesNamedBy}'s own {@code normalizeName} —
	 * and the normalisation inside {@link DrugReference#nameKeys()} is never put to a name that needs
	 * it: before this case existed, dropping the key side of it left the whole api suite green — which
	 * is why this case exists and is what it closes. Do not read that sentence as current, which an
	 * earlier form of this javadoc invited: with this case in place, dropping the key-side
	 * normalisation reddens this one (re-measured at issue #339's review round 11 head). Nothing is
	 * claimed about the rest of the suite under that mutation.
	 *
	 * <p>The {@code json} source trims but does not lower-case, so an operator file can spell a name
	 * ONLY in mixed case — and it has to be a name no OTHER alias of that entry supplies in lower case,
	 * or the key is present under the mutation anyway and the case passes. The drift it catches is
	 * fail-OPEN: an index that
	 * loses a claimant makes {@code uniqueStrongestClaimant} more likely to answer true, so the gate
	 * PERMITS a displacement it should refuse — one substance's rated mechanism under another's name.
	 */
	@Test
	public void theIndexAgreesOverAnOperatorAuthoredDatasetThatIsNotAlreadyLowerCased()
			throws IOException {
		List<DrugReference> entries =
				DrugReferenceTestSupport.fixtureEntries(NOT_PRE_NORMALISED_FIXTURE);
		boolean capitalised = false;
		for (DrugReference entry : entries) {
			for (String alias : entry.getAliases()) {
				capitalised |= alias != null
						&& !alias.equals(alias.toLowerCase(java.util.Locale.ROOT));
			}
		}
		assertTrue(capitalised, "precondition: this fixture must carry an alias the parser did NOT "
				+ "lower-case, or the key side of the agreement is not being asked anything");

		assertAgrees(entries, "the operator-authored fixture " + NOT_PRE_NORMALISED_FIXTURE);
	}
}
