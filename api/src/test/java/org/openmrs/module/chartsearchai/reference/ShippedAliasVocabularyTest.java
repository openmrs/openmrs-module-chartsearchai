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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
	 * #392). So the short BRAND aliases are asserted as a sorted list too, which is how {@code Align},
	 * {@code Today} and {@code Ella} were caught before it and how the next {@code stye} is caught: a
	 * refresh puts the brand that joined into this diff, where it can be read. What this case holds is
	 * therefore three things: every short alias that is not a declared brand is one of the seven
	 * substance names; the short brand aliases are exactly this list; and no brand alias is three
	 * characters or fewer.
	 *
	 * <p>Asserted as the lists, in sorted order, so a knowledge-base refresh reports WHICH word joined
	 * rather than that a number moved.
	 */
	@Test
	public void everyShortAliasTheShippedVocabularyCarriesIsItselfASubstance() throws Exception {
		Set<String> brands = shippedBrandNames();
		assertTrue(brands.size() > 1000,
			"precondition: the shipped file declares brand names (knowledge-base schema 1.3)");
		List<String> shortNonBrandAliases = new ArrayList<String>();
		List<String> shortBrandAliases = new ArrayList<String>();
		List<String> tooShortBrands = new ArrayList<String>();
		for (String alias : distinctAliases()) {
			if (alias.length() <= SHORT) {
				(brands.contains(alias) ? shortBrandAliases : shortNonBrandAliases).add(alias);
			}
			if (alias.length() <= 3 && brands.contains(alias)) {
				tooShortBrands.add(alias);
			}
		}
		Collections.sort(shortNonBrandAliases);
		Collections.sort(shortBrandAliases);

		assertEquals(java.util.Arrays.asList("clove", "hemin", "iron", "kava", "opium", "urea", "yeast"),
			shortNonBrandAliases,
			"every alias of " + SHORT + " characters or fewer that is not a declared brand name must be a"
					+ " substance name — the property ADR Decision 68 declines to give up, and a new member"
					+ " here is a change to that argument rather than a number to update");
		assertEquals(SHORT_BRAND_ALIASES, shortBrandAliases,
			"the brand aliases of " + SHORT + " characters or fewer, as the shipped file carried them on"
					+ " 2026-09-09 — a refresh that adds one must be read for whether the newcomer is a name"
					+ " someone asks a drug by or a word ordinary clinical prose carries (stye, propel),"
					+ " and the knowledge base's exclusion list updated before this list is");
		assertEquals(Collections.emptyList(), tooShortBrands,
			"a brand alias of three characters or fewer is an acronym the knowledge base excludes at"
					+ " source; one reaching here means that exclusion was lost on a refresh");
	}

	/** The 193 brand aliases of {@link #SHORT} characters or fewer in the shipped file, produced by
	 *  {@link DrugReference#getAliases()} over the real load and intersected with the raw
	 *  {@code brand_names}; sorted. Every member was read when the list was pinned. */
	private static final List<String> SHORT_BRAND_ALIASES = java.util.Arrays.asList(
			"aceon", "actiq", "actos", "adbry", "addyi", "adoxa", "advil", "afrin", "ajovy", "akten",
			"aleve", "alli", "alora", "alrex", "alyq", "amrix", "anoro", "arava", "arbli", "ascor", "atgam",
			"atryn", "avar", "aveed", "avita", "avoca", "axtle", "azopt", "azor", "baza", "beser", "bicnu",
			"bidil", "bkemv", "botox", "breo", "bumex", "bupap", "calan", "capex", "carac", "cequa",
			"chooz", "cipro", "coreg", "creon", "curae", "cutar", "ddavp", "depen", "dex4", "dhivy",
			"digex", "digox", "dilt", "disal", "doans", "dodex", "doral", "doryx", "doxil", "duopa",
			"ecoza", "edex", "emcyt", "emgel", "emsam", "enoby", "esgic", "evdi", "evivo", "eylea", "feiba",
			"fiasp", "flac", "frova", "glydo", "glyrx", "gvoke", "halog", "hemax", "hulio", "hycet", "ifex",
			"infed", "inova", "inzo", "iodip", "iosat", "ipol", "ivra", "jalyn", "koate", "kofal", "kuric",
			"kuvan", "lasix", "lidum", "lopid", "luxiq", "mapap", "mobic", "mvasi", "nasop", "nemex",
			"neuac", "nexha", "nityr", "nocto", "norco", "nuox", "nuwiq", "nytol", "ofev", "okebo", "olux",
			"onfi", "onyda", "opvee", "oseni", "ovace", "paxil", "phexx", "pretz", "proin", "qdolo",
			"qlosi", "qmiiz", "qnasl", "qtern", "qvar", "rayos", "rebif", "repan", "revia", "ringl",
			"sebex", "selrx", "sileo", "siliq", "skyla", "sprix", "sular", "sutab", "symfi", "taltz",
			"tdvax", "tigan", "tobi", "tolak", "tovet", "tpoxx", "triaz", "tums", "urex", "urso", "utira",
			"uzedy", "vanos", "vevye", "vfend", "vonjo", "vtol", "vuity", "vykat", "wakix", "wyost",
			"xalix", "xanax", "xbryk", "xgeva", "xiclo", "xoten", "xromi", "xyrem", "xywav", "xyzal",
			"yonsa", "yutiq", "zensa", "zetia", "ziac", "ziana", "zilxi", "zimhi", "zingo", "zocor",
			"zomig", "zosyn", "zybic", "zyflo", "zylet", "zyvox");
}
