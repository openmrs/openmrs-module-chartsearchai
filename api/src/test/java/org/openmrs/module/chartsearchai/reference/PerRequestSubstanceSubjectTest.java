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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #238 item 1 — {@code DrugSafetyValidator.SubstanceSubjects} decided which row a response names a
 * substance by once per {@code validate} PASS, and the safety layer runs twice per {@code /search}: the
 * pre-answer pass that renders each finding as a citable {@code safety_finding} record the model reads
 * ({@code DrugReferenceInjector.preAnswerFindings}), and the chips pass beside the answer. The chips pass
 * additionally sees the rows the ANSWER puts in play, so the two could name one substance two ways in one
 * response — the injected record a clinician can cite saying one thing and the chip beside it another.
 *
 * <p>The fix is INVARIANCE rather than a per-request carrier, for a substance the question or the
 * patient's own orders resolve: the subject is folded over those rows alone while every arm still
 * reaches its rules and its bands over the whole group, so {@code answer} — the one input that varies
 * WITHIN {@code validate} — is no longer read for such a substance. Nothing is transported from one pass
 * to the other. Not universal: a substance in play ONLY because the answer named it has no such group to
 * fold, and {@code SubstanceSubjects.groupOf} then folds THAT pass's own answer-widened rows instead —
 * see {@link #aSubstanceOnlyTheAnswerNamesIsStillFoldedRatherThanAnsweredPositionally}, which is exactly
 * that shape, and {@code groupOf}'s own javadoc, which states this narrower claim correctly.
 *
 * <p><b>Which is not "the two passes cannot disagree", and these cases cannot see the difference.</b>
 * Each pass builds its own {@code PatientClinicalContext} from the patient, so the order rows and the
 * recorded names are a function of two chart reads separated by the LLM call;
 * {@code DrugSafetyValidator.SubstanceSubjects}' javadoc states that residue. Every case here that runs
 * the passes builds ONE context object and hands it to both — most via
 * {@link DrugReferenceTestSupport#contextNaming}, and
 * {@link #theQuestionPairArmNamesTheSubstanceAlikeAcrossBothPasses} via a plain
 * {@link DrugReferenceTestSupport#ctx} call — so what they assert is agreement given one read, never
 * agreement across two. {@code mappings} is likewise not varied by any case — it is null in the real pre-answer
 * pass and real in the chips pass, and the claim that it reaches none of the three inputs this folds is
 * reasoned from its readers rather than exercised here.
 *
 * <p>The one-substance premise is asserted by
 * {@link #theFixturePosesTheDivergenceAndSurvivesTheLoadTimeRepair} and by that case alone: break the
 * fixture's {@code substanceName} and the two headline cases below still pass, on a fixture where there is
 * then no divergence to close. They are non-vacuous because that sibling case reddens, not because they
 * check it themselves.
 *
 * <p><b>Both channels are asserted, and by the key rather than the prose.</b> The injected finding's
 * {@code resourceUuid} is {@link ChartSearchAiUtils#resourceKey} over the finding's type and drug
 * ({@code DrugReferenceInjector.injectRecords}), so comparing it against the chip's own
 * {@code resourceKey} is an EXACT equality on the one field #206 made "the substance's name in this
 * response". A containment assertion could not do it here: {@code Estrone sulfate} is a prefix of
 * {@code Estrone sulfate (topical)}, so the divergence this file exists for would pass one.
 *
 * <p>Every scenario runs the real production path — the real {@link JsonDrugReferenceSource} parse of a
 * test-classpath fixture, the real {@code injectRecords} / {@code preAnswerFindings} for the pre-answer
 * pass and the real {@code validate} for the chips pass, real question and answer strings, GP reads on
 * their no-context defaults.
 */
public class PerRequestSubstanceSubjectTest {

	private static final String FIXTURE =
			"chartsearchai-test/drug-reference-answer-named-substance-row.json";

	/** The row the QUESTION's own word resolves, and the only one carrying the bare {@code estrone}. */
	private static final String QUALIFIED = "Estrone sulfate (topical)";

	/** The row only the ANSWER's wording reaches, and the one {@code canonicalRow} elects. */
	private static final String UNQUALIFIED = "Estrone sulfate";

	/** Names the qualified row alone — it carries the bare alias and the unqualified row does not. */
	private static final String QUESTION = "Can I give her estrone?";

	/** Names the unqualified row, spelling the alias only that row publishes. No dose, so the dose arm
	 *  reads the answer and finds nothing to compare. */
	private static final String ANSWER = "Estrone sulfate can be given.";

	private static DrugReferenceService service() throws Exception {
		return DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
	}

	/** The patient is on warfarin and on nothing else, so no recorded name claims either estrone row and
	 *  the fold alone decides which one names the substance. */
	private static PatientClinicalContext onWarfarin(DrugReferenceService service) {
		return DrugReferenceTestSupport.contextNaming(service, 40, null, "Warfarin");
	}

	private static String keyOf(SafetyWarning warning) {
		return ChartSearchAiUtils.resourceKey(warning.getType(), warning.getDrug());
	}

	@Test
	public void theFixturePosesTheDivergenceAndSurvivesTheLoadTimeRepair() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(FIXTURE);
		DrugReference qualified = DrugReferenceTestSupport.row(entries, QUALIFIED);
		DrugReference unqualified = DrugReferenceTestSupport.row(entries, UNQUALIFIED);

		// The premise a hand-written fixture can get wrong invisibly: DrugReferenceService.ensureLoaded
		// runs DrugReferenceValidity.checkEntries, whose sanitizeAliases adds an entry's own display name
		// back to its aliases (ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES / REPAIRED). A row named "Estrone"
		// aliased only "estrone sulfate" would therefore publish "estrone" on every REAL load, the
		// question would resolve both rows, and the divergence below would exist only because
		// serviceWith reaches setEntries — the seam that bypasses all dataset loading. Every row already
		// naming itself is what makes this fixture's asymmetry true of a real load, so it is asserted
		// rather than trusted: edit the fixture into a shape the loader would repair and this reddens.
		for (DrugReference entry : entries) {
			assertTrue(entry.isNamed(DrugReference.normalizeName(entry.getName())),
					"precondition: every row must already list its own display name, or the load-time "
							+ "alias repair changes this fixture and the premises below are about a "
							+ "dataset the module cannot load: " + entry.getName());
		}
		// The same premise put to the production check itself rather than to the one rule of it this
		// fixture was designed around, so an edit tripping some OTHER rule — a repair or a drop — is
		// caught too. Asserted on freshly parsed entries because checkEntries MUTATES what it repairs.
		DrugReferenceValidity validity = new DrugReferenceValidity();
		validity.checkEntries(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		assertEquals(Collections.<String> emptyList(),
				DrugReferenceTestSupport.rulesOf(validity.getFindings()),
				"precondition: the real load-time validity check must find nothing to repair or report "
						+ "here, or this fixture is not a dataset a real load would produce");

		assertEquals(qualified.substanceGroupKey(), unqualified.substanceGroupKey(),
				"precondition: the two rows must be ONE substance, or there is no group to name");
		assertFalse(qualified.namesNoRoute(),
				"precondition: the row the question resolves must name a route");
		assertTrue(unqualified.namesNoRoute(),
				"precondition: the row only the answer reaches must not, so canonicalRow's FIRST rung "
						+ "elects it and the second is never consulted");
		assertSame(unqualified, DrugReference.canonicalRow(Arrays.asList(qualified, unqualified)),
				"precondition: the fold must elect the answer-reached row, or the passes cannot differ");

		DrugReferenceService service = service();
		assertEquals(Arrays.asList(QUALIFIED),
				DrugReferenceTestSupport.names(service.findImpliedByQuery(QUESTION)),
				"precondition: the question must resolve the qualified row ALONE");
		assertTrue(DrugReferenceTestSupport.names(service.findImpliedByQuery(ANSWER)).contains(UNQUALIFIED),
				"precondition: the answer must reach the row the question did not");
	}

	/**
	 * The property, over both channels of one request: the citable record the model READ and the chip
	 * displayed beside the answer must call one substance one thing.
	 */
	@Test
	public void theCitedFindingAndTheChipBesideTheAnswerNameOneSubstanceOneWay() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = onWarfarin(service);

		PatientChart injected = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, QUESTION);
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(injected);
		assertEquals(1, findings.size(),
				"precondition: the pre-answer pass must inject exactly one citable finding, or this "
						+ "case asserts nothing: " + injected.getText());

		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate(ANSWER, QUESTION, context);
		assertEquals(1, chips.size(),
				"precondition: the chips pass must raise exactly one chip: "
						+ DrugReferenceTestSupport.details(chips));
		assertEquals(SafetyWarning.TYPE_INTERACTION, chips.get(0).getType(),
				"precondition: and it must be the interaction chip the finding was rendered from");

		assertEquals(findings.get(0).getResourceUuid(), keyOf(chips.get(0)),
				"the record the model read and the chip beside the answer must key on one name for one "
						+ "substance. The finding's resourceUuid is resourceKey(type, drug), so this is "
						+ "an exact equality on the field issue #206 made 'the substance's name in this "
						+ "response' — and the chip's detail says: " + chips.get(0).getDetail());
	}

	/**
	 * The same property stated as the two PASSES rather than as the two channels: the pre-answer pass
	 * and the chips pass, both through their own production entry points, must name the substance alike.
	 * Asserted beside the case above rather than instead of it, because that one is what a clinician sees
	 * and this one is where the divergence is created.
	 */
	@Test
	public void thePreAnswerPassAndTheChipsPassNameTheSubstanceAlike() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = onWarfarin(service);

		List<SafetyWarning> preAnswer = DrugReferenceTestSupport.injectorWithSafety(service)
				.preAnswerFindings(context, QUESTION);
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate(ANSWER, QUESTION, context);

		assertEquals(1, preAnswer.size(), "precondition: one pre-answer finding: "
				+ DrugReferenceTestSupport.details(preAnswer));
		assertEquals(1, chips.size(), "precondition: one chip: "
				+ DrugReferenceTestSupport.details(chips));
		assertEquals(preAnswer.get(0).getDrug(), chips.get(0).getDrug(),
				"the two passes must name one substance alike; the chips pass sees the rows the ANSWER "
						+ "puts in play and the pre-answer pass cannot, so the naming decision may not "
						+ "read them");
		assertEquals(QUALIFIED, chips.get(0).getDrug(),
				"and the name they agree on is the one the pre-answer record already published, since "
						+ "that record is in the prompt the model read by the time an answer exists");
	}

	/**
	 * The one shape the naming group cannot cover, and what the fallback does with it: a substance
	 * in play ONLY because the answer names it — the question does not name it and the patient is not on
	 * it. The pre-answer pass validates with an empty answer, so it never saw this substance and there is
	 * nothing for the chips pass to disagree with; what still has to hold is issue #206's own property
	 * INSIDE the chips pass, that the rows are folded rather than answered positionally. So the fallback
	 * folds the answer's own rows, and the row it elects is the unqualified one rather than the fixture's
	 * first.
	 */
	@Test
	public void aSubstanceOnlyTheAnswerNamesIsStillFoldedRatherThanAnsweredPositionally() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = onWarfarin(service);
		String answer = "Norethisterone can be given instead.";

		assertEquals(Arrays.asList("Norethisterone (topical)", "Norethisterone"),
				DrugReferenceTestSupport.names(service.findImpliedByQuery(answer)),
				"precondition: the answer must reach BOTH rows, the qualified one FIRST — a positional "
						+ "answer would keep that one, so the fold is what this case can see");
		assertTrue(service.findImpliedByQuery(QUESTION).isEmpty()
				|| !DrugReferenceTestSupport.names(service.findImpliedByQuery(QUESTION))
						.contains("Norethisterone"),
				"precondition: and the question must not name this substance, or it has a naming group");

		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate(answer, QUESTION, context);
		List<String> named = new ArrayList<String>();
		for (SafetyWarning chip : chips) {
			if (chip.getDrug().startsWith("Norethisterone")) {
				named.add(chip.getDrug());
			}
		}
		assertEquals(Arrays.asList("Norethisterone"), named,
				"the substance only the answer named is folded to its unqualified row, not left as the "
						+ "row the arm reached first: " + DrugReferenceTestSupport.details(chips));
	}

	/**
	 * Issue #236's own residue, closed by this change too — not only for the drug-in-play, dose and
	 * contraindication arms {@link #thePreAnswerPassAndTheChipsPassNameTheSubstanceAlike} covers, but for
	 * the QUESTION-PAIR arm ({@code DrugSafetyValidator.addQuestionPairInteractions}), which #236 moved
	 * onto the shared {@code SubstanceSubjects} lookup this change folds. Reproduces ADR Decision 49's own
	 * live measurement over the real shipped 19 MB knowledge base: a question naming both Kanamycin and
	 * {@code Daxibotulinumtoxina}, a brand-named row of a substance the dataset also files under
	 * {@code Botulinum toxin type A} — the two rows tie on {@code namesNoRoute} and {@code canonicalRow}
	 * elects the latter on the second rung, the row the data files the family under (issue #250).
	 * Answered with wording that names {@code Botulinum toxin type A} instead. Before issue #238,
	 * Decision 49 measured the pre-answer record and the chip printing two different names for that
	 * family; this
	 * case asserts they now print one.
	 */
	@Test
	public void theQuestionPairArmNamesTheSubstanceAlikeAcrossBothPasses() throws Exception {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWithGroups(DrugReferenceTestSupport.shippedEntries());
		String question = "Does Daxibotulinumtoxina interact with kanamycin?";
		String answer = "Botulinum toxin type A can interact with kanamycin.";
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, 70.0, null, null, null, null);

		assertEquals(Arrays.asList("Kanamycin", "Daxibotulinumtoxina"),
				DrugReferenceTestSupport.names(service.findImpliedByQuery(question)),
				"precondition: the question must name exactly these two rows, or the question-pair arm "
						+ "never runs and this case asserts nothing");
		assertTrue(DrugReferenceTestSupport.names(service.findImpliedByQuery(answer))
						.contains("Botulinum toxin type A"),
				"precondition: the answer must reach the family's OTHER row, or nothing can move between "
						+ "the passes");

		List<SafetyWarning> preAnswer = DrugReferenceTestSupport.injectorWithSafety(service)
				.preAnswerFindings(context, question);
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate(answer, question, context);

		SafetyWarning preAnswerPair =
				DrugReferenceTestSupport.onlyOfType(preAnswer, SafetyWarning.TYPE_INTERACTION);
		SafetyWarning chipPair = DrugReferenceTestSupport.onlyOfType(chips, SafetyWarning.TYPE_INTERACTION);

		assertEquals(preAnswerPair.getDetail(), chipPair.getDetail(),
				"the pre-answer record the model read and the chip beside the answer must name one "
						+ "substance alike. Before issue #238 the pre-answer pass printed '...Daxibotulinumtoxina "
						+ "(botulinum toxin type a)...' and the chips pass printed '...Botulinum toxin type A...' "
						+ "for the identical pair, which is ADR Decision 49's own reproducer: pre-answer="
						+ preAnswerPair.getDetail() + " | chips=" + chipPair.getDetail());
	}

	/**
	 * The consequence of moving the subject, pinned rather than merely stated: {@code addOverdose} tries
	 * the SUBJECT's own band first — "the row tried first has followed that choice ever since [#206]" —
	 * so the ceiling a dose chip quotes moves with the name. Here the response names the substance by the
	 * qualified row, so its own 3000 mg/day ceiling is the one quoted, and no attribution clause is
	 * needed because the number and the name are the same row's.
	 */
	@Test
	public void theDoseChipQuotesTheCeilingOfTheRowTheResponseNamesTheSubstanceBy() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = onWarfarin(service);

		SafetyWarning dose = DrugReferenceTestSupport.onlyOfType(DrugReferenceTestSupport.validator(service)
				.validate("Estrone sulfate 4000 mg daily is fine.", QUESTION, context),
				SafetyWarning.TYPE_OVERDOSE);

		assertEquals(QUALIFIED, dose.getDrug(),
				"the dose chip names the substance the way every other arm in this response does: "
						+ dose.getDetail());
		assertTrue(dose.getDetail().contains("3000 mg/day"),
				"and the ceiling quoted is the named row's own, which is what subject-first ordering "
						+ "means: " + dose.getDetail());
		assertFalse(dose.getDetail().contains("a ceiling this dataset publishes for"),
				"so no attribution clause, the number and the name being one row's: " + dose.getDetail());
	}

	/**
	 * The forbidden direction, which the fix must not take: a band only a SIBLING row publishes must
	 * still be reported. A stated 2500 mg/day clears the named row's 3000 and exceeds the sibling's 2000,
	 * so the walk after the subject reaches it and {@code ceilingAttribution} says whose ceiling it is —
	 * the fallback #208 kept precisely so that preferring the subject's band cannot lose a warning.
	 */
	@Test
	public void aCeilingOnlyASiblingRowPublishesIsStillReportedAgainstThatRow() throws Exception {
		DrugReferenceService service = service();
		PatientClinicalContext context = onWarfarin(service);

		SafetyWarning dose = DrugReferenceTestSupport.onlyOfType(DrugReferenceTestSupport.validator(service)
				.validate("Estrone sulfate 2500 mg daily is fine.", QUESTION, context),
				SafetyWarning.TYPE_OVERDOSE);

		assertEquals(QUALIFIED, dose.getDrug(),
				"the chip is still named after the row the response names the substance by: "
						+ dose.getDetail());
		assertTrue(dose.getDetail().contains("2000 mg/day"),
				"a dose clearing that row's band and exceeding a sibling's must still warn, against "
						+ "the sibling's ceiling — the direction issue #208 refused to trade away: "
						+ dose.getDetail());
		assertTrue(dose.getDetail().contains("a ceiling this dataset publishes for " + UNQUALIFIED
				+ ", not for " + QUALIFIED),
				"and the sentence says whose ceiling it quoted (issue #208): " + dose.getDetail());
	}
}
