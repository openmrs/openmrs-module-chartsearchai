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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * One substance, one interaction chip per active order — however many rows the reference data files
 * that substance as (issue #162).
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.addInteractionWarnings} ran once per entry in the
 * drugs-in-play set and {@code bestRulePerPartner} groups per PARTNER within one subject, so the
 * SUBJECT side was never grouped. One clinician-facing word resolves every route variant of a
 * substance ({@code findByQuery} returns every entry whose aliases match), so each variant raised its
 * own chip against the same order. Measured live on the 3.7.1 standalone — Sarah Taylor, "Is it safe
 * to give hydrocortisone?", one diclofenac order — as
 * {@code Hydrocortisone x diclofenac} AND {@code Hydrocortisone (ophthalmic) x diclofenac}.
 *
 * <p><b>Why this is not a dedup.</b> The two rows carry DIFFERENT mechanism prose — one systemic, one
 * ophthalmic — so a survivor has to be chosen, and the chip's own subject label named a route the
 * clinician never asked about. Both halves are asserted here: which prose survives, and what the chip
 * calls the subject.
 *
 * <p>Slices taken verbatim from the shipped KB, driven through the real
 * {@link DdiDrugReferenceSource} parser and the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}.
 */
public class InteractionRouteVariantTest {

	/** Verbatim KB rows and interaction rows — see the fixture's own {@code metadata.note}. */
	private static final String FIXTURE = "chartsearchai-test/ddi-interaction-route-variants.json";

	/**
	 * The one slice in which DATASET ORDER and the canonical row disagree ON A FAMILY THAT CAN RAISE AN
	 * INTERACTION CHIP — Chloroprocaine, whose route-qualified row the KB lists first, given a partner
	 * whose rule sits on the unqualified row. Two other fixtures carry a not-first family and still
	 * cannot tell {@code interactionSubject}'s choice apart from "the first row in play": see the
	 * fixture's own {@code metadata.note} for which, and why neither raises a chip from it.
	 */
	private static final String NOT_FIRST_FIXTURE = "chartsearchai-test/ddi-canonical-subject-label.json";

	private static DrugSafetyValidator validator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}

	private static List<SafetyWarning> interactionChips(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())) {
				out.add(warning);
			}
		}
		return out;
	}

	@Test
	public void theFixtureReallyCarriesTheShapesUnderTest() throws IOException {
		// Preconditions on the FIXTURE's shape, taken through the unranked primitive — which is what
		// establishes that one alias is shared by several rows here. Deliberately not the accessor the
		// validator now uses (findImpliedByQuery, since issue #209): the ranked counterpart is asserted for
		// the PPI pair below, where the two differ. For the route-variant families they cannot differ, since
		// every row is the same substance and the verdict is taken per substance. Without these, a count
		// below could pass while one entry was in play, i.e. while asserting nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		List<DrugReference> hydrocortisone = service.findByQuery("Is it safe to give hydrocortisone?");
		assertEquals(3, hydrocortisone.size(), "one question word must resolve all three hydrocortisone "
				+ "rows, was: " + DrugReferenceTestSupport.names(hydrocortisone));

		List<DrugReference> ketorolac = service.findByQuery("Is it safe to give ketorolac?");
		assertEquals(2, ketorolac.size(), "and both ketorolac rows, was: "
				+ DrugReferenceTestSupport.names(ketorolac));

		List<DrugReference> oxymetazoline = service.findByQuery("Is it safe to give oxymetazoline?");
		assertEquals(3, oxymetazoline.size(), "and all three oxymetazoline rows, was: "
				+ DrugReferenceTestSupport.names(oxymetazoline));
		for (DrugReference row : oxymetazoline) {
			assertTrue(row.getName().endsWith(")"), "none of which is route-unspecified — that is what "
					+ "this family is here for, was: " + row.getName());
		}

		List<DrugReference> ppis = service.findByQuery("Is it safe to give esomeprazole?");
		assertEquals(2, ppis.size(), "one PPI word must MATCH both rows the KB files under one "
				+ "rxnorm_name, was: " + DrugReferenceTestSupport.names(ppis));
		assertEquals("[Esomeprazole]", DrugReferenceTestSupport
				.names(service.findImpliedByQuery("Is it safe to give esomeprazole?")).toString(),
				"while NAMING one of them — which is why the case below has to name both (issue #209)");
		assertEquals(DrugReference.normalizeName(ppis.get(0).getSubstanceName()),
				DrugReference.normalizeName(ppis.get(1).getSubstanceName()),
				"they must share ONE substance name, or the stem is not what keeps them apart");
		assertNotEquals(ppis.get(0).substanceKey(), ppis.get(1).substanceKey(),
				"while the combined key still separates them (issue #121)");
	}

	@Test
	public void routeVariantsOfTheSubjectRaiseOneChipNamingTheSubstance() throws IOException {
		// Sarah Taylor's live shape exactly. Three hydrocortisone rows are in play; two of them carry a
		// diclofenac rule, so before this fix the clinician saw two chips for one clinical fact — and one
		// of them named a route ("Hydrocortisone (ophthalmic)") against a systemic order.
		List<SafetyWarning> warnings = validator().validate("", "Is it safe to give hydrocortisone?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Diclofenac 50mg"), null, null, null));

		assertEquals(1, warnings.size(),
				"the rows of one substance are one chip against one order, was: " + warnings);
		assertEquals("Hydrocortisone", warnings.get(0).getDrug(),
				"and the chip names the SUBSTANCE, not one of its routes");
		assertEquals("Hydrocortisone interacts with active order diclofenac — Moderate. The combined use"
				+ " of corticosteroids and nonsteroidal anti-inflammatory drugs (NSAIDs) may increase the"
				+ " potential for serious gastrointestinal (GI) toxicity, including inflammation,"
				+ " bleeding, ulceration, and perforation.", warnings.get(0).getDetail(),
				"carrying the route-unspecified row's mechanism — the ophthalmic row's prose describes a"
						+ " presentation nobody asked about");
	}

	@Test
	public void theSurvivingChipIsNotDecidedByWhichNoteIsLonger() throws IOException {
		// The tie-break that grouping alone gets WRONG. Ketorolac's two rows rate lepirudin Moderate
		// apiece, and the OPHTHALMIC row's note is the longer one (495 characters against 265, verbatim
		// from the shipped KB and reproduced independently), so the pre-existing severity-then-longer-note
		// rule would hand the chip to a row whose prose is about eye drops. This case is the pin: the
		// note length cannot be what decides a route. How many (substance, partner) groups share the
		// shape is deliberately NOT stated — two measurement passes disagreed about the count while
		// agreeing about these two rows, so the rows are the evidence and the count was removed.
		List<SafetyWarning> warnings = validator().validate("", "Is it safe to give ketorolac?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Lepirudin 15mg"), null, null, null));

		assertEquals(1, warnings.size(), "one substance, one partner, one chip, was: " + warnings);
		assertEquals("Ketorolac interacts with active order lepirudin — Moderate. Theoretically, the"
				+ " coadministration of nonsteroidal anti-inflammatory drugs (NSAIDs) and thrombin"
				+ " inhibitors may potentiate the risk of bleeding. NSAIDs interfere with platelet"
				+ " adhesion and aggregation and may prolong bleeding time in healthy individuals.",
				warnings.get(0).getDetail(),
				"the route-unspecified row keeps the chip even though its note is the shorter one");
		assertFalse(warnings.get(0).getDetail().contains("topically administered"),
				"so the ophthalmic row's prose must not survive, was: " + warnings.get(0).getDetail());
	}

	@Test
	public void severityStillOutranksTheRoutePreference() throws IOException {
		// The other direction, and the residue this rule accepts. Lapatinib rates Sirolimus Moderate and
		// Sirolimus (protein-bound) MAJOR, so preferring the route-unspecified row FIRST would report
		// Moderate for a pair the KB rates Major — under-warning, which is the one direction a
		// non-blocking advisory must not take (the same call bestRulePerPartner already records for the
		// partner side). Severity leads; the accepted cost is that the surviving prose then describes the
		// protein-bound formulation.
		List<SafetyWarning> warnings = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport
						.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS))
				.validate("", "Is it safe to give sirolimus?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Lapatinib 250mg"), null, null, null));

		assertEquals(1, interactionChips(warnings).size(),
				"the two sirolimus rows are one subject, was: " + warnings);
		SafetyWarning chip = interactionChips(warnings).get(0);
		assertEquals("Sirolimus", chip.getDrug(),
				"named by the route-unspecified row whatever supplies the rating");
		assertTrue(chip.getDetail().startsWith("Sirolimus interacts with active order lapatinib — Major. "),
				"and the pair's most severe rating survives, was: " + chip.getDetail());
	}

	@Test
	public void aSubstanceWithNoRouteUnspecifiedRowStillRaisesOneChip() throws IOException {
		// 11 of the shipped KB's 121 multi-row families name no unqualified row at all (measured
		// 2026-08-06). The collapse must still be one chip for them — and the label is then the family's
		// first row, qualifier included, because the KB publishes no unqualified name to use instead and
		// building one by string surgery on a display name is the mistake issue #148 had to undo.
		List<SafetyWarning> warnings = validator().validate("", "Is it safe to give oxymetazoline?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Phenelzine 15mg"), null, null, null));

		assertEquals(1, warnings.size(),
				"three rows of one substance are still one chip, was: " + warnings);
		assertEquals("Oxymetazoline (nasal)", warnings.get(0).getDrug(),
				"named by the family's first row, since the KB gives this substance no unqualified name");
	}

	@Test
	public void twoDistinctSubstancesTheKbFilesUnderOneSubstanceNameStayTwoChips() throws IOException {
		// The must-NOT-collapse case on the subject side. Omeprazole and Esomeprazole share one
		// rxnorm_name, one RxCUI and one ATC code, and here they also share ONE MECHANISM GROUP against
		// kanamycin — so their two chips carry byte-identical notes. A key made of reference-data identity
		// alone merges them, and a dedup on rendered text would too; they are two substances, exactly as
		// enalapril and enalaprilat are (issue #121).
		//
		// The question NAMES BOTH, since issue #209. It used to name only esomeprazole and rely on that one
		// word putting both in play, which is the over-admission #209 removed — so the vehicle changed and
		// the property did not: each row is now admitted by its own display name. It does not DETECT
		// over-admission (an over-admitting resolver returns the same two rows here, measured); it stops the
		// case DEPENDING on it, and it gains the over-NARROWING direction, where dropping a PPI fails this.
		// See ContraindicationRouteVariantTest's sibling case for the same note in full.
		List<SafetyWarning> warnings = validator().validate("",
				"Is it safe to give omeprazole or esomeprazole?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Kanamycin 500mg"), null, null, null));

		assertEquals(2, warnings.size(),
				"two substances sharing one substance name keep their own chips, was: " + warnings);
		assertEquals("Omeprazole", warnings.get(0).getDrug());
		assertEquals("Esomeprazole", warnings.get(1).getDrug());
		assertEquals(warnings.get(0).getDetail().replace("Omeprazole", "X"),
				warnings.get(1).getDetail().replace("Esomeprazole", "X"),
				"and their notes really are the same string, so nothing but the subject key can tell the "
						+ "two chips apart");
	}

	@Test
	public void theChipIsNamedAfterTheCanonicalRowNotTheFirstRowInPlay() throws IOException {
		// The subject-LABEL half of issue #162 — the half the issue calls a correctness fix rather than a
		// de-duplication — asserted where it can actually fail. No fixture could tell the canonical row
		// apart from "the first row in play" before this one: reducing interactionSubject to
		// subjects.get(0) passed the whole api suite (measured 2026-08-06). 7 of the shipped KB's 121
		// multi-row families list a qualified row first, and Chloroprocaine is one, so this slice is where
		// dataset order gives the WRONG answer — see NOT_FIRST_FIXTURE for why the two fixtures that also
		// carry a not-first family cannot make that answer visible.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(NOT_FIRST_FIXTURE);
		List<DrugReference> rows = service.findByQuery("Is it safe to give chloroprocaine?");
		assertEquals(2, rows.size(), "precondition: one question word must resolve both chloroprocaine "
				+ "rows, was: " + DrugReferenceTestSupport.names(rows));
		assertEquals("Chloroprocaine (ophthalmic)", rows.get(0).getName(),
				"precondition: and the ROUTE-QUALIFIED row must come first, or this case cannot "
						+ "distinguish the canonical row from the dataset's first — was: "
						+ DrugReferenceTestSupport.names(rows));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give chloroprocaine?", DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Lorazepam 1mg"), null, null, null));

		List<SafetyWarning> chips = interactionChips(warnings);
		assertEquals(1, chips.size(), "the two rows are one subject and one chip, was: " + warnings);
		assertEquals("Chloroprocaine", chips.get(0).getDrug(),
				"named by the route-unspecified row even though the dataset lists the ophthalmic one "
						+ "first — a systemic order must not be told about an eye drop");
		// The note comes from the unqualified row, which is where lorazepam's rule sits; under a
		// first-wins label that same note would have been printed under "Chloroprocaine (ophthalmic)",
		// i.e. the chip-versus-prose divergence with the label rather than the prose wrong.
		assertEquals("Chloroprocaine interacts with active order lorazepam — Moderate. The concomitant use"
				+ " of local anesthetics and benzodiazepines may have additive CNS-depressant effects."
				+ " Both types of drugs are CNS depressants.",
				chips.get(0).getDetail(), "was: " + warnings);
	}

	@Test
	public void differentPartnersOfOneSubstanceKeepTheirOwnChips() throws IOException {
		// The collapse is per (substance, partner) and never per substance: two orders that interact with
		// hydrocortisone through different rows are two clinical facts. Ketorolac's rule sits on the
		// route-unspecified row and diclofenac's on it too, so this also pins that grouping the subject
		// does not merge its partners.
		List<SafetyWarning> warnings = validator().validate("", "Is it safe to give hydrocortisone?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Diclofenac 50mg", "Ketorolac 10mg"), null, null,
						null));

		assertEquals(2, warnings.size(), "one chip per partner, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Hydrocortisone", "active order diclofenac"), "was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Hydrocortisone", "active order ketorolac"), "was: " + warnings);
	}

	@Test
	public void eachCollapsedChipIsInjectedIntoThePromptExactlyOnce() throws IOException {
		// The other half of a chip (issue #110): every chip is injected as a numbered, citable
		// safety-finding record, so N duplicate chips were N near-identical records in the context window
		// too. Real injector wired to the real validator, so the record count follows the chips.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<?> findings = DrugReferenceTestSupport.injectedFindings(injector.injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Diclofenac 50mg"),
						null, null, null),
				"Is it safe to give hydrocortisone?"));

		assertEquals(1, findings.size(),
				"one chip is one citable record, not one per route variant, was: " + findings);
	}
}
