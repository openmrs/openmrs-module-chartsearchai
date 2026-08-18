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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Both directions of matching an interaction token against an order name whose spelling carries
 * DIACRITICS (issue #129) — the mirror image of the over-match {@link DrugSafetyOrderNameMatchingTest}
 * pins. DDInter's tokens are unaccented RxNorm generics, so before the fold a patient ordered
 * {@code Budésonide} shared no substring with {@code budesonide} at the accented character and was
 * never checked against that drug's interaction rules at all — the safety net simply absent, with
 * nothing logged. Measured over the 3.7.1 demo dictionary (2531 drug and drug-concept names x the
 * full KB's 2093 rule tokens), folding recovers 78 matches the raw comparison misses, and 224 of
 * those 2531 names carry a diacritic.
 *
 * <ul>
 *   <li><b>Must now fire.</b> Every accented order name below is a real row in that dictionary —
 *       {@code Budésonide}, {@code Dexaméthasone}, {@code Héparine}, {@code glycérine},
 *       {@code Lévofloxacine} — and each is paired here with the unaccented spelling of the SAME
 *       product, also a real row, so the only difference between the case that fired and the case
 *       that did not is the diacritic.</li>
 *   <li><b>Must still not fire.</b> Folding WIDENS matching, so it can resurrect the nested-name
 *       over-match issue #86 removed: {@code glycérine} folds to {@code glycerine}, which is
 *       {@code glycerin} plus one inflectional letter, so it becomes matchable at the very moment
 *       {@code nitroglycérine} becomes a candidate for the same token. The left boundary is what has
 *       to keep rejecting the second, and the whole #128 kill set is re-asserted here for that
 *       reason — including its three cases whose long name is itself accented
 *       ({@code Nitroglycérine ~ glycerin}, {@code Budésonide ~ desonide},
 *       {@code Lévofloxacine/Ciprofloxacine ~ ofloxacin}), which are the ones folding could newly
 *       break.</li>
 *   <li><b>Every matcher the same name reaches.</b> The fold lives in the boundary matcher all of these
 *       arms go through ({@link DrugReference#containsBoundedToken} — since issue #260 the scan beneath
 *       it does no folding of its own, and that matcher is where these operands are prepared), so
 *       these also cover the arms where the accented name is not a rule token's haystack: the two
 *       that resolve an order's own reference entry through {@link DrugReference#matchesText} (the
 *       interaction screen, and — since issue #143 — the active-order contraindication arm, both
 *       through the one {@code activeOrderEntries} definition), and the chart reconciliation, where
 *       the accented name is the NEEDLE inside a rendered record. Moving the fold into
 *       {@link DrugReference#matchesOrderName} alone, or applying it to one side only, leaves the
 *       other tests green and fails those.</li>
 *   <li><b>Not tuned for misspellings.</b> {@code Lisoniazide} and {@code Sprironolactone} are typos
 *       in this same dictionary. They stay unmatched deliberately: accommodating them reopens the
 *       substring hazard from the other side, and a data-quality problem is not a matcher problem.
 *       Nothing here asserts them either way, so this class does not fossilize them.</li>
 * </ul>
 *
 * <p>Every scenario runs the real pipeline: a verbatim KB slice parsed by the real
 * {@link DdiDrugReferenceSource}, the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}, GP reads on their
 * no-context defaults (so the severity floor is the production {@code minor}, which filters the
 * {@code Unknown} rows this slice carries verbatim). Each "must not fire" is paired with the positive
 * that proves the rule it must not fire on is live, so nothing here can pass by resolving nothing.
 */
public class DrugSafetyDiacriticOrderNameTest {

	/**
	 * A verbatim slice of the full DDInter KB (2283 drugs / 295,184 rows): four subject drugs whose
	 * rule lists carry, between them, every token of the #128 kill set above the {@code minor} floor,
	 * plus the partners the accented names must resolve to. The 16-drug DDInter excerpt contains none
	 * of them, and {@code ddi-order-name-collisions.json} carries only the two collisions #128
	 * reproduced live — same reason {@code ddi-severity-floor-pair.json} exists.
	 */
	private static final String DIACRITIC_SLICE = "chartsearchai-test/ddi-diacritic-order-names.json";

	/** Quinapril carries 15 of this slice's rules, so one question drives most of the table. */
	private static final String QUINAPRIL_QUESTION = "Is it safe to start quinapril?";

	private static final String QUINAPRIL_ANSWER = "Quinapril could be started with monitoring.";

	private DrugSafetyValidator validator() throws IOException {
		return DrugReferenceTestSupport.validator(diacriticService());
	}

	/** The slice behind a service, through the shared DDInter fixture loader. */
	private static DrugReferenceService diacriticService() throws IOException {
		return DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(DIACRITIC_SLICE));
	}

	/**
	 * The context shape the production builder assembles for the live probe patients: active-order
	 * display names, no ATC codes. Deliberately no ATC — the name arm is what diacritics break, and an
	 * ATC-fed context would let the class arm mask it.
	 */
	private PatientClinicalContext onOrders(String... orderNames) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(orderNames), null,
				null, null);
	}

	private long interactionCount(List<SafetyWarning> warnings) {
		return warnings.stream().filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())).count();
	}

	@Test
	public void anAccentedOrderNameIsStillCheckedForInteractions() throws IOException {
		// {accented order name, unaccented spelling of the same product, the token both must match}.
		// Every name is a real row in the 3.7.1 demo dictionary, which is bilingual by construction;
		// the unaccented one is the precondition — it proves the rule is live and the accent is the
		// only difference. Three of the accented forms are also INFLECTED ("héparine", "glycérine",
		// "lévofloxacine" = the INN stem, an accent and a trailing -e), so they exercise the fold and
		// #128's bounded tail together rather than one at a time.
		String[][] cases = {
				{ "Budésonide", "Budesonide & Formoterol", "budesonide" },
				{ "Dexaméthasone", "Dexamethasone Injection vial 8mg", "dexamethasone" },
				{ "Héparine", "Heparin sodium", "heparin" },
				{ "glycérine", "Glycerin", "glycerin" },
				{ "Lévofloxacine", "Levofloxacin", "levofloxacin" } };
		DrugSafetyValidator validator = validator();
		for (String[] c : cases) {
			List<SafetyWarning> unaccented = validator.validate(QUINAPRIL_ANSWER, QUINAPRIL_QUESTION,
					onOrders(c[1]));
			assertTrue(DrugReferenceTestSupport.detailContains(unaccented,
					SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order " + c[2]),
					"precondition: the unaccented spelling \"" + c[1] + "\" must raise the real quinapril x "
							+ c[2] + " rule, else the accented case proves nothing, was: " + unaccented);

			List<SafetyWarning> accented = validator.validate(QUINAPRIL_ANSWER, QUINAPRIL_QUESTION,
					onOrders(c[0]));
			assertTrue(DrugReferenceTestSupport.detailContains(accented, SafetyWarning.TYPE_INTERACTION,
					"Quinapril", "active order " + c[2]),
					"an order named \"" + c[0] + "\" must be checked against " + c[2]
							+ "'s interaction rules — diacritics are a spelling of the drug, not a "
							+ "different drug, was: " + accented);
		}
	}

	@Test
	public void theNestedNameOverMatchStaysDeadForAccentedNames() throws IOException {
		// The kill direction, on the three names where folding is what newly makes the longer name a
		// candidate for the nested token. Each asserts BOTH halves in one context: the drug the
		// patient is actually on raises its chip, and the drug nested inside its name does not.
		DrugSafetyValidator validator = validator();

		// "nitroglycérine" folds to "nitroglycerine": glycerin + one letter, i.e. exactly the tail
		// #128's allowance tolerates, so only the LEFT boundary ("nitro|glycerin") separates it from
		// the "glycérine" case above, which must match. Both are real dictionary rows.
		List<SafetyWarning> onNitroglycerine = validator.validate(QUINAPRIL_ANSWER, QUINAPRIL_QUESTION,
				onOrders("Nitroglycérine"));
		assertTrue(DrugReferenceTestSupport.detailContains(onNitroglycerine,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order nitroglycerin"),
				"precondition: the folded name must raise its OWN rule, else the absence below proves "
						+ "nothing, was: " + onNitroglycerine);
		assertFalse(DrugReferenceTestSupport.detailContains(onNitroglycerine,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order glycerin"),
				"a nitroglycerin order must not be reported as an active glycerin order once the accent "
						+ "is folded away, was: " + onNitroglycerine);

		// "lévofloxacine" folds to "levofloxacine", which carries "ofloxacin" inside a word
		// ("lev|ofloxacin") — the fluoroquinolone case from issue #129's own comment.
		List<SafetyWarning> onLevofloxacine = validator.validate(QUINAPRIL_ANSWER, QUINAPRIL_QUESTION,
				onOrders("Lévofloxacine"));
		assertTrue(DrugReferenceTestSupport.detailContains(onLevofloxacine,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order levofloxacin"),
				"precondition: the folded name must raise its OWN rule, was: " + onLevofloxacine);
		assertFalse(DrugReferenceTestSupport.detailContains(onLevofloxacine,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order ofloxacin"),
				"a levofloxacin order must not be reported as an active ofloxacin order, was: "
						+ onLevofloxacine);

		// A multi-word accented row, so the nested token is not at the head of the name either:
		// "furosémide et spironolactone" folds to "...spironolactone", which carries "iron"
		// ("sp|iron|olactone") — the second of #128's two live-reproduced fabrications.
		List<SafetyWarning> onFurosemideSpironolactone = validator.validate(QUINAPRIL_ANSWER,
				QUINAPRIL_QUESTION, onOrders("Furosémide et spironolactone"));
		assertTrue(DrugReferenceTestSupport.detailContains(onFurosemideSpironolactone,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order spironolactone", "Major"),
				"precondition: the folded name must raise its own Major spironolactone rule, was: "
						+ onFurosemideSpironolactone);
		assertFalse(DrugReferenceTestSupport.detailContains(onFurosemideSpironolactone,
				SafetyWarning.TYPE_INTERACTION, "Quinapril", "active order iron"),
				"a spironolactone order must not be reported as an active iron order once the accent is "
						+ "folded away, was: " + onFurosemideSpironolactone);

		// "budésonide" folds to "budesonide", which carries "desonide" ("bud|esonide"). Metformin is
		// the subject because it is metformin — not quinapril — whose rule list carries desonide
		// (Minor, the topical-corticosteroid-versus-antidiabetic row).
		String metforminQuestion = "Is it safe to continue her metformin?";
		String metforminAnswer = "Metformin could be continued.";
		List<SafetyWarning> onDesonide = validator.validate(metforminAnswer, metforminQuestion,
				onOrders("Desonide 0.05% cream"));
		assertTrue(DrugReferenceTestSupport.detailContains(onDesonide, SafetyWarning.TYPE_INTERACTION,
				"Metformin", "active order desonide", "Minor"),
				"precondition: a patient actually on desonide must get the real metformin x desonide "
						+ "chip, else the absence below proves nothing, was: " + onDesonide);
		List<SafetyWarning> onBudesonide = validator.validate(metforminAnswer, metforminQuestion,
				onOrders("Budésonide"));
		assertFalse(DrugReferenceTestSupport.detailContains(onBudesonide, SafetyWarning.TYPE_INTERACTION,
				"Metformin", "active order desonide"),
				"a budesonide order must not be reported as an active desonide order once the accent is "
						+ "folded away, was: " + onBudesonide);
	}

	@Test
	public void theIssue86KillSetStillDoesNotFire() throws IOException {
		// The whole kill set #128 measured, re-asserted here so this widening is pinned against
		// reintroducing the over-match. Each row is {subject drug, question, order name that CONTAINS
		// the token, the nested token, an order name the token legitimately names}. The last column is
		// the precondition: it proves the rule is live in this slice, so the assertion above it cannot
		// pass by resolving nothing. Order names are dictionary rows where the dictionary has one; the
		// nested drugs' own names come from the KB rows themselves (the demo dictionary carries no
		// opium, desonide, oxacillin, chlorothiazide, urea or hydroxyurea row).
		String[][] rows = {
				{ "Quinapril", QUINAPRIL_QUESTION, "Tiotropium", "opium", "Opium tincture" },
				{ "Quinapril", QUINAPRIL_QUESTION, "Spironolactone", "iron", "Iron IR 325mg" },
				{ "Quinapril", QUINAPRIL_QUESTION, "Nitroglycérine", "glycerin", "Glycerin" },
				{ "Quinapril", QUINAPRIL_QUESTION, "Hydrochlorothiazide 50mg", "chlorothiazide",
						"Chlorothiazide" },
				{ "Quinapril", QUINAPRIL_QUESTION, "Ciprofloxacine", "ofloxacin", "Ofloxacin" },
				{ "Quinapril", QUINAPRIL_QUESTION, "P-aminosalicylic acid", "salicylic acid",
						"Salicylic acid" },
				{ "Metformin", "Is it safe to continue her metformin?", "Budésonide", "desonide",
						"Desonide 0.05% cream" },
				{ "Methotrexate", "Can I give methotrexate?", "Cloxacilline Co 500mg", "oxacillin",
						"Oxacillin" },
				{ "Lactulose", "Is it safe to give lactulose?", "Hydroxyurea 500mg", "urea", "Urea" } };
		DrugSafetyValidator validator = validator();
		for (String[] row : rows) {
			String subject = row[0];
			String answer = subject + " could be given.";
			List<SafetyWarning> onNestingName = validator.validate(answer, row[1], onOrders(row[2]));
			assertFalse(DrugReferenceTestSupport.detailContains(onNestingName,
					SafetyWarning.TYPE_INTERACTION, subject, "active order " + row[3]),
					"\"" + row[2] + "\" must not be reported as an active " + row[3] + " order, was: "
							+ onNestingName);

			List<SafetyWarning> onNestedDrug = validator.validate(answer, row[1], onOrders(row[4]));
			assertTrue(DrugReferenceTestSupport.detailContains(onNestedDrug,
					SafetyWarning.TYPE_INTERACTION, subject, "active order " + row[3]),
					"precondition: a patient actually on " + row[3] + " must get the real " + subject
							+ " x " + row[3] + " chip, else the assertion above proves nothing, was: "
							+ onNestedDrug);
		}
	}

	@Test
	public void anAccentedOrderNameIsStillSubstantiatedByAnUnaccentedChartRecord() throws IOException {
		// The third call site of the same boundary scan, and the reason the fold is applied to BOTH
		// operands rather than to the haystack alone: PatientClinicalContext.ActiveDrugOrder.namedIn
		// looks for the patient's own order name INSIDE a rendered chart record, so here the accented
		// string is the NEEDLE. The divergence staged below is reachable, not hypothetical — querystore
		// indexed the drug-order record in one locale while the safety layer read the order in another,
		// and the same order really does read "Dexamethasone" in en and "Dexaméthasone" in fr on the
		// 3.7.1 standalone. What a missed match costs here is not a missing chip but a spurious
		// record: the reconciliation would call a substantiated order unrepresented and inject a
		// second citable line for the one prescription (issue #118).
		DrugReferenceService service = diacriticService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Dexaméthasone"), null, null, null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder(
						"11111111-aaaa-bbbb-cccc-222222222222", "Dexaméthasone")));
		PatientChart chart = DrugReferenceTestSupport.chartOf(DrugReferenceTestSupport.drugOrderRecord(
				1, "a-different-order-uuid", "Dexamethasone. Dose: 4 Milligram Oral Once daily"));

		PatientChart result = injector.injectRecords(chart, context, "what are her active medications?");

		long injectedOrders = result.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER
						.equals(m.getResourceType()))
				.count();
		assertEquals(0, injectedOrders,
				"the chart record names this order in the other locale's spelling, so it substantiates "
						+ "it and nothing may be injected: " + result.getText());
	}

	@Test
	public void accentedOrderNamesAreScreenedAgainstEachOther() throws IOException {
		// The second matcher the same accented name reaches, and the reason the fold belongs to the
		// shared boundary scan rather than to matchesOrderName alone: when the question names no drug,
		// DrugSafetyValidator.activeOrderEntries resolves the SUBJECTS of the screen by running each
		// active-order name through findByQuery -> DrugReference.matchesText, where the order name is
		// the haystack and the unaccented alias is the needle. Folding only the order-name matcher
		// would leave a francophone patient's whole medication list invisible to the screen — the same
		// absent safety net, one arm over. Since issue #143 the same resolution also supplies the
		// subjects of the active-order contraindication arm, so this one fold covers both.
		DrugSafetyValidator validator = validator();
		String screeningQuestion = "Are there any interactions between her medications?";

		List<SafetyWarning> unaccented = validator.validate("", screeningQuestion,
				onOrders("Dexamethasone Injection vial 8mg", "Levofloxacin"));
		assertEquals(1, interactionCount(unaccented),
				"precondition: the unaccented spellings must raise exactly one chip for the real "
						+ "dexamethasone x levofloxacin Major pair, was: " + unaccented);
		assertTrue(DrugReferenceTestSupport.detailContains(unaccented, SafetyWarning.TYPE_INTERACTION,
				"Dexamethasone", "active order levofloxacin", "Major"),
				"precondition: that chip is the tendon-rupture pair, was: " + unaccented);

		List<SafetyWarning> accented = validator.validate("", screeningQuestion,
				onOrders("Dexaméthasone", "Lévofloxacine"));
		assertEquals(1, interactionCount(accented),
				"the same two drugs, spelled as this dictionary spells them in French, must raise the "
						+ "same single chip, was: " + accented);
		assertTrue(DrugReferenceTestSupport.detailContains(accented, SafetyWarning.TYPE_INTERACTION,
				"Dexamethasone", "active order levofloxacin", "Major"),
				"the screened pair must be the same Major tendon-rupture pair, was: " + accented);
	}
}
