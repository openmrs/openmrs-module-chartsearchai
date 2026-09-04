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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The two-row slice issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/338">#338</a>'s cases read
 * really is a slice of the knowledge base the module ships.
 *
 * <p><b>Why it needs a case of its own.</b> Both cases in
 * {@code ReferenceProseFidelityTest} build their answers by slicing the record the injector renders
 * from this file, and compare them against that same record — so they are self-relative: edit a row's
 * ATC codes and they stay green, while the record they were written about no longer exists. ADR
 * Decision 59 quotes that rendered record character for character and this file is its only producer,
 * so an edit here can make a published measurement false on a green build. Measured: changing
 * {@code H02AB09} to {@code H03AB09} in the fixture leaves the whole class green.
 *
 * <p>It reads {@code shippedEntries()} deliberately, which that accessor's javadoc reserves for an
 * invariant over the shipped dataset rather than for a case asserting rendered TEXT: this asserts no
 * text, only that two rows are the dataset's own. A refresh that genuinely changes either row is
 * meant to redden here, where the failure names the row, rather than in a prose case that would fail
 * somewhere less legible.
 *
 * <p><b>Row-local fields only.</b> {@code DrugReference.getId()} and {@code substanceKey()} are
 * resolved per substance-name FAMILY across the whole dataset — {@code DdiDrugReferenceSource
 * .substanceIds} is canonical for why — so a two-row slice and the 2283-row KB legitimately disagree
 * about them, and asserting one here would fail on a faithful slice.
 */
public class Issue338SliceProvenanceTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-issue338-allergy-cross-reactivity.json";

	/** The two rows the slice carries, by the name each is filed under. */
	private static final List<String> SLICED = Arrays.asList("Hydrocortisone", "Dexamethasone");

	@Test
	public void everyRowTheSliceCarriesIsTheShippedKnowledgeBasesOwn() throws Exception {
		List<DrugReference> slice = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		List<DrugReference> shipped = DrugReferenceTestSupport.shippedEntries();
		assertEquals(SLICED.size(), slice.size(),
				"the slice must carry these rows and no others, or this case guards less than it says");
		for (String name : SLICED) {
			DrugReference sliced = DrugReferenceTestSupport.row(slice, name);
			DrugReference original = DrugReferenceTestSupport.row(shipped, name);
			assertNotNull(sliced, "the slice must carry " + name);
			assertNotNull(original, "the shipped knowledge base must carry " + name);
			assertEquals(original.getName(), sliced.getName(), name + "'s name must be the KB's own");
			assertEquals(original.normalizedAtcCodes(), sliced.normalizedAtcCodes(),
					name + "'s ATC codes must be the KB's own — the level-4 subgroup these two share is "
							+ "what raises the finding ADR Decision 59 quotes");
			assertEquals(original.getAliases(), sliced.getAliases(),
					name + "'s aliases must be the KB's own");
			assertEquals(original.displayLabel(), sliced.displayLabel(),
					name + "'s rendered label must be the KB's own — it is what the injected record "
							+ "prints, and ADR Decision 59 quotes that record");
		}
	}
}
