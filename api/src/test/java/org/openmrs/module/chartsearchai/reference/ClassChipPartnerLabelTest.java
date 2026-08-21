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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * What a class chip calls the ACTIVE ORDER it names — issue #155 (a raw ATC code where a drug name
 * belongs) and issue #174's site 1 (the label chosen by dataset order).
 *
 * <p><b>Issue #155.</b> {@code displayLabelForAtcCode} returned the code itself when the loaded
 * dataset carried no entry for it, so on {@code sourceFormat=json} — the bundled four-entry curated
 * seed, which carries no aspirin entry at all — Agnes Adams' chip read
 * {@code … as active order N02BA01}, and {@code N02BA01} is not a drug name. That was the DEFAULT when
 * #155 was filed, so it was reachable out of the box; since ADR Decision 36 the default knowledge base
 * does carry aspirin, which narrows the shape to a dataset that cannot name a code it was handed rather
 * than removing it.
 * The order itself carries a display name, and the chip is built from that order.
 *
 * <p><b>Issue #174 site 1.</b> {@code entryForAtcCode} returned the FIRST entry carrying the code
 * while every row of a substance publishes identical codes, so the label was whichever row the
 * dataset listed first. {@code Cyclosporine (ophthalmic)} precedes {@code Cyclosporine} in the
 * shipped KB and both publish {@code L04AD01}, so a systemic cyclosporine order was named as an
 * ophthalmic preparation.
 *
 * <p>Both are the same resolution: name the order by the substance the dataset knows, else by the
 * order's own display name, else — and only then — by the code. Driven through the real
 * {@link DrugSafetyValidator#validate}: the first case over the real bundled curated dataset (the
 * production default), the second over rows the real {@link DdiDrugReferenceSource} parses out of a
 * verbatim KB slice.
 */
public class ClassChipPartnerLabelTest {

	/** Verbatim KB rows: the cyclosporine family, whose route-qualified row is listed FIRST, and the
	 *  tacrolimus row that shares {@code L04AD} with it. */
	private static final String FIXTURE = "chartsearchai-test/ddi-class-partner-canonical-row.json";

	/** The three {@code WHOATC} codes the 3.7.1 demo dictionary maps an aspirin order's concept to. */
	private static final Set<String> ASPIRIN_ORDER_CODES = DrugReferenceTestSupport
			.set("A01AD05", "B01AC06", "N02BA01");

	/** The code a systemic cyclosporine order's concept maps to, and the one BOTH cyclosporine rows of
	 *  the fixture publish — which is what makes "the entry carrying this code" ambiguous at all. */
	private static final String CYCLOSPORINE_ORDER_CODE = "L04AD01";

	/** The curated seed's own aspirin rule as the RULE's own token words it, unrated and so exempt from
	 *  the severity floor, which is why these cases can carry it at all. Its ATC is {@code B01AC06} — one
	 *  of the aspirin order's three codes, but not the one the NSAID group matches.
	 *
	 *  <p>Read by ONE case now, and that is issue #292's scope rather than an accident. It used to be
	 *  shared with the folded case below, whose partner is named after the ORDER — and a folded chip
	 *  reconciles such a partner where the RULE's own token names that very order
	 *  ({@code DrugSafetyValidator.foldedPartnerLabel}), which {@code aspirin} does of an
	 *  {@code Aspirin 81mg} order. So that case now words its rule sentence with the order's display and
	 *  this constant is the unfolded wording only. */
	private static final String CURATED_ASPIRIN_RULE_SENTENCE =
			"Ibuprofen interacts with active order aspirin — additive GI and bleeding risk";

	@Test
	public void anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName() {
		// Issue #155, on the configuration it was measured on: the bundled curated seed's four entries
		// (ibuprofen, paracetamol, amoxicillin, gentamicin) carry no aspirin, so no entry can supply a
		// name and the curated NSAID group is what links the pair.
		//
		// One chip, not two: grouping an order's codes by the ORDER also correlates the two arms here,
		// because the rule cites B01AC06 while the class hit is under N02BA01 and both are that order's.
		// That is the residue addInteractionWarnings documented as needing the per-order codes; naming
		// the order and correlating it are the same resolution, so they arrived together.
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());

		List<SafetyWarning> warnings = validator.validate("", "Can I give ibuprofen?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						ASPIRIN_ORDER_CODES, null, null,
						Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-1", "Aspirin 81mg",
								DrugReferenceTestSupport.set("aspirin 81mg"), ASPIRIN_ORDER_CODES))));

		assertEquals(1, warnings.size(), "was: " + warnings);
		// ONE name for one prescription, which is issue #292's own spec change and this string is where it
		// shows: this assertion used to read the rule's token ("active order aspirin") beside the ladder's
		// order name ("active order Aspirin 81mg"), one prescription under two names in one detail, which
		// is the defect the ticket opens with. The ladder's name came from the ORDER here — the seed
		// carries none of the order's three codes — and an order is not a substance, so
		// DrugSafetyValidator.foldedPartnerLabel hands that name to the rule sentence only where the
		// RULE's own token names that very order. It does: token `aspirin` against an order whose names
		// are {aspirin 81mg}, the same predicate PatientClinicalContext.hasActiveDrug used to admit the
		// rule. Where it does not — a partner renamed after a DIFFERENT order, or one order carrying two
		// substances' codes — the two names stay, and FoldedChipOnePartnerNameTest pins both.
		assertEquals("Ibuprofen interacts with active order Aspirin 81mg — additive GI and bleeding"
				+ " risk. Ibuprofen is in the same cross-reactivity group (NSAID) as active order"
				+ " Aspirin 81mg — possible additive or duplicate-class therapy",
				warnings.get(0).getDetail());
	}

	@Test
	public void withNoIdentifiedOrderToNameTheCodeIsStillTheLastResort() {
		// The residue, pinned rather than left to be rediscovered: a caller that supplies only the
		// flattened ATC set (the fallback issue #118 deliberately kept) has said nothing about which
		// order contributed which code, so this arm has no name for the order and no way to see that
		// the rule's code and the class hit's code are one co-medication. The code is all there is to
		// print, and the two arms stay uncorrelated — exactly the case above, minus the order. The
		// ladder stops here; it does not fabricate a name.
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService());

		List<SafetyWarning> warnings = validator.validate("", "Can I give ibuprofen?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						ASPIRIN_ORDER_CODES, null, null));

		assertEquals(2, warnings.size(), "was: " + warnings);
		assertEquals(CURATED_ASPIRIN_RULE_SENTENCE, warnings.get(0).getDetail());
		assertEquals("Ibuprofen is in the same cross-reactivity group (NSAID) as active order N02BA01"
				+ " — possible additive or duplicate-class therapy", warnings.get(1).getDetail());
	}

	@Test
	public void theFixtureReallyListsTheRouteQualifiedRowFirst() throws IOException {
		// The precondition the issue #174 case rests on and could not otherwise state: first-wins and
		// canonicalRow only DISAGREE while the route-qualified row is listed ahead of the plain one and
		// both publish the same code. Regenerate this slice in a different row order and the case below
		// goes green whichever resolution is in force, and the regression walks back in unnoticed —
		// which is exactly the warning DrugReferenceTestSupport.DDI_ROUTE_VARIANTS carries for its own
		// slice. Through the real parser, so it characterises what the validator will actually load.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(FIXTURE);

		assertEquals(Arrays.asList("Tacrolimus", "Cyclosporine (ophthalmic)", "Cyclosporine"),
				DrugReferenceTestSupport.names(entries),
				"the route-qualified cyclosporine row must be listed FIRST");
		assertEquals(new TreeSet<String>(entries.get(2).normalizedAtcCodes()),
				new TreeSet<String>(entries.get(1).normalizedAtcCodes()),
				"and both rows must publish the same codes, or the code the order maps to picks a row "
						+ "on its own and there is nothing for canonicalRow to decide");
		assertTrue(entries.get(1).normalizedAtcCodes().contains(CYCLOSPORINE_ORDER_CODE),
				"including the one the order maps to");
	}

	@Test
	public void anOrderTheDatasetFilesAsSeveralRowsIsNamedBySubstance() throws IOException {
		// Issue #174 site 1. The class sentence rides inside the folded chip here, which is what a
		// systemic cyclosporine order actually produces: the KB rates the tacrolimus pair Major, so the
		// rule arm reaches it too. Both sentences now name the partner by the resolved entry — until
		// issue #292 the rule arm named it by the rule's own match token ("cyclosporine", lowercased by
		// the ddinter parser) while the class sentence named it by that entry, so this one chip called
		// one prescription two things. A first-wins resolution prints "Cyclosporine (ophthalmic)" there
		// — an ophthalmic preparation the chart does not record — which is what this case is about, and
		// it is now asserted on both sentences rather than one.
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));

		List<SafetyWarning> warnings = validator.validate("", "Is it safe to give tacrolimus?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Cyclosporine 100mg"),
						DrugReferenceTestSupport.set(CYCLOSPORINE_ORDER_CODE), null, null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals("Tacrolimus interacts with active order Cyclosporine — Major. Coadministration of"
				+ " tacrolimus and cyclosporine may increase the risk and severity of nephrotoxicity due"
				+ " to additive effects on the kidney. Clinical experience indicates that the combination"
				+ " is associated with increased renal toxicity as evidenced by increased serum"
				+ " creatinine and decreased glomerular filtration rate. In vitro and animal data also"
				+ " suggest that tacrolimus may inhibit the intestinal first-pass metabolism of"
				+ " cyclosporine via CYP450 3A4, resulting in significantly increased bioavailability of"
				+ " the latter. Tacrolimus is in the same ATC class (L04AD) as active order Cyclosporine"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}
}
