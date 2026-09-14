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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the shipped knowledge base's alias vocabulary is SHAPED like, measured through the real parser
 * and the real accessor — the figure ADR Decision 68 quotes when it declines to harvest a dictionary's
 * every synonym into that vocabulary (issue #353).
 *
 * <p><b>Why a test and not a script.</b> {@code CLAUDE.md} forbids re-expressing a production predicate
 * in a script that measures the knowledge base for a figure that will be quoted: this one would have to
 * reimplement {@code DdiDrugReferenceSource.DrugRow.addAlias}'s trim, lower-case and de-duplication,
 * plus {@code DrugReference.setAliases}' own trim and {@code DrugReferenceValidity}'s blank-alias drop
 * and its repair of an entry no alias names. A first pass of this measurement was written as a script
 * and disagreed with an independent read in exactly the tail being investigated, which is the failure
 * that rule records. Every figure asserted below is produced by {@link DrugReference#getAliases()} over
 * {@code DrugReferenceTestSupport.shippedEntries()}, which is the real
 * {@code DdiDrugReferenceSource#load()}.
 *
 * <p><b>What the figures are OF, and what makes them safe to pin.</b> They are properties of the
 * dataset this module SHIPS, so they move when that dataset is refreshed and are meant to — a refresh
 * that changes them is a change to the argument Decision 68 rests on and should be read, not silently
 * absorbed. That is why the short vocabulary is asserted as the LIST rather than as a count: a count
 * going from 7 to 8 says nothing, while a new member says which word joined and whether it is still a
 * substance name.
 */
public class ShippedAliasVocabularyTest {

	/** Five characters, because that is the band the dictionary synonyms Decision 68 declines
	 *  concentrate in ({@code alert}, {@code dos}, {@code pas}, {@code dt}, {@code ascot}). Nothing in
	 *  production reads it — {@code DrugReference.matchesText} has no length floor, which is the whole
	 *  reason those synonyms are a hazard — so it is a measurement boundary and not a rule. */
	private static final int SHORT = 5;

	/** The pinned list's own resource, one lower-cased brand per line, sorted as
	 *  {@link Collections#sort} orders them. A file rather than a literal because there are thousands
	 *  of them and a diff is the whole point. */
	private static final String PINNED_BRAND_NAMES = "chartsearchai-test/shipped-brand-aliases.txt";

	private static Set<String> distinctAliases() {
		Set<String> distinct = new LinkedHashSet<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			for (String alias : entry.getAliases()) {
				if (alias != null) {
					distinct.add(alias);
				}
			}
		}
		return distinct;
	}

	/**
	 * The brand names the shipped file itself declares ({@code drugs[].brand_names}, knowledge-base
	 * schema 1.3), lower-cased as {@code DdiDrugReferenceSource.DrugRow.addAlias} lower-cases every
	 * alias. Read off the raw JSON rather than through the parser, because the parser folds them into
	 * one alias list with everything else and the question below is precisely which aliases are brands.
	 */
	private static Set<String> shippedBrandNames() throws Exception {
		Set<String> brands = new LinkedHashSet<String>();
		try (InputStream in = ShippedAliasVocabularyTest.class
				.getResourceAsStream(DdiDrugReferenceSource.CLASSPATH_DEFAULT)) {
			assertNotNull(in, DdiDrugReferenceSource.CLASSPATH_DEFAULT + " should be on the classpath");
			for (JsonNode drug : new ObjectMapper().readTree(in).path("drugs")) {
				for (JsonNode brand : drug.path("brand_names")) {
					brands.add(brand.asText().trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		return brands;
	}

	/**
	 * The vocabulary's SHORT tail, which is what the argument turns on: every one of these is either a
	 * substance in its own right or a brand name the knowledge base declares, so nothing in the shipped
	 * alias set is a word that ordinary clinical prose carries in another sense. That is the property
	 * harvesting a dictionary's every synonym would end — measured on the 3.7.1 reference-application
	 * demo dictionary (2026-09-02, a raw {@code SELECT} over {@code concept_name}), the 171 bridged
	 * CIEL concepts it carries publish 1519 distinct non-voided names, 103 of them five characters or
	 * shorter, among them {@code alert}, {@code dos}, {@code pas}, {@code dt}, {@code abc},
	 * {@code arret}, {@code ascot}, {@code mabel}, {@code linde} and the French fully specified name
	 * {@code 73702}.
	 *
	 * <p><b>Brand names joined the vocabulary with knowledge-base schema 1.3</b>
	 * (openmrs-ddi-knowledge-base issue #5: {@code panadol} resolved to nothing, so the asked-about drug
	 * was never evaluated). They are short in bulk — {@code advil}, {@code aleve}, {@code cipro},
	 * {@code coreg}, {@code lasix}, {@code xanax}, {@code zocor} — so the seven substance names could
	 * not stay the whole assertion. The hazard the property guards against is addressed where the brands
	 * are produced: the knowledge base's {@code fetch_brand_names.py} excludes a brand of three
	 * characters or fewer, one whose every word is an ordinary dictionary word ({@code Align},
	 * {@code Today}, {@code Sleep Aid}) and one that is a first name, and commits the excluded list
	 * beside the kept one. That rule is a dictionary, and dictionaries have lacunae: {@code stye} (a
	 * mineral-oil eye ointment) survived it, because the dictionary carries {@code sty} and not
	 * {@code stye}, and measured through the real {@code findImpliedByQuery} "The patient has a stye on
	 * the right upper eyelid" resolved Mineral oil (found in review of the refresh that introduced it,
	 * #392).
	 *
	 * <p><b>Which brand JOINED on a refresh is not this case's question any more</b>, and the reason is
	 * that a band cannot be picked where the hazard has no length: {@code matchesText} has no length
	 * floor, so {@code propel} (six characters, a brand of the Mometasone rows) is the same hazard as
	 * {@code stye} (four) and no short-tail list can see it — restoring it to the shipped file left the
	 * whole api suite green while this case was asserting the 193 short brand aliases. That assertion
	 * was therefore replaced, in the same review, by
	 * {@link #theShippedVocabularyCarriesExactlyTheBrandNamesPinnedBesideIt}, which pins the WHOLE
	 * declared brand set; the short list was a projection of it and so a second thing to update rather
	 * than a second guard. What this case holds is the two things that pin is not: every short alias
	 * that is not a declared brand is one of the seven substance names, and no brand alias is three
	 * characters or fewer.
	 *
	 * <p>Asserted as the list, in sorted order, so a knowledge-base refresh reports WHICH word joined
	 * rather than that a number moved.
	 */
	@Test
	public void everyShortAliasTheShippedVocabularyCarriesIsItselfASubstance() throws Exception {
		Set<String> brands = shippedBrandNames();
		assertTrue(brands.size() > 1000,
			"precondition: the shipped file declares brand names (knowledge-base schema 1.3)");
		List<String> shortNonBrandAliases = new ArrayList<String>();
		List<String> tooShortBrands = new ArrayList<String>();
		for (String alias : distinctAliases()) {
			if (alias.length() <= SHORT && !brands.contains(alias)) {
				shortNonBrandAliases.add(alias);
			}
			if (alias.length() <= 3 && brands.contains(alias)) {
				tooShortBrands.add(alias);
			}
		}
		Collections.sort(shortNonBrandAliases);

		assertEquals(java.util.Arrays.asList("clove", "hemin", "iron", "kava", "opium", "urea", "yeast"),
			shortNonBrandAliases,
			"every alias of " + SHORT + " characters or fewer that is not a declared brand name must be a"
					+ " substance name — the property ADR Decision 68 declines to give up, and a new member"
					+ " here is a change to that argument rather than a number to update");
		assertEquals(Collections.emptyList(), tooShortBrands,
			"a brand alias of three characters or fewer is an acronym the knowledge base excludes at"
					+ " source; one reaching here means that exclusion was lost on a refresh");
	}

	/**
	 * Every brand name the shipped file declares, pinned beside it as a sorted list, so a
	 * knowledge-base refresh diffs to exactly the brands that joined and the ones that left.
	 *
	 * <p><b>Why the whole set and not a short tail.</b> A brand alias is a hazard when it is also an
	 * ordinary word — the word then resolves a drug through {@code DrugReference.matchesText}, which
	 * has no length floor, and the drug reaches the prompt as citable evidence with a safety finding
	 * attached (measured on {@code stye} in review of #392: "The patient has a stye on the right upper
	 * eyelid" resolved Mineral oil and raised an interaction finding against a levofloxacin order).
	 * Being ordinary has nothing to do with being short. The list this replaced held the brand aliases
	 * of five characters or fewer, and {@code propel} — a brand of the three Mometasone rows, and the
	 * second ordinary word that upstream exclusion rule let through — is six, so restoring it to the
	 * shipped file left the whole api suite green. Pinning the set itself is what removes the choice of
	 * band; it also removes the second list, since the short one was a projection of this one.
	 *
	 * <p><b>What a reader does with the diff.</b> A refresh regenerates this file; the diff is then
	 * read for brands that are words ordinary clinical prose carries in another sense, and any such
	 * word is excluded UPSTREAM — the knowledge base's {@code src/curation.json} {@code brand_exclusions}
	 * — before this list is regenerated. This file is the record of what was read, not a place to
	 * silence a brand: a name dropped here without being dropped upstream still resolves.
	 *
	 * <p>Produced through the real load, as everything else here is: {@link DrugReference#getAliases()}
	 * over {@code DrugReferenceTestSupport.shippedEntries()}, intersected with the raw
	 * {@code brand_names} the file declares. The first assertion is what makes the intersection safe to
	 * pin — every declared brand is carried, so nothing can leave this list by being dropped between
	 * the file and the alias vocabulary.
	 */
	@Test
	public void theShippedVocabularyCarriesExactlyTheBrandNamesPinnedBesideIt() throws Exception {
		Set<String> aliases = distinctAliases();
		List<String> carried = new ArrayList<String>();
		List<String> declaredButNotCarried = new ArrayList<String>();
		for (String brand : shippedBrandNames()) {
			(aliases.contains(brand) ? carried : declaredButNotCarried).add(brand);
		}
		Collections.sort(carried);
		Collections.sort(declaredButNotCarried);

		List<String> pinned = pinnedBrandNames();
		List<String> joined = new ArrayList<String>(carried);
		joined.removeAll(new HashSet<String>(pinned));
		List<String> gone = new ArrayList<String>(pinned);
		gone.removeAll(new HashSet<String>(carried));

		assertEquals(Collections.emptyList(), declaredButNotCarried,
			"every brand the file declares must reach the alias vocabulary, or this pin is over a subset"
					+ " that a parser or validity change can shrink silently");
		assertEquals(Collections.emptyList(), joined,
			"brands the shipped knowledge base now declares that " + PINNED_BRAND_NAMES + " does not —"
					+ " read each one for whether it is a name someone asks a drug by or a word ordinary"
					+ " clinical prose carries in another sense (stye, propel), exclude any such word"
					+ " upstream, and only then regenerate that file");
		assertEquals(Collections.emptyList(), gone,
			"brands " + PINNED_BRAND_NAMES + " carries that the shipped knowledge base no longer declares"
					+ " — a brand leaving is as much a change to what resolves as one joining");
		assertEquals(pinned.size(), carried.size(),
			"and the pinned file must carry each brand exactly once");
	}

	private static List<String> pinnedBrandNames() throws Exception {
		try (InputStream in = ShippedAliasVocabularyTest.class.getClassLoader()
				.getResourceAsStream(PINNED_BRAND_NAMES)) {
			assertNotNull(in, PINNED_BRAND_NAMES + " should be on the test classpath");
			List<String> pinned = new ArrayList<String>();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(in, StandardCharsets.UTF_8))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					if (!line.trim().isEmpty()) {
						pinned.add(line.trim());
					}
				}
			}
			return pinned;
		}
	}

}
