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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #190 item 1 — {@code DrugReferenceInjector.render}'s {@code Contraindicated with:} clause
 * rendered one clause per contraindication ROW while
 * {@code DrugSafetyValidator.ContraindicationChips} raises one chip per
 * {@code (substance, type, token)} — or, since issue #146, per {@code (substance, substance)} for an
 * allergy rule naming its own entry — so the injected record and the chip beside it disagreed about how
 * many contraindications the entry has.
 *
 * <p>The two surfaces do not count the same POPULATION and are not being made to: the record renders
 * every clause the entry's rules render (a record is reference material about the drug) while the chip renders
 * the subset the patient's own chart matches. What they must agree on is the collapse UNIT, which is
 * what one rule authored twice exposes.
 *
 * <p>Curated-source-only by construction: the {@code ddinter} and {@code atc} sources publish no
 * contraindications at all (see {@link DdiDrugReferenceSource}'s class javadoc), so only an
 * operator-authored file can file one rule twice. The bundled seed does not — its four ibuprofen rows
 * are four distinct keys, the self-named allergy one being the substance since issue #146 and the
 * other three their own {@code (type, token)} — which is why nothing shipped changes here and why
 * this needs a fixture.
 *
 * <p><b>Joining rather than dropping.</b> Issue #174 site 2 could drop a repeated row because the
 * repeats were near-identical; here the sibling notes differ in text, and they are operator-authored
 * clinical prose that this record is the only place the model ever sees. For THAT shape — two rules on
 * ONE key — the clause count follows the chip while both notes survive inside the one clause.
 *
 * <p><b>Issue #310 — and the other collapse unit, one along again.</b> The cases at the end of
 * this file are about two rules on DIFFERENT keys rendering one string, which the per-key map listed
 * twice. They are not a second reading of the paragraph above: #190 item 1 is one RULE counted twice
 * and is fixed by the KEY, while this is one STRING listed twice and is fixed by the string. Their
 * direction on the count is the opposite one too — after #310 this record can list FEWER clauses than
 * the entry raises chips. The paragraph on the two POPULATIONS above is what licenses that; the one on
 * the JOIN describes a different shape, in which the clause count does follow the chip.
 *
 * <p>Runs the REAL production path: the real {@link JsonDrugReferenceSource} parser over a fixture,
 * the real {@code injectRecords} and the real {@code validate}, GP reads on their no-context defaults.
 */
public class InjectedContraindicationClauseTest {

	/** Shared with {@code ContraindicationRouteVariantTest}, which asks the same question of the CHIP:
	 *  one curated {@code allergy}/{@code ibuprofen} rule authored twice, under two spellings and with
	 *  two different notes. Both spellings NAME the entry, so since issue #146 they collapse on the
	 *  substance key rather than on the normalized {@code (type, token)} one this fixture was built
	 *  for; what it still pins is the JOIN, which is what this class is about. The rule key space's own
	 *  normalization is pinned by
	 *  {@code SelfNamedAllergyRuleFoldTest.aClassLevelRuleAuthoredTwiceIsStillOneChip}. */
	private static final String DUPLICATE_RULE_FIXTURE =
			"chartsearchai-test/drug-reference-duplicate-rule-tokens.json";

	/** Issue #310's own: one entry whose first and third rules carry ONE note while the second carries
	 *  another, so the clause a de-duplication drops is not adjacent to the one it keeps. */
	private static final String CROSS_KEY_CLAUSE_ORDER =
			"chartsearchai-test/drug-reference-cross-key-clause-order.json";

	/** Issue #308's own, reused: the entry whose collapsed key renders the em-dash JOIN, so one key's
	 *  clause CONTAINS another's — the arrangement that separates exact equality from containment. */
	private static final String COLLAPSED_KEY_JOINED_CLAUSE =
			"chartsearchai-test/drug-reference-collapsed-key-joined-clause.json";

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
		return clausesIn(record.getText());
	}

	/** As {@link #contraindicationClauses}, over a record located by the drug it names rather than by
	 *  position — {@code DrugReferenceTestSupport.referenceTextNaming}, which is what a case over a
	 *  fixture carrying several entries has to use. */
	private static List<String> clausesIn(String text) {
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

		assertEquals(1, contraindicationClauses(record).size(),
				"one rule authored twice is ONE clause, as it is one chip, was: " + record.getText());
		// Equal here because this entry's only rule is one the patient matches. The two surfaces do NOT
		// count the same population in general — the record renders every clause the entry's rules render while
		// the chip renders the subset the chart matches — so what #190 item 1 is about is the collapse
		// UNIT, which is now the same on both sides.
		assertEquals(chips.size(), contraindicationClauses(record).size(),
				"so the two surfaces agree for this entry, was: " + record.getText());
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
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.curatedService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), allergicToIbuprofen(), QUESTION);
		RecordMapping record = DrugReferenceTestSupport.injectedReferences(chart).get(0);

		assertEquals(Arrays.asList("NSAID hypersensitivity", "documented ibuprofen allergy",
				"active gastrointestinal bleeding", "active peptic ulcer disease"),
				contraindicationClauses(record),
				"four distinct rules are four clauses, unchanged: " + record.getText());
	}

	@Test
	public void twoAllergyRulesNamingTheDRUGAreOneClauseBecauseTheyAreOneChip() throws Exception {
		// Issue #146 moved the chip's collapse unit for an allergy rule NAMING the entry it is filed on:
		// two such rules under two aliases of one drug are one chip, because they report one fact. This
		// clause rendering keys on the chip's unit by contract, so it had to move with it — left keyed on
		// (type, token) it put TWO clauses in the record beside ONE chip, which is exactly the disagreement
		// issue #190 item 1 removed, re-opened one rule shape along. Silent: the model is simply told the
		// drug has two contraindications where the deterministic layer found one.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.fixtureEntries(SelfNamedAllergyRuleFoldTest.SELF_NAMED_SHAPES));
		String question = "Is it safe to give her nurofen?";
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("Brufen/Nurofen brand"), null);
		DrugReference nurofen = service.lookupByToken("nurofen");
		assertEquals(2, nurofen.getContraindications().size(),
				"precondition: the fixture must carry two rules, each naming the entry");
		for (DrugReference.Contraindication rule : nurofen.getContraindications()) {
			assertTrue(DrugSafetyValidator.selfNamedAllergyRule(nurofen, rule),
					"precondition: each rule must NAME the entry — " + rule.getToken());
			assertTrue(context.hasAllergyToken(rule.getToken()),
					"precondition: and both must match the one recorded allergy — " + rule.getToken());
		}

		List<SafetyWarning> chips = ruleChips(
				DrugReferenceTestSupport.validator(service).validate("", question, context));
		PatientChart chart = DrugReferenceTestSupport.injector(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		List<String> clauses = contraindicationClauses(
				DrugReferenceTestSupport.injectedReferences(chart).get(0));

		assertEquals(1, chips.size(), "one fact is one chip, was: " + chips);
		assertEquals(1, clauses.size(), "and one clause beside it, was: " + clauses);
		assertEquals("documented nurofen allergy — avoid the other brand too", clauses.get(0),
				"joined, so the note the chip drops still reaches the prompt, was: " + clauses);
	}

	/** Call-site alias for {@link DrugReferenceTestSupport#sectionItems}, which carries the contract. */
	private static List<String> sectionItems(String record, String lead) {
		return DrugReferenceTestSupport.sectionItems(record, lead);
	}

	/** How many collapsed keys {@code ref}'s rules land on — {@link DrugSafetyValidator#contraindicationFinding}'s
	 *  own partition, asked of production so a precondition cannot assert a key space the renderer does
	 *  not use.
	 *
	 *  <p>Since issue #310 this is NOT the rendered clause count, and the gap is what the cases below
	 *  use it to establish: the keys apart, the clauses together. Read an assertion here as a statement
	 *  about the rendered list and the arrangement stops being the one the case needs. */
	private static int distinctKeys(DrugReference ref) {
		Set<Object> keys = new LinkedHashSet<Object>();
		for (DrugReference.Contraindication rule : ref.getContraindications()) {
			keys.add(DrugSafetyValidator.contraindicationFinding(ref, rule));
		}
		return keys.size();
	}

	/** A service over {@code fixture}, parsed by the real production parser. */
	private static DrugReferenceService fixtureService(String fixture) throws Exception {
		return DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(fixture));
	}

	/** The rendered {@code drug_reference} record for {@code drug}, through the real injector wired to
	 *  the real validator over {@code service} — the CALLER's service, never one built here, so a case
	 *  asserting a precondition through it asserts that of the very instance the record is rendered
	 *  from. Every case below that reads a record goes through this, so none of them can differ in how
	 *  the record was produced. */
	private static String recordFor(DrugReferenceService service, String question,
			PatientClinicalContext context, String drug) {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, drug);
		assertNotNull(record, "no " + drug + " reference record was injected: "
				+ DrugReferenceTestSupport.referenceTexts(chart));
		return record;
	}

	@Test
	public void twoRulesOfOneEntrySharingANoteRenderThatClauseOnce() throws Exception {
		// Issue #310, and the ticket's own shape. Codeine files an `allergy` rule on `codeine` and a
		// `condition` rule on `respiratory depression`, both carrying the note `opioid reaction` — which
		// DrugReferenceInjector.contraindicationSections' own section comment calls a natural way to
		// author "recorded either way". contraindicationFinding keys them apart (the self-named allergy rule on the SUBSTANCE since
		// issue #146, the condition rule on its own (type, token)), so the per-key map renders the string
		// twice and the list read "opioid reaction; opioid reaction": two rules, ONE clinical fact, and a
		// model reading citable evidence told the drug has two contraindications where the deterministic
		// layer found one. That is issue #190 item 1's harm one collapse unit along.
		//
		// The de-duplication that already existed is the em-dash join inside contraindicationClauses,
		// which is scoped to one key by construction and cannot see across keys. What this pins is the
		// CROSS-KEY half, over the very string identity the three reading sections have resolved over
		// since issue #308.
		DrugReferenceService service =
				fixtureService(InjectedContraindicationCorroborationTest.BORROWED_ALIAS);
		DrugReference codeine = service.lookupByToken("codeine");
		assertEquals(2, codeine.getContraindications().size(),
				"precondition: the fixture must carry both rules");
		assertEquals(2, distinctKeys(codeine),
				"precondition: and they must land on DIFFERENT collapsed keys, or this case is the "
						+ "within-key join issue #190 item 1 already handles");

		String record = recordFor(service, "Is it safe to give her codeine?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dihydrocodeine"), null),
				"Codeine");

		assertEquals(Arrays.asList("opioid reaction"), clausesIn(record),
				"two rules carrying one note are ONE clause in the rendered list, was: " + record);
	}

	@Test
	public void aClauseTwoKeysRenderIsListedOnceWithAnotherClauseBetweenThem() throws Exception {
		// The same de-duplication where the two keys are not adjacent, which is what separates dropping a
		// repeat from dropping a NEIGHBOUR: Pethidine renders "avoid …" (condition, unrecorded), then
		// "documented respiratory risk" (condition, recorded), then "avoid …" again (self-named allergy,
		// recorded). Only the first and third collapse, and the clause between them keeps its own slot.
		String record = pethidineRecord();

		assertEquals(Arrays.asList("avoid in opioid-sensitive states", "documented respiratory risk"),
				clausesIn(record),
				"the repeat collapses onto its first slot and the clause between survives, was: "
						+ record);
	}

	@Test
	public void aReadingSectionIsListedInTheDeduplicatedClausesOwnOrder() throws Exception {
		// The half de-duplicating the list alone would BREAK, and the reason contraindicationSections
		// re-emits its sections rather than taking the order the per-key walk produced.
		//
		// contraindicationSections' own comment at the inClauseOrder calls carries the argument; this is
		// the arrangement that discriminates it. "avoid …" is rendered by key 1 (the denial) and key 3
		// (the assertion), the cross-key precedence gives it to the assertion, and "documented
		// respiratory risk" sits between them on key 2 — so the recorded section reached key 2 first
		// while the list leads with key 1. This asserts the rendered ORDER, not membership.
		String record = pethidineRecord();
		List<String> clauses = clausesIn(record);
		List<String> recorded = sectionItems(record, DrugReferenceInjector.RECORDED_READING_LEAD);

		assertEquals(clauses, recorded,
				"every clause here is recorded, so the section IS the list — in the list's own order, "
						+ "was: " + record);
		assertEquals(Arrays.asList("avoid in opioid-sensitive states", "documented respiratory risk"),
				recorded, "and that order is first-occurrence order across the keys, was: " + record);
	}

	@Test
	public void theDenialAndTheHedgeAreListedInTheClausesOwnOrderToo() throws Exception {
		// The same anomaly in the two sections the case above cannot reach, and each arrives by its own
		// route — which is why all three sections are re-emitted and not just the one that outranks the
		// others.
		//
		// A string reaches the DENIAL late when an earlier key rendering it was UNEVALUABLE. Tapentadol's
		// rule 1 is typed `diagnosis`, which is neither chart list this module reads, and rule 3 repeats
		// its note with rule 2's between them, so the list takes the string at rule 1's slot while the
		// denial's own walk reaches it only at rule 3.
		//
		// A string reaches the HEDGE late when an earlier key rendering it was a DENIAL, since the hedge
		// outranks the denial in the precedence above. Rule 4 denies `dose reduction required`, rule 6
		// matches it through `renal` inside the recorded `Malignant tumor of adrenal gland` — containment
		// without a word boundary, so it hedges rather than records — and rule 5 sits between them.
		//
		// Drop either of those two calls and read the failures.
		String record = tapentadolRecord();

		assertEquals(Arrays.asList("monitor for respiratory depression", "avoid in hepatic impairment"),
				sectionItems(record, DrugReferenceInjector.NOT_RECORDED_READING_LEAD),
				"the denial follows the clause list, not the order its own keys were walked in, was: "
						+ record);
		assertEquals(Arrays.asList("dose reduction required", "documented tapentadol reaction"),
				sectionItems(record, DrugReferenceInjector.UNCORROBORATED_READING_LEAD),
				"and so does the hedge, was: " + record);
		// The list itself is the order both were re-emitted into. Note what it does NOT show: the clause
		// the `diagnosis` rule renders IS claimed, by the denial, because rule 3 renders the same string
		// — the partition's exception is over the CLAUSE and not the RULE, and contraindicationSections'
		// javadoc carries that residue and why it is pre-existing rather than this change's.
		assertEquals(Arrays.asList("monitor for respiratory depression", "avoid in hepatic impairment",
				"dose reduction required", "documented tapentadol reaction"), clausesIn(record),
				"was: " + record);
	}

	@Test
	public void clausesDifferingOnlyInCaseOrSpacingAreEachTheirOwnClause() throws Exception {
		// The de-duplication identity is exact equality of the rendered clause, and this is what holds
		// the EXACTNESS. Containment is held by the case above; case-folding and whitespace-normalising
		// are a different loosening with the same harm. Fold the identity either way and read the
		// failures.
		//
		// Nalbuphine renders "Avoid in pregnancy" (denied), "avoid in pregnancy" (a self-named allergy
		// rule her recorded Nalbuphine allergy both matches and NAMES, so recorded) and
		// "Avoid  in  pregnancy" (denied). Fold case and the recorded clause leaves the list, so
		// inClauseOrder drops it from its section too and the record silently retracts a reading it had
		// established. Normalise interior whitespace and the third clause leaves the list.
		//
		// The premise first, for the reason aClauseAnotherKeyMerelyCONTAINSIsStillItsOwnClause states.
		String record = recordFor(fixtureService(CROSS_KEY_CLAUSE_ORDER),
				"Is it safe to give her nalbuphine?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Nalbuphine"), null),
				"Nalbuphine");

		assertEquals("avoid in pregnancy",
				DrugReferenceTestSupport.sectionAfter(record, DrugReferenceInjector.RECORDED_READING_LEAD),
				"the lower-cased clause is the RECORDED one; folding case retracts that reading, was: "
						+ record);
		assertEquals(Arrays.asList("Avoid in pregnancy", "avoid in pregnancy", "Avoid  in  pregnancy"),
				clausesIn(record),
				"three clauses differing only in case or spacing are three clauses, was: " + record);
	}

	/** As {@link #pethidineRecord}, for the entry carrying the other two sections' anomaly. */
	private static String tapentadolRecord() throws Exception {
		return recordFor(fixtureService(CROSS_KEY_CLAUSE_ORDER), "Is it safe to give her tapentadol?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Tapentazoline"),
						DrugReferenceTestSupport.set("Malignant tumor of adrenal gland")),
				"Tapentadol");
	}

	@Test
	public void aClauseAnotherKeyMerelyCONTAINSIsStillItsOwnClause() throws Exception {
		// The identity is exact equality, never containment, and this is what holds that. Levoketoconazole
		// files two self-named allergy rules, so issue #146 keys BOTH on the substance and
		// contraindicationClauses renders their joined clause "opioid reaction — other reaction"; a
		// CONDITION rule of another key carries "opioid reaction" alone. One string contains the other and
		// both are listed — the residue contraindicationSections' "Not containment" paragraph names.
		//
		// Pinning it is not endorsing a repeat: the two strings carry different clinical content ("other
		// reaction" appears only in the join), so collapsing them drops an operator instruction this
		// record is the only place the prompt carries.
		String record = recordFor(fixtureService(COLLAPSED_KEY_JOINED_CLAUSE),
				"Is it safe to give her levoketoconazole?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Levocetirizine"),
						DrugReferenceTestSupport.set("Respiratory depression")),
				"Levoketoconazole");

		// The premise first, and measured rather than asserted in prose, because it is the half that makes
		// containment DANGEROUS rather than merely lossy: the shorter clause is the one this chart
		// RECORDS, and it sits in a different section from the join that contains it. Loosen the
		// de-duplication and this is what fails — the list drops it, inClauseOrder then drops it from
		// this section, and the record silently stops stating a reading it had established. Read through
		// sectionAfter rather than sectionItems so an absent section reads as the retraction it is and
		// not as a stale precondition.
		assertEquals("opioid reaction",
				DrugReferenceTestSupport.sectionAfter(record, DrugReferenceInjector.RECORDED_READING_LEAD),
				"the shorter clause is the RECORDED one; losing it retracts a chart reading, was: "
						+ record);

		assertEquals(Arrays.asList("opioid reaction — other reaction", "opioid reaction"),
				clausesIn(record),
				"a clause another key merely CONTAINS is still its own clause, was: " + record);
	}

	/** Issue #310's own fixture, rendered through the real injector wired to the real validator: one
	 *  entry, three rules, three collapsed keys, and one clause string rendered by the first and third
	 *  of them. */
	private static String pethidineRecord() throws Exception {
		DrugReferenceService service = fixtureService(CROSS_KEY_CLAUSE_ORDER);
		assertEquals(3, distinctKeys(service.lookupByToken("pethidine")),
				"precondition: the fixture's three rules must land on three collapsed keys");
		return recordFor(service, "Is it safe to give her pethidine?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Pethidine"),
						DrugReferenceTestSupport.set("Respiratory depression")),
				"Pethidine");
	}
}
