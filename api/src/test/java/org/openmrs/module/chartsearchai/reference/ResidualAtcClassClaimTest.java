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

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Whether a RESIDUAL ATC subgroup may license a clinical relationship claim (issue #167), in both
 * arms that make one.
 *
 * <p><b>The defect.</b> A shared level-4 subgroup was read as evidence of a pharmacological
 * relationship whatever the subgroup was. Measured live on the 3.7.1 standalone, in both arms:
 * <ul>
 *   <li>interaction arm — Agnes Adams on Aspirin 81mg, "Is it safe to give epinephrine?" →
 *       {@code Epinephrine is in the same ATC class (A01AD) as active order Acetylsalicylic acid
 *       (aspirin) — possible duplicate therapy}. {@code A01AD} is "Other agents for local oral
 *       treatment": aspirin is filed there as a mouth rinse and epinephrine as a dental
 *       haemostatic, and adrenaline and aspirin are not duplicate therapy.</li>
 *   <li>contraindication arm — {@code Potassium Iodide} and {@code Acetylcysteine} share only
 *       {@code S01XA} "Other ophthalmologicals" and {@code V03AB} "Antidotes", and the chip read
 *       {@code is in the same ATC class (V03AB) … — possible cross-reactivity}.</li>
 * </ul>
 *
 * <p><b>And the case that bounds the exclusion</b>, which is why "no residual bucket may ever
 * license a claim" is the wrong answer. A residual subgroup inherits whatever its containing group
 * asserts: {@code R06AX} is "Other antihistamines for systemic use", but its parent {@code R06A} is
 * "ANTIHISTAMINES FOR SYSTEMIC USE", so loratadine and desloratadine really are both antihistamines
 * and one really is duplicate therapy for the other. The last case here pins that chip, and it
 * passes before this issue's change as well as after — deliberately, because the risk in fixing
 * #167 is over-vetoing, not under-vetoing. See {@code DrugReference.isUnclassifyingAtcCode} for the
 * criterion and its measured KB impact, and {@code CrossReactivityClassChoiceTest}, whose
 * {@code J01GB} case ("Other aminoglycosides") is the same boundary in the allergy arm.
 *
 * <p>Driven through the real {@link DrugSafetyValidator#validate} entry point over rows the real
 * {@link DdiDrugReferenceSource} parses out of a verbatim slice of the shipped 19 MB KB.
 */
public class ResidualAtcClassClaimTest {

	/** Verbatim KB rows: the two residual-bucket pairs, and the antihistamine pair that bounds the
	 *  exclusion. */
	private static final String FIXTURE = "chartsearchai-test/ddi-residual-atc-bucket.json";

	@Test
	public void theFixtureReallySharesNothingButAResidualBucket() throws IOException {
		// The precondition both defect cases rest on, through the production accessor the arms compare
		// with: if either pair shared a subgroup that DOES classify the substances, dropping the residual
		// one would leave a chip and the cases below would pass while testing nothing.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);

		assertEquals("[A01AD]", shared(service, "epinephrine", "Acetylsalicylic acid").toString(),
				"aspirin and epinephrine must share the local-oral-treatment bucket and nothing else");
		assertEquals("[S01XA, V03AB]",
				shared(service, "acetylcysteine", "Potassium Iodide").toString(),
				"and potassium iodide and acetylcysteine only an ophthalmological and an antidote bucket");
		assertEquals("[R06AX]", shared(service, "desloratadine", "Loratadine").toString(),
				"while the antihistamine pair shares a residual subgroup whose PARENT group is "
						+ "'ANTIHISTAMINES FOR SYSTEMIC USE'");
	}

	@Test
	public void aLocalOralTreatmentBucketRaisesNoDuplicateTherapyChip() throws IOException {
		// Issue #167's interaction arm, as measured live (three chips there, one here, because issue
		// #162 has since collapsed epinephrine's three route rows onto one subject).
		List<SafetyWarning> warnings = fixtureValidator().validate("", "Is it safe to give epinephrine?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						DrugReferenceTestSupport.set("A01AD05", "B01AC06", "N02BA01"), null, null));

		assertEquals(0, warnings.size(),
				"a bucket ATC defines by exclusion inside a group it defines by administration site "
						+ "says nothing about either substance, so there is no chip to raise, was: "
						+ warnings);
	}

	@Test
	public void anAntidoteBucketRaisesNoCrossReactivityChip() throws IOException {
		// Issue #167's contraindication arm. V03AB "Antidotes" sits inside V03A, whose own ATC name is
		// "ALL OTHER THERAPEUTIC PRODUCTS", and its members are grouped by the poisoning each reverses;
		// two of them share no chemistry for an allergy to cross-react with.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give acetylcysteine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Potassium Iodide"), null));

		assertEquals(0, warnings.size(),
				"neither the ophthalmological nor the antidote bucket may justify a cross-reactivity "
						+ "claim, and no curated group covers the pair either, was: " + warnings);
	}

	@Test
	public void aResidualSubgroupInsideATherapeuticGroupStillChips() throws IOException {
		// The boundary. R06AX is as residual as A01AD in ATC's own wording, and this chip is right: the
		// two drugs are both H1 antihistamines because R06A says so, and prescribing one alongside the
		// other IS duplicate therapy. An exclusion that dropped this would remove 1331 of the shipped
		// KB's ROW pairs, and 1291 of its SUBSTANCE pairs, that name a residual subgroup under a
		// therapeutic group (measured — see DrugReference.isUnclassifyingAtcCode, which carries both
		// figures and the reason the row one read 1488 until issue #263, and
		// DrugSafetyValidator.sharedClass, which defines the two bases against each other), among them
		// every antihistamine, antidepressant and aminoglycoside pair.
		List<SafetyWarning> warnings = fixtureValidator().validate("",
				"Is it safe to give desloratadine?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Loratadine 10mg"),
						DrugReferenceTestSupport.set("R06AX13"), null, null));

		assertEquals(1, warnings.size(), "was: " + warnings);
		assertEquals(SafetyWarning.TYPE_INTERACTION, warnings.get(0).getType());
		assertEquals("Desloratadine is in the same ATC class (R06AX) as active order Loratadine"
				+ " — possible duplicate therapy", warnings.get(0).getDetail());
	}

	/** The subgroups the drug {@code question} names shares with the entry {@code other} resolves to,
	 *  sorted — through the production resolvers and the production accessor, as
	 *  {@code CrossReactivityClassChoiceTest} does it, so this characterises the fixture rather than
	 *  re-implementing the scan under test. */
	private static Set<String> shared(DrugReferenceService service, String question, String other)
			throws IOException {
		List<DrugReference> inPlay = service.findByQuery("Is it safe to give " + question + "?");
		assertTrue(!inPlay.isEmpty(), question + " must resolve at least one row");
		DrugReference otherEntry = service.lookupByToken(other);
		assertNotNull(otherEntry, other + " must resolve to an entry");
		Set<String> out = new TreeSet<String>(otherEntry.atcSubgroups());
		out.retainAll(inPlay.get(0).atcSubgroups());
		return out;
	}

	private static DrugSafetyValidator fixtureValidator() throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE));
	}
}
