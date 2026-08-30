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
}
