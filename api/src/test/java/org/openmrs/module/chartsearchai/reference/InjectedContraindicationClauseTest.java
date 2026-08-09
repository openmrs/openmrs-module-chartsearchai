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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #190 item 1 — {@code DrugReferenceInjector.render}'s {@code Contraindicated with:} clause
 * rendered one clause per contraindication ROW while
 * {@code DrugSafetyValidator.ContraindicationChips} raises one chip per
 * {@code (substance, type, token)}, so the injected record and the chip beside it disagreed about how
 * many contraindications the entry has.
 *
 * <p>Curated-source-only by construction: the {@code ddinter} and {@code atc} sources publish no
 * contraindications at all (see {@link DdiDrugReferenceSource}'s class javadoc), so only an
 * operator-authored file can file one rule twice. The bundled seed does not — its four ibuprofen rows
 * are four distinct {@code (type, token)} pairs — which is why nothing shipped changes here and why
 * this needs a fixture.
 *
 * <p><b>Joining rather than dropping.</b> Issue #174 site 2 could drop a repeated row because the
 * repeats were near-identical; here the sibling notes differ in text, and they are operator-authored
 * clinical prose that this record is the only place the model ever sees. So the clause count follows
 * the chip while both notes survive inside the one clause.
 *
 * <p>Runs the REAL production path: the real {@link JsonDrugReferenceSource} parser over a fixture,
 * the real {@code injectRecords} and the real {@code validate}, GP reads on their no-context defaults.
 */
public class InjectedContraindicationClauseTest {

	/** Shared with {@code ContraindicationRouteVariantTest}, which asks the same question of the CHIP:
	 *  one curated {@code allergy}/{@code ibuprofen} rule authored twice, under two spellings and with
	 *  two different notes. */
	private static final String DUPLICATE_RULE_FIXTURE =
			"chartsearchai-test/drug-reference-duplicate-rule-tokens.json";

	private static final String QUESTION = "Is ibuprofen safe for her?";

	private static DrugReferenceService service() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(DUPLICATE_RULE_FIXTURE));
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return service;
	}

	private static PatientClinicalContext allergicToIbuprofen() {
		return DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("ibuprofen"), null);
	}

	/**
	 * The clauses the record's {@code Contraindicated with:} section lists, split on the rendering's
	 * own {@code "; "} separator. Read off the record text rather than recomputed, because the count
	 * a model reads is the count the rendered string states.
	 */
	private static List<String> contraindicationClauses(RecordMapping record) {
		String text = record.getText();
		int start = text.indexOf(" Contraindicated with: ");
		assertTrue(start >= 0,
				"precondition: the record must render a contraindication clause: " + text);
		String section = text.substring(start + " Contraindicated with: ".length());
		int next = section.indexOf(" Interactions:");
		if (next >= 0) {
			section = section.substring(0, next);
		}
		if (section.endsWith(".")) {
			section = section.substring(0, section.length() - 1);
		}
		return new ArrayList<String>(Arrays.asList(section.split("; ")));
	}

	private static List<SafetyWarning> ruleChips(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains(" is contraindicated by an ")) {
				out.add(warning);
			}
		}
		return out;
	}

	@Test
	public void theRecordListsAsManyContraindicationsAsTheChipsRaise() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = allergicToIbuprofen();

		DrugReference ibuprofen = DrugReferenceTestSupport.row(
				DrugReferenceTestSupport.fixtureEntries(DUPLICATE_RULE_FIXTURE), "Ibuprofen");
		assertEquals(2, ibuprofen.getContraindications().size(),
				"precondition: the fixture must really carry the one rule twice");

		List<SafetyWarning> chips = ruleChips(DrugReferenceTestSupport.validator(service)
				.validate("", QUESTION, context));
		assertEquals(1, chips.size(),
				"precondition: the chip side collapses the re-spelling to one chip, was: " + chips);

		PatientChart chart = DrugReferenceTestSupport.injector(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, QUESTION);
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).get(0);

		assertEquals(chips.size(), contraindicationClauses(record).size(),
				"the record must count contraindications as the chips do, was: " + record.getText());
	}

	@Test
	public void theOneClauseStillCarriesBothAuthoredNotes() throws Exception {
		// The half that makes this a JOIN rather than a copy of issue #174 site 2's drop: the second
		// row's note is the operator's own clinical instruction, and this record is the only place the
		// prompt ever carries it — the chip drops it (ContraindicationRouteVariantTest
		// .oneCuratedRuleAuthoredTwiceRaisesOneChip pins that, ties keeping the incumbent), so a record
		// that dropped it too would remove it from the deployment altogether.
		PatientChart chart = DrugReferenceTestSupport.injector(service())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), allergicToIbuprofen(), QUESTION);
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).get(0);
		List<String> clauses = contraindicationClauses(record);

		assertEquals(1, clauses.size(), "precondition: one clause, was: " + record.getText());
		assertTrue(clauses.get(0).contains("documented ibuprofen allergy"),
				"the incumbent note the chip quotes must survive, was: " + clauses);
		assertTrue(clauses.get(0).contains("avoid all NSAIDs"),
				"and so must the sibling note the chip drops, was: " + clauses);
	}

	@Test
	public void distinctRulesStillEachGetTheirOwnClause() throws Exception {
		// The control, over the SHIPPED curated seed: ibuprofen's four contraindication rows are four
		// distinct (type, token) pairs, so nothing may collapse and the record must read exactly as it
		// always has. Without this the collapse could key on something coarser — the type alone, say —
		// and merge two genuinely different contraindications into one clause.
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.bundledService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), allergicToIbuprofen(), QUESTION);
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).get(0);

		assertEquals(Arrays.asList("NSAID hypersensitivity", "documented ibuprofen allergy",
				"active gastrointestinal bleeding", "active peptic ulcer disease"),
				contraindicationClauses(record),
				"four distinct rules are four clauses, unchanged: " + record.getText());
	}
}
