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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Whether the duplicate-therapy arm can report a drug as duplicating the patient's own order of that
 * same drug — issue #185.
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.classRelationships} skipped an active order that
 * is "the same drug as {@code ref}" by asking whether the two share an exact ATC code. Sharing a
 * level-5 code does mean sharing a substance, so the test never fired wrongly — but it is only
 * SUFFICIENT, and the arm needed a NECESSARY one. Where the co-medication's code and the reference
 * row's code are different codes of one substance, the sets are disjoint, their level-4 subgroups
 * still meet, and the chip fires against the order itself:
 *
 * <pre>Omeprazole is in the same ATC class (A02BC) as active order Omeprazole 20mg</pre>
 *
 * <p>Identity is what the skip is actually about, so it asks
 * {@link DrugReference#substanceGroupKey()} — the same correction issues #164/#187 made for the two
 * cross-reactivity routes to the same symptom, and the reason issue #173 built that key.
 *
 * <p><b>Two routes in, and they need different halves of the answer.</b> Both are pinned here, over
 * verbatim rows of the shipped 19 MB KB.
 * <ul>
 *   <li>The co-medication resolves to NO entry — the KB carries no row for its code — so only the
 *       ORDER'S NAME can say what it is. That is the PPI slice: the KB's {@code Omeprazole} row
 *       publishes {@code A02BC05} (esomeprazole's code, with {@code rxnorm_name} "esomeprazole") and
 *       omeprazole's own {@code A02BC01} appears nowhere in the KB.</li>
 *   <li>The co-medication DOES resolve, to a different substance, and the order's name names
 *       {@code ref} anyway — a combination. That is the isoniazid slice, and it is the one that
 *       makes the order-name leg unconditional rather than a fallback for the first route.</li>
 * </ul>
 *
 * <p><b>What must not move.</b> A duplicate-therapy chip against a DIFFERENT substance in the same
 * class is the finding this arm exists to make, so widening the skip into a blanket "the patient is
 * already on something in this class" suppression would be a worse defect than the one being fixed.
 * {@code Esomeprazole} is the case that catches it: it shares the {@code Omeprazole} row's
 * {@code rxnorm_name}, its {@code rxcui} and its one ATC code, and differs only in
 * {@code drugbank_id} — so it is a different substance by {@link DrugReference#substanceKey()} and
 * by nothing else the row publishes. Its chip must survive.
 *
 * <p>The second thing that must not move is WHICH accessor reads the order's name. It decides which
 * chips are silenced, so it is the ranked {@link DrugReferenceService#findImpliedByDrugName} and
 * never the matcher underneath it — measured here on issue #209's own case, where the unranked form
 * would suppress a real chip about {@code Hydrocortisone butyrate}. Swapping the two leaves the rest
 * of the suite green, which is why that case is in this file.
 */
public class DuplicateTherapySelfChipTest {

	/** Verbatim KB rows: the three A02BC substances and the isoniazid/rifapentine pair. See the
	 *  fixture's own {@code metadata.note}, which is the authority on what it carries. */
	private static final String FIXTURE = "chartsearchai-test/ddi-duplicate-therapy-self.json";

	/** Omeprazole's own WHO ATC code — what a dictionary that maps the concept correctly supplies, and
	 *  absent from the whole shipped KB. */
	private static final String OMEPRAZOLE_ORDER_CODE = "A02BC01";

	/** The code the KB's {@code Omeprazole} row actually publishes, which is esomeprazole's. */
	private static final String ESOMEPRAZOLE_CODE = "A02BC05";

	/** The two codes the 3.7.1 demo dictionary maps its {@code Isoniazid / Rifapentine} concept to:
	 *  rifapentine's own, and the combination code, which the KB does not carry. */
	private static final Set<String> ISONIAZID_RIFAPENTINE_ORDER_CODES =
			DrugReferenceTestSupport.set("J04AB05", "J04AC51");

	/** Sarah Taylor's order name, verbatim from issue #209 — the recorded name whose unranked matcher
	 *  reaches a second substance. */
	private static final String HYDROCORTISONE_ORDER_NAME = "Hydrocortisone Injection vial 100mg";

	/** A code in hydrocortisone's {@code H02AB} subgroup that no row of
	 *  {@code ddi-contra-route-variants} publishes. Its only job is to leave the partner on the ORDER
	 *  rung — the rung where the recorded NAME, and so the choice of accessor, decides. */
	private static final String HYDROCORTISONE_ORDER_CODE = "H02AB01";

	@Test
	public void theFixtureReallyFilesOmeprazoleUnderEsomeprazolesCode() throws IOException {
		// The premise every case below rests on, through the production accessors rather than by
		// reading the file: the two PPI rows are DIFFERENT substances that publish ONE code, and the
		// code an omeprazole order carries is in neither row. Regenerate this slice from a KB that has
		// repaired the row and the cases below go green whichever skip is in force.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);
		DrugReference omeprazole = DrugReferenceTestSupport.row(entries, "Omeprazole");
		DrugReference esomeprazole = DrugReferenceTestSupport.row(entries, "Esomeprazole");

		assertEquals(Collections.singleton(ESOMEPRAZOLE_CODE), omeprazole.normalizedAtcCodes(),
				"the Omeprazole row must publish esomeprazole's code and only that");
		assertEquals(omeprazole.normalizedAtcCodes(), esomeprazole.normalizedAtcCodes(),
				"and the two rows must publish the SAME code, or nothing is being borrowed");
		assertNotEquals(omeprazole.substanceKey(), esomeprazole.substanceKey(),
				"while remaining two substances, or the surviving esomeprazole chip below is wrong");
		assertEquals(omeprazole.atcSubgroups(), esomeprazole.atcSubgroups(),
				"in one level-4 subgroup, which is what the class arm compares");
		for (DrugReference entry : entries) {
			assertFalse(entry.normalizedAtcCodes().contains(OMEPRAZOLE_ORDER_CODE),
					"no row may carry " + OMEPRAZOLE_ORDER_CODE + ", or the order's code is nameable and"
							+ " the partner resolves by code: " + entry.getName());
		}
	}

	@Test
	public void aDrugIsNotReportedAsDuplicatingThePatientsOwnOrderOfIt() throws IOException {
		// Issue #185's headline, verbatim from the report. The order names omeprazole and carries
		// omeprazole's code; the KB's Omeprazole row carries esomeprazole's. Disjoint code sets, one
		// shared subgroup, and the chip claimed the drug duplicates itself.
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips("Is it safe to give omeprazole?", omeprazoleOrder())),
				"a drug the patient is already prescribed does not duplicate itself");
	}

	@Test
	public void aDifferentSubstanceSharingThatRowsCodeStillRaisesTheChip() throws IOException {
		// The narrowness of the fix, and the case that makes the emptiness above mean something: the
		// SAME order and the SAME class, asked about the one substance in the KB that shares the
		// Omeprazole row's rxnorm_name, rxcui and ATC code. Only substanceKey separates them, so a skip
		// keyed on the substance NAME, on the shared code, or on "the patient is on something in this
		// class" all lose this chip.
		List<SafetyWarning> warnings = chips("Is it safe to give esomeprazole?", omeprazoleOrder());

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Esomeprazole is in the same ATC class (A02BC) as active order Omeprazole 20mg"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void anUnrelatedSubstanceInTheSameClassIsUntouched() throws IOException {
		// The plain control: a third A02BC substance, filed under its own code, against the same order.
		List<SafetyWarning> warnings = chips("Is it safe to give pantoprazole?", omeprazoleOrder());

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Pantoprazole is in the same ATC class (A02BC) as active order Omeprazole 20mg"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void theSkipIsPerPartnerSoASecondOrderInTheSameClassKeepsItsChip() throws IOException {
		// The skip silences the partner that IS the drug and no other, which only a patient on TWO
		// class-related drugs can show. A clinician asking about omeprazole for a patient already on
		// omeprazole AND pantoprazole must still be told about the pantoprazole — that is the whole
		// point of the arm. Attaching each order's substances to every partner rather than to the
		// partners that order produced would lose exactly this chip, and no other case in the suite
		// notices: every other two-order fixture supplies the flattened code set alone, which carries
		// no order to attribute anything to.
		Set<String> pantoprazoleCodes = DrugReferenceTestSupport.set("A02BC02");
		Set<String> pantoprazoleNames = DrugReferenceTestSupport.set("Pantoprazole 40mg");
		Set<String> omeprazoleCodes = DrugReferenceTestSupport.set(OMEPRAZOLE_ORDER_CODE);
		Set<String> omeprazoleNames = DrugReferenceTestSupport.set("Omeprazole 20mg");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Omeprazole 20mg", "Pantoprazole 40mg"),
				DrugReferenceTestSupport.set(OMEPRAZOLE_ORDER_CODE, "A02BC02"), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-uuid-185d", "Omeprazole 20mg",
								omeprazoleNames, omeprazoleCodes),
						DrugReferenceTestSupport.activeOrder("order-uuid-185e", "Pantoprazole 40mg",
								pantoprazoleNames, pantoprazoleCodes)));

		List<SafetyWarning> warnings = chips("Is it safe to give omeprazole?", context);

		assertEquals(1, warnings.size(), "the omeprazole order is silenced and the pantoprazole one is"
				+ " not, was: " + warnings);
		assertEquals("Omeprazole is in the same ATC class (A02BC) as active order Pantoprazole"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}

	@Test
	public void aCombinationOrderNamesItsConstituentEvenWhenItsCodeNamesTheOtherHalf() throws IOException {
		// The second route, and the one reachable on the 3.7.1 demo dictionary as it ships: its
		// Isoniazid / Rifapentine concept maps to J04AB05 and J04AC51, so the partner RESOLVES — by
		// J04AB05, to Rifapentine — and a skip that reads only the resolved entry still reports the
		// isoniazid in the tablet as duplicating the tablet. What names isoniazid is the ORDER'S NAME,
		// which is why that leg is asked of every partner and not only of one the dataset could not
		// name.
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(
						chips("Is it safe to give isoniazid?", isoniazidRifapentineOrder())),
				"a constituent of the patient's own combination order does not duplicate it");
	}

	@Test
	public void theHalfThatOrderResolvesByCodeIsStillSkipped() throws IOException {
		// The same combination order, asked about the half its CODE names. Unchanged by this fix — the
		// exact-code leg already covered it — and here so that the case above cannot be satisfied by a
		// change that silenced this arm for combination orders altogether.
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(
						chips("Is it safe to give rifapentine?", isoniazidRifapentineOrder())),
				"the substance the order's own code names is restating existing therapy too");
	}

	@Test
	public void anOrderKnownOnlyByACodeTheRowItselfPublishesIsStillSkipped() throws IOException {
		// Why the exact-code leg stays rather than being replaced. With only the flattened code set
		// (issue #118's fallback) there is no order to name, so the partner is whatever entry publishes
		// A02BC05 — canonicalRow picks Omeprazole, the earlier row — and the substance the partner
		// resolves to is therefore NOT esomeprazole's. Only the code leg skips the esomeprazole
		// question here, so dropping it would raise a chip this arm has never raised.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null,
				DrugReferenceTestSupport.set(ESOMEPRAZOLE_CODE), null, null);

		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips("Is it safe to give omeprazole?", context)));
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.details(chips("Is it safe to give esomeprazole?", context)),
				"the exact-code leg, which the substance comparison does not subsume");

		// …and the arm is demonstrably live on that context, so the two emptinesses above are not the
		// arrangement failing to reach it.
		List<SafetyWarning> pantoprazole = chips("Is it safe to give pantoprazole?", context);
		assertEquals(1, pantoprazole.size(), "was: " + pantoprazole);
		assertEquals("Pantoprazole is in the same ATC class (A02BC) as active order Omeprazole"
				+ " — possible duplicate therapy", pantoprazole.get(0).getDetail());
	}

	@Test
	public void withNoOrderToNameItABareCodeStillCarriesTheSelfChip() throws IOException {
		// The bound, stated rather than left to be rediscovered: the skip sees only what the loaded
		// dataset can NAME. A context carrying only the flattened ATC set has said nothing about which
		// order contributed which code, so a code the dataset cannot name has no name to resolve
		// either — the same place issue #155's label ladder stops, and for the same reason. Not
		// closable here: with no order identity there is nothing to attribute a name TO. Closing it
		// means giving such an order a display in PatientClinicalContextBuilder so it reaches
		// getActiveDrugOrders() at all.
		//
		// The same bound with an order present is an order whose display name the dataset carries no
		// alias for AND whose code it does not carry either — a brand-only name such as
		// "Prilosec 20mg" mapped to A02BC01. Nothing then links the order to omeprazole, so the chip
		// stands there too. That one is a dataset-coverage limit rather than a resolution rule, which
		// is why it is recorded here and not fixed here.
		List<SafetyWarning> warnings = chips("Is it safe to give omeprazole?",
				DrugReferenceTestSupport.ctx(60, null, null,
						DrugReferenceTestSupport.set(OMEPRAZOLE_ORDER_CODE), null, null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertTrue(warnings.get(0).getDetail().contains("as active order " + OMEPRAZOLE_ORDER_CODE),
				"was: " + warnings.get(0).getDetail());
	}

	@Test
	public void theOrderNameIsReadByTheRANKEDAccessorSoANearNameKeepsItsChip() throws IOException {
		// WHICH accessor reads the order's name decides which chips are SILENCED, so it is the ranked
		// one. Issue #209's own measured case, on the fixture that carries it: over
		// ddi-contra-route-variants, findByDrugName("Hydrocortisone Injection vial 100mg") admits four
		// rows including Hydrocortisone butyrate, while findImpliedByDrugName admits the three
		// hydrocortisone rows and not the ester. The ester is a genuinely different substance, so its
		// duplicate-therapy chip against a hydrocortisone order is a real finding — and it is exactly
		// what the unranked matcher would suppress. The premise is asserted below rather than assumed,
		// because if the two accessors ever agreed on this name the case would pass either way.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS);
		assertTrue(DrugReferenceTestSupport.names(service.findByDrugName(HYDROCORTISONE_ORDER_NAME))
				.contains("Hydrocortisone butyrate"),
				"the unranked matcher must reach the ester, or this case discriminates nothing");
		assertFalse(DrugReferenceTestSupport.names(service.findImpliedByDrugName(HYDROCORTISONE_ORDER_NAME))
				.contains("Hydrocortisone butyrate"),
				"and the ranked one must not");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give hydrocortisone butyrate?", hydrocortisoneOrder());

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Hydrocortisone butyrate is in the same ATC class (H02AB) as active order"
				+ " Hydrocortisone Injection vial 100mg — possible duplicate therapy",
				warnings.get(0).getDetail());
	}

	@Test
	public void andTheSubstanceThatOrderReallyIsIsStillSkipped() throws IOException {
		// The other half of the pair above, and what makes its single chip mean something: the same
		// order, the same class, asked about the substance the order actually IS. The order's code is
		// one the dataset does not carry, so nothing but the name can reach this — it is the omeprazole
		// case again, on a second family and through a different fixture.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.ddiFixtureService(DrugReferenceTestSupport.DDI_CONTRA_ROUTE_VARIANTS))
				.validate("", "Is it safe to give hydrocortisone?", hydrocortisoneOrder());

		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport.details(warnings),
				"the order's own substance does not duplicate the order");
	}

	/** An active order for omeprazole as a mapped dictionary presents it: the display name, and
	 *  omeprazole's own ATC code — which is the code the KB's Omeprazole row does not carry. */
	private static PatientClinicalContext omeprazoleOrder() {
		Set<String> codes = DrugReferenceTestSupport.set(OMEPRAZOLE_ORDER_CODE);
		Set<String> names = DrugReferenceTestSupport.set("Omeprazole 20mg");
		return DrugReferenceTestSupport.ctx(60, null, names, codes, null, null, Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-uuid-185", "Omeprazole 20mg", names, codes)));
	}

	/** An active order for the demo dictionary's own {@code Isoniazid / Rifapentine} concept, with the
	 *  two {@code WHOATC} codes it maps to. */
	private static PatientClinicalContext isoniazidRifapentineOrder() {
		Set<String> names = DrugReferenceTestSupport.set("Isoniazid / Rifapentine");
		return DrugReferenceTestSupport.ctx(60, null, names, ISONIAZID_RIFAPENTINE_ORDER_CODES, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-185b",
						"Isoniazid / Rifapentine", names, ISONIAZID_RIFAPENTINE_ORDER_CODES)));
	}

	/** An active order for Sarah Taylor's hydrocortisone injection, mapped to a same-class code the
	 *  fixture does not carry — see {@link #HYDROCORTISONE_ORDER_CODE}. */
	private static PatientClinicalContext hydrocortisoneOrder() {
		Set<String> codes = DrugReferenceTestSupport.set(HYDROCORTISONE_ORDER_CODE);
		Set<String> names = DrugReferenceTestSupport.set(HYDROCORTISONE_ORDER_NAME);
		return DrugReferenceTestSupport.ctx(60, null, names, codes, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-185c",
						HYDROCORTISONE_ORDER_NAME, names, codes)));
	}

	private static List<SafetyWarning> chips(String question, PatientClinicalContext context)
			throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", question, context);
	}
}
