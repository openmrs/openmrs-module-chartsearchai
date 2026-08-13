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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * How many duplicate-therapy chips an active order filed under SEVERAL ATC codes raises, and WHICH
 * of the shared subgroups the chip names (issue #171).
 *
 * <p><b>The two defects.</b> {@code DrugSafetyValidator.classRelationships} walked the patient's
 * active-order ATC codes and returned one sentence per code, and {@code addInteractionWarnings}
 * de-duplicated only the sentences that folded into an interaction rule. So:
 * <ol>
 *   <li>a partner filed under several codes raised one chip per SHARED subgroup, identical except
 *       for the class named — the demo dictionary's {@code Metronidazole} concept carries five
 *       {@code WHOATC} maps and shares three level-4 subgroups with tinidazole, with no rated KB row
 *       to fold into, so one clinical fact reached the clinician three times;</li>
 *   <li>when a rule DID fold, the surviving sentence was whichever code the concept dictionary
 *       happened to iterate first — the position-dependence issue #161/#166 removed from the
 *       allergy arm, still present here, and with the same consequence: a topical subgroup named as
 *       the reason for a systemic concern.</li>
 * </ol>
 *
 * <p>Both are fixed by the two arms sharing ONE decision — {@code sharedClass} over the partner's
 * whole code set — so this file pins the chip COUNT and the exact class named, through the real
 * {@link DrugSafetyValidator#validate} over verbatim rows of the shipped 19 MB KB. Since issues
 * #183/#184 that method takes the ARM as a parameter: the preference between candidates is still the
 * one decision, and the candidate sets are not, so the two arms may name different classes for one
 * pair. It is still one decision in one place, which is what fixes the two defects above.
 */
public class MultiCodeClassChipTest {

	/** Verbatim KB rows: metronidazole (five codes) with tinidazole, and the ketoprofen/ibuprofen pair
	 *  whose rated row lets a fold be observed. */
	private static final String FIXTURE = "chartsearchai-test/ddi-multicode-class-chip.json";

	/** Metronidazole's five {@code WHOATC} codes, in the ascending order the KB and the demo
	 *  dictionary both publish them in. */
	private static final Set<String> METRONIDAZOLE_CODES = DrugReferenceTestSupport
			.set("A01AB17", "D06BX01", "G01AF01", "J01XD01", "P01AB01");

	@Test
	public void theFixtureReallySharesThreeSubgroupsWithNothingToFoldInto() throws IOException {
		// The precondition: three shared subgroups (so a per-code walk emits three sentences) and no
		// rated row correlating the pair (so nothing folds them). Through the production accessors.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugReference metronidazole = service.lookupByToken("Metronidazole");
		assertNotNull(metronidazole, "the order must resolve to a metronidazole row");
		assertEquals(5, metronidazole.normalizedAtcCodes().size(),
				"metronidazole must carry five codes, was: " + metronidazole.normalizedAtcCodes());

		List<DrugReference> tinidazole = service.findByQuery("Is it safe to give tinidazole?");
		assertEquals(1, tinidazole.size(), "tinidazole must resolve one row, was: "
				+ DrugReferenceTestSupport.names(tinidazole));
		Set<String> shared = new TreeSet<String>(tinidazole.get(0).atcSubgroups());
		shared.retainAll(metronidazole.atcSubgroups());
		assertEquals("[G01AF, J01XD, P01AB]", shared.toString(),
				"the pair must share three level-4 subgroups");

		// …and the second half of this test's name, which it used to promise without checking. A rated
		// row correlating the pair would fold the sentences into one chip whatever classRelationships
		// did, so "three sentences before, one after" would stop being what the cases below measure.
		for (DrugReference.Interaction rule : tinidazole.get(0).getInteractions()) {
			assertNotEquals("metronidazole", DrugReference.normalizeName(rule.getToken()),
					"no rule may correlate the pair, or there is nothing to observe: "
							+ rule.getToken() + "/" + rule.getAtc());
		}
	}

	@Test
	public void oneChipPerPartnerSubstanceNotPerSharedCode() throws IOException {
		// Issue #171's first half. One partner, one relationship, one chip — and the class named is the
		// systemic subgroup chosen by the same rule the allergy arm uses (G01AF is a gynecological
		// anti-infective subgroup and loses to a systemic one; the tie between J01XD and P01AB, both
		// systemic and both true of these two nitroimidazoles, is broken alphabetically, which is issue
		// #168's tie-break and is deliberately unchanged here).
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is it safe to give tinidazole?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Metronidazole 400mg"), METRONIDAZOLE_CODES,
						null, null));

		assertEquals(1, warnings.size(), "three shared codes are one clinical fact, was: " + warnings);
		assertEquals("Tinidazole is in the same ATC class (J01XD) as active order Metronidazole"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void theClassNamedDoesNotDependOnTheOrdersCodeOrder() throws IOException {
		// The invariant behind the first half: the answer is a function of the two code SETS, not of the
		// order a dictionary's concept mappings happen to be iterated in. The same five codes, reversed.
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is it safe to give tinidazole?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Metronidazole 400mg"),
						DrugReferenceTestSupport.set("P01AB01", "J01XD01", "G01AF01", "D06BX01", "A01AB17"),
						null, null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Tinidazole is in the same ATC class (J01XD) as active order Metronidazole"
				+ " — possible duplicate therapy", warnings.get(0).getDetail(),
				"the same chip the ascending code order produces");
	}

	@Test
	public void aFoldedChipNamesTheSystemicSubgroupNotTheTopicalOne() throws IOException {
		// Issue #171's second half, on a pair the KB rates Moderate so the class sentence reaches the
		// clinician folded into the rule chip. Ketoprofen and ibuprofen share M01AE (propionic-acid
		// derivatives, the subgroup that relates them) and M02AA (topical products for joint and
		// muscular pain); with the order's codes iterated topical-first, a walk that names the code it
		// reached first justifies a systemic NSAID concern with a rub-on formulation.
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is it safe to give ketoprofen?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Ibuprofen 400mg"),
						DrugReferenceTestSupport.set("M02AA13", "M01AE01"), null, null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Ketoprofen interacts with active order ibuprofen — Moderate. Concomitant use of"
				+ " more than one nonsteroidal anti-inflammatory drug (NSAID) at a time may increase the"
				+ " potential for serious gastrointestinal toxicity including inflammation, bleeding,"
				+ " ulceration, and perforation of the esophagus, stomach, or intestines. These events"
				+ " can occur at any time during NSAID use, with or without warning symptoms. The risk"
				+ " is dependent on both dosage and duration of therapy. Ketoprofen is in the same ATC"
				+ " class (M01AE) as active order Ibuprofen — possible duplicate therapy",
				warnings.get(0).getDetail());
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
