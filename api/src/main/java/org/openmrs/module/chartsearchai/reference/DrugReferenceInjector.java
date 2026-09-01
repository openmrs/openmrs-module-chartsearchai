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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Part 1 of the drug-reference feature: injects matching {@link DrugReference}
 * entries into the serialized chart as additional numbered records the LLM can
 * cite, so it can ground reference facts (dosing, prose warnings,
 * contraindications, interactions) the same way it grounds chart records.
 *
 * <p>Injection is appended <em>after</em> the retrieved chart records, continuing
 * the citation numbering. A {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_REFERENCE} record
 * carries that resource type so the frontend can render its citation chip distinctly (a side
 * panel, not a chart-tab navigation). That is NOT true of every injected type — an
 * {@link ChartSearchAiConstants#RESOURCE_TYPE_ACTIVE_DRUG_ORDER} record is deliberately the
 * opposite, carrying the patient's real {@code Order} uuid precisely so a client CAN navigate to
 * it like any other chart citation (see below).
 *
 * <p><strong>Adding another kind of injected record?</strong> Its resource type must also be
 * classified by {@link org.openmrs.module.chartsearchai.ChartSearchAiUtils#referenceGroup}, which
 * decides whether a client presents a citation as evidence about the patient or as module-supplied
 * reference material. That method fails safe to <em>chart evidence</em> for types it does not
 * recognise — the wrong default for module-supplied material — so an unclassified injected type is
 * published to clinicians as if it came from the patient's own record, with no error raised. The
 * reflective guard in {@code ChartSearchAiReferenceGroupTest} catches a new
 * {@code RESOURCE_TYPE_*} constant, but it cannot see a bare string literal written here.
 *
 * <p>That one classification also decides whether the citation can be grounding-verified (issue
 * #122): reference material is demote-only, so its verdict is never {@code true}, while chart
 * evidence is graded normally. Both consequences follow from the single provenance judgement and
 * both are asserted by that guard — they used to be two separate registrations, and the second was
 * missed when {@code safety_finding} was added. Since issue #201 it decides a third thing: a
 * reference-group citation publishes no verdict at all, serializing {@code grounded: null} however
 * it was graded. So classifying a new injected type as reference material silently removes its
 * citations from the grounding signal a client sees — which is correct for module-supplied prose
 * and wrong for the patient's own record, and is one more reason to decide on provenance rather
 * than on "the module injected it". Since issue #229 it also decides prompt COST: the record and
 * character totals {@code ChartSearchAiUtils.referenceSlice} measures, and the audit row carries,
 * are the reference-group ones — so classifying a new injected type here also puts it into the
 * figure an operator reads as this module's share of the context window.
 *
 * <p>Three kinds are injected today, and they are not all module-supplied: a
 * {@code drug_reference} entry and a {@code safety_finding} present as reference material, while an
 * {@link ChartSearchAiConstants#RESOURCE_TYPE_ACTIVE_DRUG_ORDER} record is the patient's own active
 * order (read from {@code OrderService} when the chart cannot substantiate it — see
 * {@link #unrepresentedActiveOrders}) and so deliberately presents as chart evidence. For that one
 * the fail-safe default happens to be the correct classification; the decision is recorded
 * explicitly in the guard test rather than left to the default.
 *
 * <p>Matching is deterministic and age-gated:
 * <ul>
 *   <li><b>Question-driven</b> — an alias hit against the query text.</li>
 *   <li><b>Patient-driven</b> — the reference entries the patient's active orders resolve to, which
 *       since issue #151 is whatever {@code DrugReferenceService.findForActiveOrders} answers (an ATC
 *       code hit OR any name the order carries — its coded drug's, the free text a clinician typed,
 *       or its concept's) rather than the ATC hit alone, so this layer and
 *       {@link DrugSafetyValidator} cannot disagree about which orders the patient has.</li>
 * </ul>
 * Numeric dosing is rendered only when an age band matches the patient's age, so
 * a pediatric maximum is never surfaced for an adult query; contraindication and
 * interaction facts (which are not age-specific) are still rendered.
 */
@Service("chartSearchAi.drugReferenceInjector")
public class DrugReferenceInjector {

	private static final Logger log = LoggerFactory.getLogger(DrugReferenceInjector.class);

	/**
	 * Per-entry character budget for the rendered {@code Interactions:} section. Bounds the
	 * grounding text a single reference line contributes to the prompt so a broad dataset cannot
	 * overflow the LLM context window; the deterministic {@link DrugSafetyValidator} still reads
	 * every interaction off the entry, so nothing is lost from safety checking. How many the record
	 * does not name is reported on the {@link RecordMapping} as a field — never as a text tail, which
	 * the model recited into answers (issue #117). Note that this budget is not the only reason a
	 * partner goes unnamed, nor usually the main one: segment 2 of {@code render} represents the
	 * whole dataset tail with a single partner whenever a patient-relevant one was promoted, so most
	 * of that count is normally "not relevant to this patient" rather than "did not fit".
	 *
	 * <p>Two guarantees override the budget, so the rendered length is the cap plus a bounded
	 * overshoot rather than a hard ceiling — see {@code render}: every partner the patient is
	 * actually on is represented (full note, or a compact {@code name (Severity)} form when the
	 * note will not fit), and one dataset-order partner renders alongside them — compact when the
	 * patient has a relevant partner (breadth is all it is there for), in full when they do not.
	 *
	 * <p><b>Per record, and nothing bounds how many records there are.</b> N records cost N times
	 * this. That is a deliberate standing decision rather than an omission — issue #229 asked for a
	 * cap in the shape of {@code maxPairChips} and it was declined; ADR Decision 57 is canonical for
	 * the measurement and the argument, and is not restated here. What replaces it is a durable
	 * observable: {@code ChartSearchAiUtils.referenceSlice} measures the assembled chart and the
	 * figure reaches the audit row, so the cost is readable per answer without a log level.
	 */
	static final int MAX_INTERACTION_RENDER_CHARS = 1500;

	/** The lead of the reading's first section: a clause this patient's chart records. */
	static final String RECORDED_READING_LEAD = " Recorded for this patient: ";

	/** The lead of the reading's second section: a clause this patient's chart does NOT record. */
	static final String NOT_RECORDED_READING_LEAD = " Not recorded for this patient: ";

	/** The lead of the reading's third section (issue #269): a clause the patient's chart matched, but
	 *  which nothing corroborates — see {@link #contraindicationSections} and {@link #corroborated}.
	 *
	 *  <p>It asserts no contraindication and denies none, and is worded as a CONTRAST for issue #244's
	 *  reason: the record says what the match rests on and leaves the sentence a fact about the EVIDENCE.
	 *  It does speak ABOUT the chart — "Matched in this patient's chart" — which is what puts it on the
	 *  {@code drugSafety} switches' side of {@link #render}'s divide, with the two sections beside it
	 *  ({@code InjectedContraindicationCorroborationToggleContextTest}).
	 *
	 *  <p><b>What it must not be is a CATEGORICAL about the chart.</b> An
	 *  earlier wording said "not by a recorded allergy to this drug", and that is a categorical the
	 *  chart can contradict: both of {@link #corroborated}'s legs can miss a recorded allergy that
	 *  really does name the drug, because the first sees only the witnesses of THIS rule's token and
	 *  the second is narrowed by {@link DrugReferenceService#findImpliedSubstances}. Measured on a
	 *  curated arrangement — an entry aliasing {@code ketoconazole} and ruling on another of its own
	 *  names, beside an allergy recorded as {@code Ketoconazole} that {@code matchesDrugName} accepts —
	 *  the record denied an allergy the chart holds. It claims no MECHANISM either, for the same
	 *  reason: neither leg fails only by a mid-word accident.
	 *
	 *  <p>Three constants rather than three literals so that every assertion about a section can read
	 *  the words a model reads. That is NOT what makes a reword visible — a suite that reads all three
	 *  from here compares a constant to itself, which was measured: rewording this one left the whole
	 *  api suite green. What makes a reword visible is one case asserting the LITERALS,
	 *  {@code InjectedContraindicationCorroborationTest.theThreeSectionLeadsAreTheWordsAModelReads} —
	 *  the arrangement {@code ChartSearchAiAuditSearchModeTest} uses for the four search-mode spellings
	 *  and for the same reason. */
	static final String UNCORROBORATED_READING_LEAD =
			" Matched in this patient's chart but not corroborated as a record of this drug: ";

	/** querystore's resource type for a drug-order document (its {@code DrugOrderRecordSerializer}
	 *  contract), which the chart carries through unchanged. The type the active-order
	 *  reconciliation looks for, and the type it asks the chart's completeness declaration about.
	 *
	 *  <p>Reads {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_ORDER} rather than spelling the
	 *  string again, because since issue #317 this filter and
	 *  {@code QueryStoreChartBuilder}'s order-currency scoping have to agree: the substantiation test
	 *  below AND-s the rendered prose with the builder's own order read, and that read is attached
	 *  only to records of the type the builder recognised. Spelled apart, a change to one would leave
	 *  the other's half of the AND with no mapping to look at — no error, no count out of place, just
	 *  a condition that quietly stops narrowing. */
	private static final String QUERYSTORE_DRUG_ORDER_TYPE = ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER;

	@Autowired
	private DrugReferenceService drugReferenceService;

	/** Test seam: production wires {@link DrugReferenceService} via {@link Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
	}

	@Autowired
	private DrugSafetyValidator drugSafetyValidator;

	/** Test seam: production wires {@link DrugSafetyValidator} via {@link Autowired}. */
	void setDrugSafetyValidator(DrugSafetyValidator drugSafetyValidator) {
		this.drugSafetyValidator = drugSafetyValidator;
	}

	/**
	 * The deterministic safety findings this question raises, as citable records.
	 *
	 * <p>Rationale, measured. {@link DrugSafetyValidator} computes the safety join correctly every
	 * time, but it runs <em>after</em> the answer, so the model is asked to re-derive a conclusion the
	 * module already holds — and it does not. The eval README records 0 joins across 21 baseline
	 * cells; on 2026-07-30 two live cases abstained with the evidence rendered, cited and provably
	 * readable (a patient on simvastatin asked about clarithromycin, 0/6; a patient with a severe
	 * aspirin allergy asked about ibuprofen, 0/4, while quoting the NSAID family list back verbatim
	 * when asked). Supplying more evidence is measurably not the lever, and three prompt variants
	 * regressed. So the finding becomes a record: reporting a line already in front of it is something
	 * the model does reliably.
	 *
	 * <p>Computed by calling {@code validate} with an EMPTY answer — the production path, unmodified.
	 * That makes the drugs in play exactly the question-named ones (the answer contributes none), and
	 * runs the contraindication and interaction passes while contributing no dose-excess warning,
	 * which is correct: a dose warning is about a dose the answer proposes, and there is no answer yet.
	 * No second definition of any safety rule is introduced.
	 *
	 * <p>A question that names no drug is therefore not automatically finding-free. Two checks in
	 * {@code validate} have no drug in play at all, and both reach this pass:
	 * <ul>
	 *   <li>the patient's own active orders screened against EACH OTHER when the question asks to be
	 *       screened for interactions (issue #113) — that gate reads the QUESTION only;</li>
	 *   <li>the patient's own active orders checked against their own allergy and condition records
	 *       (issue #143, {@code DrugSafetyValidator.addActiveOrderContraindications}) — whose SUBJECTS
	 *       read only the chart, and whose bound reads the response's subject matter. It does read the
	 *       drugs-in-play set, but only to skip what the loop above has already covered; see the
	 *       parenthetical below.</li>
	 * </ul>
	 * The first cannot differ between this pre-answer pass and the post-answer chips pass. The second
	 * CAN, and only in the safe direction: this pass calls {@code validate} with an EMPTY answer, so
	 * its subject matter is the question alone, while the chips pass adds the answer and the records it
	 * cited — a superset of the same texts, with the three question-derived widenings identical either
	 * way. Every test {@code SubjectMatter} applies is monotone in those texts, so the findings of this
	 * pass are a SUBSET of the chips beside the answer. That is the direction this property exists for:
	 * a finding in the prompt is never asserted without a chip beside the answer. The converse — a chip
	 * whose record was not in the prompt — the drug-in-play arm above has always allowed, since a drug
	 * only the ANSWER names cannot be known before there is an answer. Pinned by
	 * {@code SubjectMatterScopedContraindicationTest
	 * .theInjectorsPreAnswerFindingsAreASubsetOfTheChipsBesideTheAnswer}, over an arrangement whose
	 * pre-answer set is deliberately NON-empty — the first version of that case asserted zero findings
	 * and then iterated them, so it pinned nothing. (The #143 arm skips a drug already in play, so a
	 * question naming one of the patient's own orders moves that chip from this arm to the drug-in-play
	 * loop rather than adding or dropping one — the same chips, from a different arm.)
	 *
	 * <p><b>A THIRD channel carries this patient's own contraindication findings into the prompt, and
	 * it is neither of those.</b> {@link #contraindicationSections} marks a rendered clause "Recorded
	 * for this patient" on an injected {@code drug_reference} record, off
	 * {@code DrugSafetyValidator.recordedContraindicationKind} and — since issue #269, for a self-named
	 * allergy rule — {@link #corroborated}, which NARROWS that match rather than adding a second route to
	 * the marking: a clause the match reaches and corroboration does not takes a third section instead,
	 * claiming nothing about the patient. That method's javadoc
	 * used to call the marking exact — "which is exactly when the ledger raises a chip for that key" —
	 * and the order leg no longer satisfies it: {@link #matchingEntries} admits an active order that
	 * merely SHARES a class with a question-named drug, such an entry is not in the drugs-in-play set,
	 * so its chip goes through {@code SubjectMatter} while the clause is marked regardless. The
	 * statement the record makes is still true (the chart does record it), and the residue is bounded:
	 * no two entries of the bundled curated file share a level-4 subgroup or the shipped NSAID group,
	 * and the {@code ddinter} and {@code atc} sources publish no contraindication rules at all, so it
	 * takes a deployment-authored dataset relating two entries. Stated rather than left to be
	 * rediscovered, because it is the one place a patient-specific contraindication can still reach the
	 * prompt with no chip beside it, and a wider curated file is the documented expansion path.
	 *
	 * <p>The list is empty whenever the deterministic layer finds nothing, so a question that nothing
	 * bears on gains no record and its abstention survives by construction rather than by prompt
	 * wording — the direction issue #107 guards.
	 */
	List<SafetyWarning> preAnswerFindings(PatientClinicalContext context, String question) {
		return preAnswerFindings(context, question, null);
	}

	/**
	 * As {@link #preAnswerFindings(PatientClinicalContext, String)}, for a caller that has already
	 * resolved the patient's active orders to their reference entries and so can spare the validator
	 * deriving them a second time (issue #255) — which is {@link #injectRecords}, the only production
	 * caller that passes a non-null list; the two-argument overload above reaches this one too.
	 *
	 * @param orderEntries that resolution, or {@code null} to let the validator resolve for itself.
	 *        It must be the resolution of {@code context}'s own orders; see the validator's own
	 *        parameter javadoc for why the list travels and the enriched CONTEXT deliberately does
	 *        not.
	 */
	List<SafetyWarning> preAnswerFindings(PatientClinicalContext context, String question,
			List<DrugReference> orderEntries) {
		// Gated on the SAME toggle that gates the chips, because the two must never disagree. The
		// validator's public entry point checks this GP; the package-private overload used here does
		// not, so without this an operator setting validateAnswers=false would switch the chips off
		// while findings kept flowing into the prompt — the answer asserting "not safe due to a Major
		// interaction [232]" with no chip beside it. That is precisely the chip-versus-prose divergence
		// this whole change exists to remove, reappearing silently and only under a non-default config.
		if (drugSafetyValidator == null || !ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS)) {
			return Collections.emptyList();
		}
		return drugSafetyValidator.validate("", question, context, null, orderEntries);
	}

	/**
	 * Production entry point: injects reference records into {@code chart} for the
	 * given patient and question when the feature is enabled. Reads the patient's
	 * clinical context (active orders) for patient-driven matching. Returns the
	 * chart unchanged when the feature is off or nothing matches. Fails safe: the
	 * injection is an additive enrichment, so any unexpected error degrades to the
	 * unmodified chart rather than failing the query.
	 */
	public PatientChart inject(PatientChart chart, Patient patient, String question) {
		try {
			if (chart == null || !ChartSearchAiUtils.isDrugReferenceEnabled()) {
				return chart;
			}
			PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
			return injectRecords(chart, context, question);
		}
		catch (RuntimeException e) {
			log.warn("Drug-reference injection failed; leaving the chart unmodified — the answer path is never broken",
					e);
			return chart;
		}
	}

	/**
	 * Pure injection over an explicit clinical context — no OpenMRS context read —
	 * so the matching/rendering logic is unit-testable. Honours the
	 * {@code injectFromQuery} / {@code injectFromOrders} toggles.
	 */
	PatientChart injectRecords(PatientChart chart, PatientClinicalContext rawContext, String question) {
		// The same resolution DrugSafetyValidator.validate applies, for the same reason
		// (issue #136): orderedInteractionNotes decides which interactions to promote through
		// PatientClinicalContext.hasActiveDrug, so a context without the reference names here would
		// promote a different set of partners than the chips name — the exact chip-versus-prose split
		// that method's javadoc exists to rule out.
		// Resolved once and kept, and since issue #255 once for the whole PASS: this list is handed to
		// the validate below rather than derived a second time there. orderedInteractionNotes
		// groups a partner by the active-order ENTRY the rule names (issue #190 item 2), which is the
		// chip's own key, so these entries have to be the same resolution the names above come from —
		// two resolutions is how the record and the chip come to disagree about which rows are one
		// partner, which is the very thing that grouping exists to settle. Since issue #151 the same
		// list is also the order-driven leg's candidate set, which used to resolve itself and by a
		// narrower key — see matchingEntries.
		List<DrugReference> orderEntries = drugReferenceService.findForActiveOrders(rawContext);
		PatientClinicalContext context = drugReferenceService.withReferenceNames(rawContext, orderEntries);
		// The resolved context, which is what every other consumer in this method is handed. It is the
		// SAME answer rawContext would give for this particular reader, and that is worth stating rather
		// than leaving to be discovered: which row this response names a substance by is ranked off
		// getActiveDrugNames() — every name the orders carry, not the displays alone (issue #293) —
		// while withReferenceNames adds only
		// getActiveDrugReferenceNames() and copies the rest through. So passing rawContext here is
		// currently indistinguishable (measured by mutation, 2026-08-14: the whole suite stays green),
		// and the reason to pass this one is that a later change to what the ranking reads must not have
		// to notice that the injector was feeding it a different context from everything else.
		Map<DrugReference, SubstanceRendering> matched = matchingEntries(orderEntries, question, context);
		// Handed the resolution above rather than left to derive it again (issue #255): validate used to
		// resolve the same orders again, and this method already holds that answer.
		List<SafetyWarning> findings = preAnswerFindings(context, question, orderEntries);
		List<PatientClinicalContext.ActiveDrugOrder> unrepresented = unrepresentedActiveOrders(chart, context);
		if (matched.isEmpty() && findings.isEmpty() && unrepresented.isEmpty()) {
			return chart;
		}

		StringBuilder text = new StringBuilder(chart.getText());
		List<RecordMapping> mappings = new ArrayList<RecordMapping>(chart.getMappings());
		int index = mappings.size() + 1;

		// The patient's own records first, before the module's reference material and the findings
		// derived from it — the order the REST layer also renders references in (chart evidence
		// before reference material), so the clinician reads evidence then conclusion.
		for (PatientClinicalContext.ActiveDrugOrder order : unrepresented) {
			String rendered = renderActiveOrder(order);
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER,
					order.getUuid(), null, rendered));
			text.append("[").append(index).append("] ").append(rendered).append("\n");
			index++;
		}

		// Decided ONCE for the whole chart, not per record: it reads two global properties, and every
		// other GP read in this class is hoisted to a once-per-injection site for the same reason
		// LlmInferenceService gives for trusting the chart over a re-read — a flag that flips mid-loop
		// would leave record [7] carrying a patient-specific reading and record [8] not.
		ContraindicationReading reading = new ContraindicationReading(context);
		for (Map.Entry<DrugReference, SubstanceRendering> match : matched.entrySet()) {
			DrugReference ref = match.getKey();
			RenderedReference rendered =
					render(ref, orderEntries, reading, match.getValue(), findings);
			// The rendering's own bookkeeping rides on the mapping, not in the line — see
			// RenderedReference. The chart line and the mapping text stay byte-identical, so the
			// grounding verifier still compares against exactly what the model read.
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE,
					ref.getId(), null, rendered.text, rendered.source, rendered.withheldInteractions));
			text.append("[").append(index).append("] ").append(rendered.text).append("\n");
			index++;
		}

		// After the reference records, so a finding's citation number always follows the reference it
		// was derived from — the clinician reads cause then conclusion in chart order.
		for (SafetyWarning finding : findings) {
			String rendered = renderFinding(finding);
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING,
					ChartSearchAiUtils.resourceKey(finding.getType(), finding.getDrug()), null, rendered));
			text.append("[").append(index).append("] ").append(rendered).append("\n");
			index++;
		}

		// The drug-reference character total is here because that slice's SIZE is the thing issue #163
		// is about and the REST response cannot show it: the response returns only CITED references,
		// so a question injecting one near-duplicate record per route variant looked identical from
		// outside while spending several times the prompt budget. A count alone did not settle it
		// either — what crowds out chart records is characters — so an operator (or a verification
		// pass) can read both off one line.
		//
		// TWO character totals since issue #229, deliberately, because they answer two questions and
		// neither is the other's approximation. `referenceCharacters` is #163's: the drug-reference
		// ENTRIES' own text, which is what a near-duplicate-per-route-variant defect inflates, and it
		// is pinned to EXCLUDE the findings rendered beside them
		// (ReferenceRecordSubstanceCollapseTest). The slice is #229's: every record
		// ChartSearchAiUtils.referenceGroup calls reference material, which is the prompt budget the
		// module's own material spends and the figure the audit row carries. Both are printed and
		// labelled, so an operator correlating this line with a row is never comparing two
		// populations under one name — which is what happens if either number replaces the other.
		//
		// Guarded on isDebugEnabled because both totals are full walks of the mapping list evaluated
		// as ARGUMENTS, i.e. before SLF4J is consulted — one of them already was before this change,
		// and adding the second doubled it. The guard is not here for the cost, which is small enough
		// that quoting a figure would mean quoting the method and arrangement that produced it; it is
		// here because this codebase already uses the idiom where a log argument does real work
		// (ChartSearchServiceRouter, QueryStoreChartBuilder), and a discarded walk is easier to
		// notice than to justify.
		if (log.isDebugEnabled()) {
			ChartSearchAiUtils.ReferenceSlice slice = ChartSearchAiUtils.referenceSlice(mappings);
			log.debug("Injected {} active-order, {} drug-reference ({} chars) and {} safety-finding "
					+ "record(s) — reference slice {} record(s), {} chars — into chart for question '{}'",
					unrepresented.size(), matched.size(), referenceCharacters(mappings), findings.size(),
					slice.getRecords(), slice.getCharacters(), question);
		}
		PatientChart injected = new PatientChart(text.toString(), Collections.unmodifiableList(mappings),
				chart.getFocusIndices());
		// Carry the query-scoped stamp across the reconstruction. LlmInferenceService.searchStreaming
		// derives its KV-cache decision from PatientChart.isQueryScoped() precisely so a mode-flip /
		// GP-read race cannot mis-scope the persist; a fresh PatientChart defaults the flag to false,
		// so dropping it here would silently re-arm exactly that hazard for question-dependent slices
		// (the medications path — the flagship scoped intent — is also the likeliest drug-ref match).
		if (chart.isQueryScoped()) {
			injected.markQueryScoped();
		}
		// And the preFilter stamp, for the same reason one level along: the audit row's search mode
		// is derived from these two flags, so a rebuild that dropped this one would file a
		// focus-hinted prompt as a plain full chart — the wrong-signal failure issue #178 is about.
		if (chart.isPreFiltered()) {
			injected.markPreFiltered();
		}
		// Same reasoning for the completeness stamp: it is what tells a consumer whether a record's
		// ABSENCE from this chart is meaningful, and a fresh PatientChart declares nothing. Dropping
		// it would silently turn the rebuilt slice into one whose absences look uninformative.
		injected.markCompleteFor(chart.getCompleteResourceTypes());
		return injected;
	}

	/**
	 * The patient's active drug orders that {@code chart} carries no drug-order record for — the
	 * reconciliation of the drug-safety layer's {@code OrderService} read against the serialized
	 * chart the answer is grounded in (issue #118). WARNs when it finds any: the two reads
	 * disagreeing is an anomaly an operator needs to see, and it was previously silent.
	 *
	 * <p>Why this exists. The safety layer reads active orders straight from {@code OrderService}
	 * ({@link PatientClinicalContextBuilder}); the answer is grounded only in the querystore chart.
	 * That independence is a strength — it is what kept the chips correct when the index was behind
	 * — but nothing compared the two, so a divergence surfaced to the clinician as the module
	 * contradicting itself: a chip naming "active order simvastatin" beside an answer stating "No
	 * active medications are recorded." (3.7.1 standalone, HEAD {@code 13690b1}; three phrasings,
	 * so not phrasing sensitivity). Injecting the orders the chart cannot substantiate degrades the
	 * divergence into a missing-record repair instead: the model has the medication list in front of
	 * it, which is the mechanism #110 established for the safety findings.
	 *
	 * <p>Index drift was only the trigger, and fixing it belongs to querystore, which owns
	 * indexing. The same divergence is reachable from a query racing an in-flight index write, a
	 * failed indexing advice, or a partial reindex, so the reconciliation is not conditioned on any
	 * cause.
	 *
	 * <p>An order is substantiated by a chart record carrying its {@code Order} uuid (querystore
	 * indexes its {@code drug_order} document under exactly that — its
	 * {@code DrugOrderRecordSerializer} contract, so the match is exact), or failing that by a
	 * drug-order record whose text names the drug and that is not a record of an order that has
	 * ENDED. The name fallback is deliberate insurance in the
	 * conservative direction: were the uuid contract to change, uuid-only matching would report
	 * every order as missing on every query, and a WARN that fires always reports nothing. A
	 * <em>live</em> drug-order record naming the drug already tells the model the patient has an
	 * order for it, so there is nothing for the answer to deny and nothing to repair.
	 *
	 * <p>That insurance is unavailable for one class of order, and it is worth naming because the
	 * failure it guards against would be silent for it: an order no name could be read for
	 * ({@code ActiveDrugOrder.namedByCodesOnly}, issue #290) carries no names, so {@code namedIn} can
	 * never be true and it is matched by uuid ALONE. It is still reconciled — before #290 it never
	 * reached this list at all — but if the uuid contract drifts, this class of order is where the
	 * drift shows up as a permanent repair rather than as a caught discrepancy.
	 *
	 * <p><strong>Two ways that fallback used to over-match, both fixed, both of which suppressed the
	 * WARN as well as the repair</strong> — so the discrepancy became invisible rather than merely
	 * unrepaired, which is worse than not having the check.
	 *
	 * <p>First, the corpus was every {@code drug_order} record's text regardless of whether the
	 * record described a LIVE order. querystore indexes stopped and discontinued orders too (they
	 * are not voided). So in the renewal shape — stop Simvastatin 20mg, start Simvastatin 40mg, the
	 * replacement's document missing under drift — the stopped record's text named the drug, the new
	 * order counted as substantiated, and the answer reading "Stopped: …" correctly reported no
	 * active medication beside a chip naming one. Issue #118 verbatim, through the ordinary revise
	 * flow. Only records describing a live order now substantiate one — decided by the module's own
	 * order read where it has an answer ({@link RecordMapping#getOrderActive()}, issue #317) and by
	 * the rendered text where it does not ({@link #describesEndedOrder}). The first is what covers
	 * the same renewal shape when the old order lapsed by its {@code auto_expire_date} instead of
	 * being stopped, which querystore renders no marker for and prose therefore cannot see.
	 *
	 * <p>Second, the match itself was a plain substring test, so a short order name was found inside
	 * an unrelated word — an active {@code ASA} order read as substantiated by
	 * {@code "Drug order: Nasal spray"} — and a sibling record could mask an order whose name is a
	 * substring of it ({@code Aspirin} inside {@code Aspirin/Dipyridamole}).
	 * {@code ActiveDrugOrder.namedIn} now shares {@link DrugReference#containsWord} — the existing
	 * symmetric-boundary rule, which is also the rule alias-in-prose matching applies (since issue #330
	 * {@code matchesText} reaches it through the folded arity {@code foldedWordMatch} rather than
	 * through this method, which is the same rule and the same allowance) — rather than introducing a
	 * third matcher beside it and {@code matchesOrderName}. Which of those two it borrows is a real
	 * decision, not a coin toss; {@code namedIn}'s javadoc records why the symmetric one is the
	 * correct and the safe choice for this direction.
	 *
	 * <p>Only reconciled when the chart claims to carry every drug-order record
	 * ({@link PatientChart#isCompleteFor}). A query-scoped slice — the DEFAULT chart mode — omits
	 * everything outside the question's typed scope by design, so absence there says nothing about
	 * the index; treating it as drift would WARN and inject a medication list on nearly every
	 * query. A medications question does scope the slice to drug orders, which is precisely the
	 * question that produced the observed contradiction, so the default mode is still covered.
	 */
	static List<PatientClinicalContext.ActiveDrugOrder> unrepresentedActiveOrders(PatientChart chart,
			PatientClinicalContext context) {
		if (chart == null || context == null || context.getActiveDrugOrders().isEmpty()
				|| !chart.isCompleteFor(QUERYSTORE_DRUG_ORDER_TYPE)) {
			return Collections.emptyList();
		}

		Set<String> chartResourceUuids = new HashSet<String>();
		// One entry per admitted drug-order record, NOT one concatenated buffer. A record boundary is a
		// real boundary: an order name must be found inside ONE record that names it, never spanning
		// two. That distinction became load-bearing when ActiveDrugOrder.namedIn began collapsing
		// whitespace runs in its haystack (issue #293) — the separator this used to append was a
		// newline, which the collapse turns into a space, so a multi-word name could match across the
		// join and substantiate an order neither record names. Fail-OPEN, since substantiated means the
		// WARN and the injected record are both suppressed. Measured: one order named "Warfarin 5mg"
		// against records "Drug order: Warfarin" and "5mg tablet, 1 daily" was reported substantiated.
		List<String> liveDrugOrderTexts = new ArrayList<String>();
		List<RecordMapping> mappings = chart.getMappings();
		for (RecordMapping mapping : mappings == null ? Collections.<RecordMapping>emptyList() : mappings) {
			// Uuid matching is type-agnostic: a resource uuid is globally unique, so a record
			// carrying this order's uuid IS this order however it is typed.
			if (mapping.getResourceUuid() != null) {
				chartResourceUuids.add(mapping.getResourceUuid());
			}
			if (QUERYSTORE_DRUG_ORDER_TYPE.equals(mapping.getResourceType()) && mapping.getText() != null) {
				// Only records describing a LIVE order may substantiate one. A stopped or
				// discontinued order's record names the drug while saying the patient is no longer
				// on it, so counting it would answer "the chart already covers this order" with a
				// record that in fact tells the model the opposite.
				//
				// Two tests, AND-ed (issue #317). The chart builder now reads OrderService and
				// records, per drug-order record, whether that order is in force; a record is
				// admitted only where the prose and that answer both leave it live. Neither
				// overrules the other and each can only exclude more, which is what makes adding
				// the second safe: it cannot re-admit anything the prose already refused.
				//
				// Each covers what the other cannot. Prose cannot see an order that lapsed by its
				// auto_expire_date, because querystore renders no marker for one — the limitation
				// describesEndedOrder's own javadoc records, and the one that turns a lapsed record
				// into a substantiation for the live order that replaced it. And wherever the read
				// has no answer at all — SerializedRecord.getOrderActive() enumerates when, and is
				// the ONLY place that does — the text is the only evidence there is, which is also
				// what leaves the name fallback intact for the drifted-uuid record it was added for.
				String lower = mapping.getText().toLowerCase(Locale.ROOT);
				if (!describesEndedOrder(lower) && !Boolean.FALSE.equals(mapping.getOrderActive())) {
					liveDrugOrderTexts.add(lower);
				}
			}
		}

		List<PatientClinicalContext.ActiveDrugOrder> unrepresented =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			boolean substantiated = (order.getUuid() != null && chartResourceUuids.contains(order.getUuid()))
					|| namedInAny(order, liveDrugOrderTexts);
			if (!substantiated) {
				unrepresented.add(order);
			}
		}
		if (!unrepresented.isEmpty()) {
			// WARN, not INFO: the two reads disagreeing means the chart the answer is grounded in is
			// behind the authoritative order list, which an operator must be able to see. The chart
			// comes from the querystore index, so the usual cause is that index being behind — a
			// querystore concern, named here so the log points at the right module.
			log.warn("Active-order reconciliation: {} of {} active drug order(s) have no drug-order record "
					+ "in the retrieved chart, so the answer could deny a medication the drug-safety "
					+ "chips name; injecting them as records. The chart is built from the querystore "
					+ "index, so this normally means that index is behind the OrderService read "
					+ "(querystore owns indexing). Unrepresented: {}",
					unrepresented.size(), context.getActiveDrugOrders().size(), unrepresented);
		}
		return unrepresented;
	}

	/** @return true when {@code order} is named inside ONE of {@code recordTexts} — asked per record so
	 *          that a name cannot be assembled across a record boundary. See the comment in
	 *          {@link #unrepresentedActiveOrders} for why the boundary has to be structural rather than
	 *          a separator character. */
	private static boolean namedInAny(PatientClinicalContext.ActiveDrugOrder order,
			List<String> recordTexts) {
		for (String text : recordTexts) {
			if (order.namedIn(text)) {
				return true;
			}
		}
		return false;
	}

	/** Lowercased marker querystore renders a drug order's END date under. It emits
	 *  {@code ". Stopped: <date>"} for {@code getDateStopped()} <strong>only</strong> — measured by
	 *  running its {@code DrugOrderRecordSerializer} rather than reading it, and pinned by
	 *  {@code QuerystoreOrderTextMarkerTest.anAutoExpireDateAloneIsNotVisibleInTheRenderedText}.
	 *  This javadoc used to say "for both {@code getDateStopped()} and {@code getAutoExpireDate()}",
	 *  which is false and is the sentence that makes an order lapsed by its duration look, in the
	 *  text, exactly like one still being taken. That gap is why the reconciliation no longer relies
	 *  on this marker alone (issue #317). */
	private static final String QUERYSTORE_STOPPED_MARKER = ". stopped:";

	/** Lowercased marker for a DISCONTINUE order — the record of a drug ENDING. Keyed on the
	 *  {@code Action} VALUE, never on the {@code ". Action: "} label alone: querystore renders that
	 *  label on every drug-order record and {@code Order.action} defaults to {@code NEW}, so keying
	 *  on the label would treat every agreeing chart as drifted and fire the WARN on every query. */
	private static final String QUERYSTORE_DISCONTINUE_MARKER = ". action: discontinue";

	/**
	 * Whether {@code lowerRecordText} describes an order that has ENDED, so it must not substantiate
	 * a currently-active order of the same drug.
	 *
	 * <p><strong>This is no longer the only test</strong> (issue #317). Its one caller admits a record
	 * to the substantiation corpus only when this method AND {@link RecordMapping#getOrderActive()}
	 * both leave it live — the second being the module's own {@code OrderService} read, carried down
	 * from {@code QueryStoreChartBuilder.toSerializedRecords}. Conjunction, not precedence: neither
	 * can overrule the other, and each can only ever exclude more. So this method still decides on its
	 * own for every record the read cannot speak for — {@code SerializedRecord.getOrderActive()} is
	 * the one place those are enumerated, deliberately, so this javadoc cannot go stale as that list
	 * grows — and the read decides on its own for the order that lapsed by its
	 * {@code auto_expire_date}, which querystore renders no marker for.
	 *
	 * <p>Why prose was the only test until then, and why the second one is not the route this javadoc
	 * used to anticipate. querystore also carries the structural signal in its {@code QueryDocument}
	 * metadata ({@code putOrderBaseFields} puts {@code action}, {@code date_stopped} and
	 * {@code auto_expire_date} there), and that metadata was dropped upstream. But reading it would
	 * have meant re-deriving {@code Order.isActive()} — voided, activated, discontinued, expired —
	 * from three fields in a second implementation, and it would still rest on metadata surviving
	 * querystore's index round-trip, which is not exercised by this module's tests. Reading
	 * {@code OrderService} instead asks the same authority the drug-safety layer reads, so the chart
	 * and the chips answer off the same data rather than off the index and the database respectively.
	 * They do not share a call site — the safety layer screens with {@code getActiveOrders}, the chart
	 * with {@code Order.isActive()} — so this is agreement between two predicates, not agreement by
	 * construction, and it is pinned as such rather than assumed:
	 * {@code DrugOrderCurrencyMarkTest.theTwoPredicatesTheModuleAsksAgreeOnEveryOrderEitherCanEvaluate}
	 * drives both over one patient's whole drug-order list and asserts they classify each order
	 * alike, so a core change that splits them reddens a test instead of splitting the chart's prose
	 * from the chips built beside it. It excludes the one row where they differ by construction —
	 * {@code Order.isActive()} throws where the SQL answers — and asserts that exclusion.
	 *
	 * <p>Because it keys on rendered prose, the markers are pinned against the REAL querystore
	 * serializer's output in {@code QuerystoreOrderTextMarkerTest} — a wording change there fails
	 * loudly instead of silently reopening issue #118.
	 *
	 * <p><strong>What this method cannot see, and what now covers it.</strong> Running that serializer
	 * (rather than reading it) showed querystore does NOT render an auto-expire date into the text: an
	 * order that lapsed by {@code autoExpireDate} passing carries no end marker at all, so on this
	 * test alone it went on substantiating the live order that replaced it. It no longer does — the
	 * caller AND-s this with {@link RecordMapping#getOrderActive()}, and that answer excludes it
	 * (issue #317); {@code AuthoritativeEndedOrderSubstantiationTest} pins the exclusion. What is
	 * still true, and is why this method's own contract matters, is that the TEXT carries no end
	 * marker for such an order — {@code QuerystoreOrderTextMarkerTest} pins that, so it fails loudly
	 * if querystore ever starts rendering one, at which point this method covers auto-expiry on its
	 * own and that test's expectation flips.
	 */
	static boolean describesEndedOrder(String lowerRecordText) {
		return lowerRecordText != null
				&& (lowerRecordText.contains(QUERYSTORE_STOPPED_MARKER)
						|| lowerRecordText.contains(QUERYSTORE_DISCONTINUE_MARKER));
	}

	/**
	 * One unrepresented active order as a chart line. Shaped like querystore's own drug-order text
	 * ({@code "Drug order: <drug>. Dose: …"}) so the model reads it as the chart record it stands in
	 * for, and stated as plain fact: a hedge inside the record ("no matching record was retrieved")
	 * is the shape that made the model put an abstention clause in front of its own evidence in
	 * #110. The provenance is carried by the record's resource type and the WARN above, where an
	 * operator looks for it, rather than by prose in front of a clinician.
	 *
	 * <p><strong>Since #317 the resemblance is no longer exact, and since #315 that matters.</strong>
	 * A real {@code drug_order} record carries {@code PatientChartSerializer}'s order-status field
	 * WHERE THE MODULE COULD ESTABLISH IT — the field is absent on every null case that accessor
	 * enumerates, including a failed order read, which drops it from every record on the chart. So
	 * the two line shapes are not reliably distinguished by the field; this line simply never
	 * carries one, because it stands in for an order the chart has no record of. The
	 * #118 reconciliation means it routinely sits BESIDE an ended record naming the same drug — that
	 * is what {@code AuthoritativeEndedOrderSubstantiationTest} arranges — and #315's prompt rule
	 * fires on the ended record's field, and this line has no field for it to fire on. That is not
	 * the only difference between the two lines — the record-type prefix differs too — but it is
	 * the one the rule reads. Measured on exactly that chart, both question shapes, the #315 prompt
	 * and the base alike: the answer cites THIS line and calls the drug current, and neither arm
	 * attaches "no longer in force" to it (n=2 per cell, one arrangement, one local model — enough to
	 * refute the failure mode, not enough to call it impossible). Recorded because the next person to
	 * change either the field or this line's shape needs to know they are load-bearing together; ADR
	 * Decision 47 carries it as a residue rather than a guarantee.
	 */
	static String renderActiveOrder(PatientClinicalContext.ActiveDrugOrder order) {
		return "Active drug order: " + order.getDisplay() + ".";
	}

	/**
	 * Deduplicated union of question-driven and patient-driven matches, query matches first — <b>one
	 * entry per SUBSTANCE</b>, not one per reference row (issue #163, see {@code collect}).
	 *
	 * <p>The rendered row is carried together with the substance's whole row GROUP rather than the group
	 * being re-derived at the render site, because the group is gone by then: a renderer handed only the
	 * surviving row could not tell a substance filed as one row from a substance filed as four whose
	 * siblings the chart never named. Two things are read off it there and neither changes which row is
	 * rendered — {@link #rowAttribution} says which row that is, and {@link #otherRowDosing} says what the
	 * others publish. See {@link SubstanceRendering}.
	 *
	 * <p>Order-driven injection is <em>relevance-scoped</em>: an active-order reference is injected only
	 * when the question is about a specific drug clinically related to that order (sharing an ATC
	 * chemical subgroup or a curated cross-reactivity group — a real duplicate-therapy /
	 * cross-reactivity concern). An active medication unrelated to the asked-about drug — or a
	 * question that names no drug at all — is not injected: it would be noise that helps the
	 * clinician in no way. The safety validator reads active orders directly, so the chips never
	 * depend on this injection; the answer's medication awareness comes from the chart's own
	 * drug-order records, and from {@link #unrepresentedActiveOrders} for any the chart is missing.
	 *
	 * <p><b>Which orders are candidates is the caller's answer, not this method's (issue #151).</b> The
	 * order leg resolved its own candidates through {@code DrugReferenceService.findByActiveOrders} —
	 * the ATC-only primitive — while {@code DrugSafetyValidator.validate} has screened
	 * {@code findForActiveOrders} (ATC ∪ name) since issue #148 extracted that union into a method of
	 * its own (its name leg carries issue #147's recorded-name matcher). The split
	 * is not merely an inconsistency; it made this leg ask its two questions off two different keys:
	 * an order's RELEVANCE came from the reference ENTRY's own ATC codes ({@link #relatedToAny} reads
	 * {@code order.atcSubgroups()}), while its MEMBERSHIP came from the ORDER's concept mappings. Only
	 * the second is sparse — a dictionary maps a minority of drug concepts to ATC, while the knowledge
	 * base publishes ATC for most of its entries — so an order the relevance rule would have admitted
	 * at once could not be a candidate to be asked about. See
	 * {@code OrderDrivenInjectionResolutionTest}, which pins the shape rather than a coverage figure
	 * (the figure is a property of a deployment's dictionary and rots; the shape does not). Taking the
	 * list the caller already resolved fixes the key and removes the second resolution in one move: the
	 * injector cannot now disagree with the chips about which orders the patient has, because it no
	 * longer has an opinion of its own.
	 *
	 * <p>The gate is deliberately NOT widened with it, so this is a change of candidates and not of
	 * policy. What reaches the prompt is still only what the question's own drug is in a family with —
	 * see that test's unrelated-order and no-drug-question cases, which are what distinguish this from
	 * "inject every active order".
	 *
	 * <p><b>Two consequences of a wider candidate set that are not "one more record".</b> Neither is new
	 * in kind — both were already reachable through an ATC-mapped order — but both are now the common
	 * case rather than the rare one, so they are stated here rather than discovered.
	 * <ul>
	 *   <li>{@link #collect} folds over a SUPERSET, so a substance the question leg represented by a
	 *       route-qualified row can now be represented by the route-unspecified one — a different
	 *       {@code resourceId} on the wire and a different row's rules rendered. The direction is
	 *       monotone and is the one issue #163 asks for ({@link DrugReference#canonicalRow} never moves
	 *       AWAY from {@link DrugReference#namesNoRoute()}).
	 *       <p>Since issue #250 that fold has a second rung, so "monotone" no longer means it moves only
	 *       toward {@code namesNoRoute()}: it may also move LATERALLY, between two rows that agree on it,
	 *       toward the row the data files the substance under. All three moves the rung makes over the
	 *       shipped KB are of that kind. The consequence stated above is unchanged and now covers the
	 *       lateral case too — a different {@code resourceId} and a different row's rules rendered,
	 *       without any change in route-qualification.</p>
	 *       <p><b>Read "{@code namesNoRoute()}" here as the predicate and never as the raw string.</b>
	 *       Issue #250's second half corrected that predicate to say that a trailing parenthetical which
	 *       IS the name the data files the row's family under qualifies nothing, so an elected row may
	 *       now CARRY a trailing parenthetical while answering true. TWO shipped families elect such a
	 *       row — the influenza A/Vietnam antigen and the tick-borne encephalitis vaccine — and only the
	 *       first ALSO holds a plain sibling, which is the narrower class
	 *       {@link DrugReference#namesNoRoute()}'s javadoc and
	 *       {@code SubstanceNameRowTest.everyFamilyElectingAQualifiedRowOverAPlainSiblingIsNamedRatherThanCounted}
	 *       are about; do not read a figure for one as a figure for the other. This record's rendered row
	 *       moves for the A/Vietnam family, from a name carrying no parenthetical to one that does. The
	 *       invariant this bullet rests on is unchanged under the predicate; what it is NOT is a claim
	 *       about the shape of the string. The version of that invariant stated on raw syntax lives in
	 *       {@code SubstanceNameRowTest.aFamilyWithAnUnqualifiedRowElectsOneAndNoOtherRowSpeaksForIt}.</p>
	 *       <p>This used to add "and it makes this record agree with the chip layer's subject rather
	 *       than diverge from it". That was true when written and is <b>not</b> true now, which is
	 *       issues #237/#259: since issue #194 anchored a chip's subject on the CHART and issue #206
	 *       gave every arm one answer, the chip layer's subject is {@code interactionSubject}'s and
	 *       moving toward {@code namesNoRoute()} moves this record AWAY from it wherever the patient's
	 *       own record names a qualified row. What reconciles them is {@link #rowAttribution}, which
	 *       says which row this record is — deliberately rather than changing which row it renders,
	 *       for the coverage reason recorded there.</p></li>
	 *   <li>An order that IS the question's drug shares every subgroup with itself, so the order leg
	 *       collects it. Under the default configuration that is invisible — the question leg collected
	 *       it first — but with {@code injectFromQuery=false} the order leg is now what supplies it on a
	 *       dictionary that maps no ATC codes. That is the behaviour {@code docs/drug-kb-demo.md}'s
	 *       path-7 recipe documents as the feature, previously reachable only where the concept happened
	 *       to be mapped; no self-identity skip is added here for that reason.</li>
	 * </ul>
	 *
	 * @param orderEntries the reference entries the patient's active orders resolve to, already resolved
	 *        by {@link #injectRecords}. Never null — {@code findForActiveOrders} answers an empty list
	 *        for a null context, which is what makes this the same guard the removed {@code context !=
	 *        null} was. No null check is added for it deliberately: a null here would be swallowed by
	 *        {@code inject}'s catch and drop the WHOLE injection, question-driven records and safety
	 *        findings included, behind one WARN, so the coupling is pinned by a test that goes red
	 *        instead ({@code OrderDrivenInjectionResolutionTest
	 *        .aNullClinicalContextStillInjectsTheQuestionsOwnDrug})
	 * @param question the clinician's query, which drives the question leg and scopes the order leg
	 * @param context the patient's clinical context AFTER
	 *        {@link DrugReferenceService#withReferenceNames}, whose active-order NAMES anchor which row
	 *        this response names each substance by. May be null — "nothing known about the patient",
	 *        which ranks no row above another and so leaves {@link DrugReference#canonicalRow} deciding
	 *        exactly as it did before issues #237/#259; it is the same latitude
	 *        {@code orderEntries} has, and {@code ReferenceRecordRowAttributionTest
	 *        .aNullContextStatesNoAttributionAtAll} pins it
	 * @return the row each record RENDERS ({@link DrugReference#canonicalRow} over the rows this method
	 *         decided to inject), mapped to what the renderer needs to know about the substance's other
	 *         rows — see {@link SubstanceRendering}, which carries the rows this pass resolved and which
	 *         of them this response names the substance by. In insertion order — query matches first —
	 *         because every citation index in the injected chart depends on it.
	 */
	Map<DrugReference, SubstanceRendering> matchingEntries(List<DrugReference> orderEntries, String question,
			PatientClinicalContext context) {
		// One record per SUBSTANCE, not per reference row (issue #163). A per-call local, never a field —
		// issue #172's rule, for the reasons DrugReferenceService's class javadoc gives, NOT the
		// getAll() hot-reload this used to cite, which does not exist. The one that applies here is the
		// first: this bean is a Spring singleton, so a field memo would be one unsynchronized map shared
		// by every concurrent request.
		//
		// The substance's ROWS are kept rather than folded as they arrive (issues #237/#259): the row
		// this record RENDERS is still canonicalRow's, but which row this RESPONSE names the substance by
		// is DrugSafetyValidator.interactionSubject's answer over the whole group, and a pairwise fold
		// cannot produce it — that ranking takes a maximum over the group, so folding it two rows at a
		// time is a local variant of a decision CLAUDE.md says has exactly one definition.
		Map<Object, List<DrugReference>> bySubstance = new LinkedHashMap<Object, List<DrugReference>>();

		// The reference drugs the question itself names — drives question-driven injection AND scopes
		// the order-driven injection below, so it is computed regardless of the injectFromQuery toggle.
		//
		// findImpliedByQuery, not the bare findByQuery, since issue #209 — and through the same accessor
		// DrugSafetyValidator's drugs-in-play set uses, so a record can never be injected for a substance
		// no chip arm is checking. Prose carrying one alias of two substances injected a citable reference
		// record for each: a question about hydrocortisone injected `Hydrocortisone butyrate` as well, an
		// ester nobody named, spending prompt budget on a drug no chip stood behind.
		List<DrugReference> questionDrugs = drugReferenceService.findImpliedByQuery(question);

		boolean fromQuery = ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_INJECT_FROM_QUERY);
		if (fromQuery) {
			for (DrugReference ref : questionDrugs) {
				collect(bySubstance, ref);
			}
		}

		boolean fromOrders = ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_ORDERS,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_INJECT_FROM_ORDERS);
		if (fromOrders && !orderEntries.isEmpty()) {
			List<CrossReactivityGroup> groups = drugReferenceService.getCrossReactivityGroups();
			for (DrugReference ref : orderEntries) {
				// Only when the question names a drug this active order is clinically related to. A
				// question naming no drug has no relevance anchor, so nothing is injected here
				// (relatedToAny returns false for an empty questionDrugs).
				if (relatedToAny(ref, questionDrugs, groups)) {
					collect(bySubstance, ref);
				}
			}
		}

		// The row each record renders, mapped to the row this response NAMES its substance by. A
		// LinkedHashMap so the record order is the collection order the two legs produced, which is what
		// every citation index in this chart depends on. Keyed on the rendered row — DrugReference
		// defines no equals, so this is identity, and one substance contributes exactly one rendered row.
		// WHICH ROWS the subject is chosen among is a different question from which rows are injected,
		// and it must be the wider one. The two gates above decide what reaches the prompt; a substance
		// is called what this RESPONSE calls it whatever those gates say. Building the subject group from
		// the collected rows instead was a real defect: with injectFromOrders=false — or with an order
		// the relevance rule discards — the group loses the very row the chart names, so
		// interactionSubject takes its rows.size() == 1 short cut, answers the rendered row, and the
		// record falls silent in exactly the case issues #237/#259 are about. That is the "narrowed row
		// group" hazard DrugSafetyValidator.interactionSubject's own javadoc names, and it would have
		// made the clause configuration-dependent — the thing rowAttribution's javadoc argues must not
		// happen. So the group is questionDrugs plus every order-resolved row, ungated: the same INPUTS
		// DrugSafetyValidator.resolvedSubstanceRows folds for the chips in this same pre-answer pass.
		//
		// The inputs, not the grouping — the two key differently and saying otherwise would mislead the
		// next reader who widens this. `collect` falls back to getId() where a source publishes no
		// substance name, while `groupFor` falls back to substanceGroupKey()'s row identity, so two rows
		// sharing an id are ONE group here and TWO there. Nothing downstream can act on the difference:
		// rowAttribution refuses to speak at all when substanceKey() is null, which is exactly that case.
		//
		// Built only when something is being injected: its sole consumer is the loop below, so with no
		// matched substance it would walk every active order into a map nobody reads.
		Map<DrugReference, SubstanceRendering> subjects =
				new LinkedHashMap<DrugReference, SubstanceRendering>();
		if (bySubstance.isEmpty()) {
			return subjects;
		}
		Map<Object, List<DrugReference>> subjectRows = new LinkedHashMap<Object, List<DrugReference>>();
		for (DrugReference ref : questionDrugs) {
			collect(subjectRows, ref);
		}
		for (DrugReference ref : orderEntries) {
			collect(subjectRows, ref);
		}

		for (Map.Entry<Object, List<DrugReference>> substance : bySubstance.entrySet()) {
			List<DrugReference> injected = substance.getValue();
			// The substance's rows as the whole pass resolved them, falling back to the injected ones for
			// a substance no leg above put in the wider map — which cannot happen today, since every row
			// reaching bySubstance came from one of the two lists, and is written as a fallback rather
			// than an assertion because a future third leg would otherwise silently get a null group.
			List<DrugReference> group = subjectRows.get(substance.getKey());
			List<DrugReference> rows = group == null ? injected : group;
			DrugReference rendered = DrugReference.canonicalRow(injected);
			subjects.put(rendered,
					new SubstanceRendering(rows, chartAnchoredSubject(rendered, rows, context)));
		}
		return subjects;
	}

	/**
	 * What one injected record needs to know about the substance it stands for that its own row cannot
	 * tell it — the rows the pass resolved, and which of them this response names the substance by.
	 *
	 * <p>Both are facts about the row GROUP, and the group is gone by the time {@code render} holds one
	 * row of it: a renderer handed only the surviving row cannot tell a substance filed as one row from
	 * one filed as four whose siblings the chart never named. They are computed together, once, by
	 * {@link #matchingEntries} — so the two things this record says about its siblings (which row it is,
	 * and what the others publish) are answers over ONE row set and cannot disagree about what that set
	 * is.
	 */
	static final class SubstanceRendering {

		/** Every row of the substance THIS PASS resolved — {@code findImpliedByQuery}'s rows and the
		 *  patient's own order-resolved ones, ungated by the injection toggles for the reason
		 *  {@link #matchingEntries} gives. Never empty: the rendered row is always one of them. */
		final List<DrugReference> rows;

		/** The row this response names the substance by when the patient's own record is what chose it,
		 *  else null — {@link #chartAnchoredSubject}'s answer, read only by {@link #rowAttribution}. */
		final DrugReference subject;

		SubstanceRendering(List<DrugReference> rows, DrugReference subject) {
			this.rows = rows;
			this.subject = subject;
		}
	}

	/**
	 * @return the row {@code rows}' substance is named by in this response when the patient's own record
	 *         is what chose it, else {@code null} — "the chart names no row of this substance in
	 *         particular", which is the common case and the one {@link #rowAttribution} must stay silent
	 *         on.
	 *
	 *         <p><b>Why the chart is asked directly (issue #250).</b>
	 *         {@link DrugSafetyValidator#interactionSubject} composes two rankings — the chart's claim
	 *         first, then {@link DrugReference#canonicalRow} among the rows tied on it — and both steps
	 *         answer, always, so its answer alone cannot say WHICH step decided. This used to infer that
	 *         by comparing its row against the fold's: where they agreed, no recorded name had
	 *         out-claimed any other row. That was a PROXY, and it held only while the fold could not
	 *         reach the row the chart names. Issue #250's second rung made the fold reach exactly that
	 *         row for three shipped substances, and the proxy then read agreement as "the chart chose
	 *         nothing" — suppressing this clause on the arrangement that needs it most, where the record
	 *         renders one row and every chip beside it names another. So the question is now put to the
	 *         chart itself ({@link DrugSafetyValidator#recordNamesMoreStrongly}, asked of the row this
	 *         record RENDERS): does the patient's own record claim the subject more strongly than the row
	 *         published here? That is what this clause's sentence asserts, and unlike the proxy it cannot
	 *         drift as the fold's rungs change. Still a READ of the existing accessors and not a third
	 *         ranking — the composition that decides a subject stays in one place.
	 *
	 *         <p>It matters because {@code rowAttribution}'s sentence says "the row this patient's record
	 *         names". Widening the group to every row the pass resolved (see the caller) makes the fold
	 *         able to move the subject off the rendered row on its own — a question naming one
	 *         route-qualified row while the patient is on an order whose display name ties every row, for
	 *         instance — and calling that "the row this patient's record names" is a claim about a chart
	 *         that said no such thing.
	 *
	 *         <p><b>KNOWN RESIDUE, stated rather than discovered.</b> Answering null here is silence, not
	 *         agreement, and one divergence survives it: a question resolving only a qualified row renders
	 *         that row's record, while the chip layer — whose group is the wider union — names the
	 *         substance by the row the fold elects. The chart chose neither, so this stays quiet and the
	 *         two surfaces still differ with nothing saying so. That is issue #237's shape surviving in
	 *         the one case this method deliberately does not speak to, and
	 *         {@code ReferenceRecordRowAttributionTest.aSubjectTheFoldMovedRatherThanTheChartIsAttributed
	 *         ToNobody} pins the silence rather than blessing the divergence.
	 *
	 *         <p>Its MECHANISM changed with issue #250 even though the residue did not, so do not read the
	 *         older account of it: this used to compare the fold over the SUBJECT group against the row
	 *         rendered from the narrower INJECTED set, and the divergence was partly an artefact of the
	 *         comparison straddling two row sets. It no longer straddles them — the rendered row is passed
	 *         in — so what is left is the honest core of it: where no recorded name claims either row, no
	 *         sentence can truthfully say the chart preferred one, whatever the fold decided.
	 *
	 *         <p>Closing it needs a SECOND sentence rather than a wider guard: the existing one would be
	 *         false there, because no recorded name chose the row, so the choice is between a differently
	 *         worded clause for the fold-moved case and leaving it. That is a wording decision with no
	 *         measurement behind it yet, and this module does not invent clinician-facing vocabulary on a
	 *         guess — see {@code DrugSafetyValidator.ceilingAttribution}, whose wording was settled by
	 *         issue #244 against measured alternatives.
	 */
	private static DrugReference chartAnchoredSubject(DrugReference rendered, List<DrugReference> rows,
			PatientClinicalContext context) {
		DrugReference subject = DrugSafetyValidator.interactionSubject(rows, context);
		return DrugSafetyValidator.recordNamesMoreStrongly(subject, rendered, context) ? subject : null;
	}

	/**
	 * Record {@code ref} among the rows of its substance, from which the caller picks the one this
	 * record renders ({@link DrugReference#canonicalRow}) and the one this response names the substance
	 * by ({@link DrugSafetyValidator#interactionSubject}), and which it then carries whole so the record
	 * can state what the others publish ({@link #otherRowDosing}).
	 *
	 * <p>The rows are KEPT rather than folded as they arrive (issues #237/#259). The first two choices
	 * were a pairwise fold here until the second was needed, and only the first can be made that way:
	 * {@code canonicalRow} is associative over pairs, while the subject ranking takes a MAXIMUM over the
	 * group's claims and then folds among the rows tied on it, which two rows at a time cannot compute.
	 * Approximating it pairwise would be a local variant of a decision CLAUDE.md gives exactly one
	 * definition, and it is the variant that answers differently on precisely the families this is for.
	 *
	 * <p><b>Why the key is the substance (issue #163).</b> This map was keyed on {@code ref.getId()}, and
	 * route/formulation variants of one substance deliberately carry DISTINCT ids — the {@code ddinter}
	 * parser falls back to the DDInter id when the RxCUI is shared, precisely so citations stay
	 * unambiguous — so one question word injected one near-duplicate record per variant, each rendering
	 * up to {@link #MAX_INTERACTION_RENDER_CHARS} of interaction prose. That is prompt budget spent
	 * several times over on one drug (issues #95, #99), and several differently-worded copies of one fact
	 * handed to a model that miscopies them (#142). Invisible from the REST response, which returns only
	 * CITED references, which is why it survived several live verification passes.
	 *
	 * <p><b>The id remains the fallback</b>, rather than {@link DrugReference#substanceGroupKey()}'s
	 * object identity, because THIS map's job includes what the id was originally chosen for: the
	 * surviving entry's id becomes the injected {@link RecordMapping}'s resourceId, so two records
	 * sharing an id would make a citation ambiguous. A source publishing no substance name (the curated
	 * {@code json} file, the {@code atc} adapter) therefore keeps exactly the de-duplication it had.
	 *
	 * <p><b>What the collapse gives up</b>, measured rather than assumed. The surviving row's rules are
	 * the ones the record renders, and a sibling can carry a partner the survivor does not: over the
	 * shipped KB, 80 of the 121 multi-row substances have at least one such partner, 2627 in total, a few
	 * of them lopsided ({@code Olopatadine} 112 partners against its family's 397) — measured 2026-08-06,
	 * re-measure before relying on the figures.
	 *
	 * <p><b>What that costs, per leg, because the two legs differ.</b> For the QUESTION-driven leg it
	 * costs breadth only: the substance is then also a drug in play, so a partner the patient is on whose
	 * rule clears the severity floor raises a chip whatever row carries it (the chips read every row off
	 * {@code getAll()}, and since issue #162 they read the substance's rows as one subject), and since
	 * issue #110 that chip is injected as its own citable safety-finding record carrying the rule's
	 * mechanism note verbatim. What the sibling rows lose there is the {@code Interactions:} tail — the
	 * section {@code render} already truncates to one compact representative whenever a relevant partner
	 * is promoted.
	 *
	 * <p>For the ORDER-driven leg no chip stands behind it, and that is worth stating rather than being
	 * covered by the sentence above. That leg needs {@link #relatedToAny}, hence a question that named a
	 * drug, and the substance it injects is an ACTIVE ORDER rather than a drug in play — so
	 * {@link DrugSafetyValidator}'s drug-in-play arm does not see it, and the one arm that does cover
	 * (active order, active order) pairs is gated on the question naming NO drug, which excludes this
	 * leg by construction. A rule between two of the patient's own medications that sits only on a
	 * sibling row is therefore prose this record no longer carries and no chip replaces. Narrower than
	 * it sounds — it needs the question's drug to be ATC-related to one order and that order's substance
	 * to be multi-row — but it is a real reduction in what the prompt carries, not a re-presentation of
	 * it. Less narrow since issue #151 than when that was written: the leg's candidate set is now every
	 * order the reference data RESOLVES — by ATC code or by display name — rather than the ATC-mapped
	 * subset of them, so a deployment whose dictionary maps few drug concepts reaches this residue where
	 * it previously could not reach the leg at all.
	 *
	 * <p>Issue #174's {@code orderedInteractionNotes} sweep did NOT close that residue and was never
	 * going to: it collapses several rules of ONE entry that name one PARTNER, which is the other
	 * axis. Widening this map to read every row of a substance is the change that would close it, and
	 * it is a different one — the surviving entry's id is this record's citation id, so a record
	 * assembled from several rows would have to choose one and then cite prose the chosen row does not
	 * carry.
	 */
	private static void collect(Map<Object, List<DrugReference>> bySubstance, DrugReference ref) {
		Object substance = ref.substanceKey();
		Object key = substance != null ? substance : ref.getId();
		List<DrugReference> rows = bySubstance.get(key);
		if (rows == null) {
			bySubstance.put(key, rows = new ArrayList<DrugReference>());
		}
		// Both legs can reach one row (a question naming a drug the patient is also on), and a row
		// listed twice would make it its own sibling — a substance whose rows all fold to one name, which
		// is exactly the shape rowAttribution must stay silent on. Identity, not equals: DrugReference
		// defines none, and these are the same objects from the same parsed dataset either way.
		if (!containsSame(rows, ref)) {
			rows.add(ref);
		}
	}

	/** @return whether {@code rows} already holds THIS object. {@link DrugReference} defines no
	 *          {@code equals}, so {@code contains} would answer this anyway — written out because the
	 *          answer being identity is the point rather than an accident of the class, and a later
	 *          {@code equals} on {@link DrugReference} must not silently merge two rows here. */
	private static boolean containsSame(List<DrugReference> rows, DrugReference ref) {
		for (DrugReference row : rows) {
			if (row == ref) {
				return true;
			}
		}
		return false;
	}

	/** @return true when {@code order} shares an ATC level-4 subgroup — or, failing that, a curated
	 *          cross-reactivity group — with any of {@code questionDrugs}: a genuine class/family
	 *          relationship (duplicate therapy / cross-reactivity) that makes the active-order
	 *          reference relevant to the question.
	 *
	 *          <p>Every code here is the ENTRY's own, never the order's: {@code order} is the reference
	 *          row the patient's order resolved to, and {@code atcSubgroups()} reads what the knowledge
	 *          base publishes for it. That is why widening the candidate set to name-resolved orders
	 *          (issue #151) needed nothing here — an entry reached by name carries the same codes as one
	 *          reached by code, since they are the same rows — and it is why an entry the KB gives no
	 *          ATC code and no curated group is unrelated to everything and injects nothing. That last
	 *          case is now the reachable one rather than a formality: an ATC-keyed candidate set could
	 *          only ever contain entries with codes, while a name-keyed one routinely resolves entries
	 *          the KB classifies nowhere (in the full 19 MB DDInter KB, {@code Tiotropium} and
	 *          {@code Ipratropium} both publish no ATC code at all — measured 2026-08-13 through
	 *          {@link DrugReference#normalizedAtcCodes}). Such an order is silent here, exactly as an
	 *          unrelated one is, and the curated {@link CrossReactivityGroup} file cannot rescue it
	 *          either — {@link CrossReactivityGroup#groupsOf} answers nothing for an entry with no
	 *          codes, since a group is defined by ATC prefixes. Only the entry's own data can. */
	private static boolean relatedToAny(DrugReference order, List<DrugReference> questionDrugs,
			List<CrossReactivityGroup> groups) {
		Set<String> orderSubgroups = order.atcSubgroups();
		List<CrossReactivityGroup> orderGroups = CrossReactivityGroup.groupsOf(order, groups);
		if (orderSubgroups.isEmpty() && orderGroups.isEmpty()) {
			return false;
		}
		for (DrugReference q : questionDrugs) {
			if (!Collections.disjoint(orderSubgroups, q.atcSubgroups())
					|| CrossReactivityGroup.sharedGroup(orderGroups, q) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The prefix every injected safety-finding chart line carries — the token
	 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT}'s record-type rule keys on ("Records beginning with
	 * "Safety finding" ARE about this patient"). Shared with that prompt's format demonstration for
	 * the same reason {@code LlmProvider.FOCUS_HINT_LABEL} is shared: if the demonstration and the
	 * real line drift, the few-shot teaches a record shape the model never sees at inference time,
	 * and the verdict lead demonstrated on it (#112) stops transferring to the real finding. Pinning
	 * both sides to independent literals would let that drift ship green.
	 */
	public static final String FINDING_PREFIX = "Safety finding — ";

	/**
	 * What an injected finding licenses, stated in the record itself (issue #283) — the clause for a
	 * finding that is a reason to withhold the drug, which is every contraindication and every
	 * interaction {@link DrugSafetyValidator#licensesWithholding} answers for. Public, and shared with
	 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT}'s graded safety rule and its format demonstration,
	 * for the reason {@link #FINDING_PREFIX} is: the rule tells the model to follow the call the
	 * finding STATES, so a reworded clause here with a copy of the old wording in the prompt would
	 * leave the model matching on a sentence no record carries any more — and every test green.
	 */
	public static final String STRENGTH_WITHHOLD = " This finding is a reason to withhold it.";

	/**
	 * The counterpart clause for a finding that is not (issue #283): the strength a Minor or
	 * Unknown-rated interaction actually licenses, and the only finding that ever carries it. Shared
	 * with the prompt for the reason {@link #STRENGTH_WITHHOLD} is.
	 */
	public static final String STRENGTH_CAUTION =
			" This finding is a caution to note, not a reason to withhold it.";

	/**
	 * How a contraindication finding's rule reached this patient's chart, stated in the finding
	 * itself where nothing corroborates that match as a record of the drug (issue #308) — the
	 * {@code safety_finding} counterpart of {@link #UNCORROBORATED_READING_LEAD}, which issue #269
	 * gave the {@code drug_reference} record injected beside it.
	 *
	 * <p><b>Why the two are not one string.</b> They report one fact about one chart and they must
	 * keep saying the same thing, but that lead is a colon-terminated section HEAD whose object is
	 * supplied by the clause after it, so a well-formed sentence cannot be a substring of it — and
	 * deriving one from the other would put the single case that pins that literal
	 * ({@code InjectedContraindicationCorroborationTest.theThreeSectionLeadsAreTheWordsAModelReads})
	 * silently in charge of a second channel it was never written for. What binds them is this
	 * pairing of javadocs; each is pinned in its own file, and
	 * {@code UncorroboratedFindingProvenanceTest.theClauseIsTheWordsAModelReads} is this one's.
	 *
	 * <p><b>Three properties of the wording, each load-bearing.</b> It NAMES its subject, so it is not
	 * a dangling participle whose implied subject is the previous sentence's object. It OPENS by
	 * asserting that a record was matched, which is the negation of the antecedent of the opposite
	 * branch in {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} — "when no record addresses the drug or
	 * intervention asked about, the whole answer is one sentence stating that the records do not
	 * address it" — that a clause reading only "not a record of this drug", inside a record type the
	 * same prompt says IS about this patient, would sit close to; a flip to that branch would be
	 * fail-open. And it says what the MODULE established rather than a categorical about the chart,
	 * ADR Decision 42's own measured constraint, because both corroborating legs can miss an allergy
	 * the chart really holds.
	 *
	 * <p><b>Additive, and that is the decision rather than a detail.</b> The finding still states
	 * {@link #STRENGTH_WITHHOLD}, and a third strength class between withholding and a caution — the
	 * obvious fix — was refuted before any code was written. <b>ADR Decision 44 is canonical for what
	 * it costs</b>; the measurements it rests on are not restated here, because three copies of a
	 * rejected-alternative argument is how this repo has come to contradict itself before.
	 *
	 * <p>Package-private, like the three section leads and unlike {@link #STRENGTH_WITHHOLD}. That
	 * difference is the whole of what this clause is NOT: the strength clauses are public because
	 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} — another package — carries them verbatim in its
	 * graded-safety rule and its format demonstrations, and this one has no such consumer. It adds no
	 * branch to that prompt, because it introduces no new call for the prompt to teach; it is evidence
	 * the model reads inside a finding the prompt already instructs it to carry whole. Anything that
	 * makes it public is teaching the prompt a third class, which ADR Decision 44 refused.
	 */
	static final String FINDING_UNCORROBORATED_MATCH =
			" This module matched that record in this patient's chart by its wording alone and could "
					+ "not corroborate it as a record of this drug.";

	/**
	 * How a finding's substances reach THIS patient's chart, stated in the finding itself where the
	 * orders they were resolved from record no name of them (issue #349) — the lead of a clause whose
	 * items follow it, {@code "; "}-joined, each reading {@code "<Substance> from <order display>"}.
	 *
	 * <p><b>What it is for.</b> A finding names its substances in the KNOWLEDGE BASE's vocabulary,
	 * which is right and is #339's settlement. Where the module reached those substances from an active
	 * order through its WHO ATC map alone, every chart record the model can read names that order
	 * something else — a local brand — so the finding is unciteable by construction: nothing in the
	 * prompt connects it to any record of this patient, and the model resolves that by disclaiming.
	 * Measured on the 3.7.1 standalone (issue #349, three runs, identical): a Major
	 * {@code Simvastatin x Clarithromycin} chip off orders named {@code Zolvimix} and {@code Klarizom}
	 * beside an answer reading "The records do not address interactions between the patient's current
	 * medications." Nothing else in that prompt could have supplied the connection — a screening
	 * question names no drug, so {@link #matchingEntries} scopes in no {@code drug_reference} record,
	 * and the chart already substantiated both orders, so {@link #unrepresentedActiveOrders} injected
	 * none either.
	 *
	 * <p><b>Why the wording is a RESOLUTION and not an identity.</b> "Clarithromycin from Zolvimix"
	 * says this module read that substance off that order, which is true of a combination brand
	 * carrying several substances' codes. It deliberately does not say the prescription IS the
	 * substance, nor that the substance's class classifies the prescription — the false claim #339's
	 * reverted rounds 5-6 made by naming a constituent, in this very text, which {@link #renderFinding}
	 * copies verbatim into a citable record carrying {@link #STRENGTH_WITHHOLD}. So the clause is
	 * additive beside the printed name rather than a second answer to which name to print, and nothing
	 * in {@code DrugSafetyValidator}'s naming ladder is consulted or re-decided.
	 *
	 * <p><b>The lead ENDS a sentence and the items are their own</b>, which is a fidelity decision
	 * rather than a style one. {@code ReferenceProseFidelityCheck} treats a record sentence reproduced
	 * WHOLE as faithful however the answer goes on, and covers the seam and not a clause's interior; the
	 * lead alone clears that check's {@code MIN_REPRODUCED_WORDS} floor, so joined to the items by a
	 * colon it would put its own invariant boilerplate inside a sentence whose interior carries order
	 * displays the live model is on record paraphrasing and misspelling. Ending it restores the exit for
	 * the half that never varies. The items' own interior is still uncovered, and that is inherent to
	 * carrying variable content rather than something a wording fixes.
	 *
	 * <p><b>Flat, {@code "; "}-joined and number-agnostic.</b> One item and three items read alike, so
	 * no branch decides between "order" and "orders" — and a substance the pass resolved from two
	 * orders gets one item per order rather than a conjunction the prose would have to inflect. The
	 * legibility cost is real and is the same trade #339 settled: on a combination brand two items can
	 * name one prescription twice, which is a reading rather than a false claim.
	 *
	 * <p>Package-private, like the three section leads and unlike {@link #STRENGTH_WITHHOLD}: that
	 * constant is public because {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} carries it verbatim, and
	 * this clause has no such consumer. It teaches the prompt no new call — it is evidence inside a
	 * record the prompt already instructs the model to carry whole, exactly as
	 * {@link #FINDING_UNCORROBORATED_MATCH} is.
	 *
	 * <p><b>Prompt-facing ONLY, and that is why {@code DrugSafetyValidator.StatedInteractionChips}
	 * does NOT key on it.</b> Adding it to that key was tried and reverted in review: the key decides
	 * which chips are emitted, so it reaches {@code PairChipExtent}'s counts and, through
	 * {@code ChartSearchAiUtils.resourceKey}, whether two injected findings share one resource uuid —
	 * which would make this clause decide wire CONTENT while every claim about it says it does not.
	 * A collapsed chip therefore carries the survivor's bridge, which is the same residue ADR
	 * Decision 63 already accepts for that collapse ("what it gives up is WHICH constituent").
	 * The chip's detail, its rank and the {@code safetyWarnings} wire shape are
	 * untouched, which is issue #283's own scoping;
	 * {@code InteractionFindingChartOrderBridgeTest.theChipDetailIsTheWordsItAlwaysWas} pins it, and
	 * {@code .theClauseIsTheWordsAModelReads} pins these words.
	 */
	static final String FINDING_CHART_ORDER_LEAD =
			" This module resolved the substances named here from this patient's own active orders. ";

	/**
	 * One deterministic finding as a chart line. The detail text is reused verbatim — it is the same
	 * string the chip carries, so the prose and the chip cannot describe the same finding differently —
	 * and the finding then states what it licenses, so the answer's opening
	 * call rests on what the deterministic layer decided rather than on the model's reading of a
	 * severity word inside the prose (#283). Every finding states one; see {@link #strengthClause} for
	 * why silence is not a third answer.
	 *
	 * <p><b>Of the chip THIS pass raised</b> — so the sentence above is about a pass and not about a
	 * request. Between issue #236 and issue #238 the two could differ for one arm — the question-pair
	 * arm; {@code DrugSafetyValidator.SubstanceSubjects} and ADR Decision 49 record which and why, and
	 * ADR Decision 53 records issue #238 closing it — and that is deliberately not restated here.
	 *
	 * <p>"Verbatim" is of the detail, not of the whole line: a detail that does not already end a
	 * sentence gains a full stop, so the clause after it reads as its own sentence rather than running
	 * on. That is {@link DrugSafetyValidator#endSentence}, shared with the chip's own fold rather than
	 * copied, and it is the only way the record's copy of the detail differs from the chip's.
	 *
	 * <p>Since issue #308 a contraindication finding whose match against the chart nothing
	 * CORROBORATES also states how it was matched, between the detail and the strength clause — see
	 * {@link #FINDING_UNCORROBORATED_MATCH}, and {@code SafetyWarning.restsOnAnUncorroboratedChartMatch}
	 * for where that answer is decided. Not "reached the chart by bare containment": that is one LEG of
	 * the union and it is the chip rank's condition, not this one, so a rule at the demoted rank whose
	 * substance some other recorded allergy reaches carries no clause. It is a second clause and not a
	 * second CALL — the strength clause is unchanged, so everything the paragraph above says about the
	 * answer's opening call still holds.
	 *
	 * <p>Since issue #349 an INTERACTION finding whose substances the chart records only under other
	 * names also states which of this patient's active orders each was resolved from, ahead of both
	 * clauses above — see {@link #FINDING_CHART_ORDER_LEAD} for why the finding is otherwise unciteable,
	 * and {@code DrugSafetyValidator.chartOrderBridges} for which orders may be named. It adds words and
	 * moves no call: the strength clause is unchanged and the chip's own detail is untouched.
	 *
	 * <p>The full-stop guard asks about ALL THREE clauses, and TWO of its three halves cannot fire today. Only a
	 * contraindication can carry provenance and {@link #strengthClause} answers one unconditionally for
	 * that type, so a provenance clause never arrives without a strength beside it; and only an
	 * interaction can carry a BRIDGE, for which that method answers one unconditionally too. Said
	 * rather than left to be rediscovered — mutating the guard to {@code strength.isEmpty()} alone
	 * leaves the whole api suite green, and so does dropping the bridge term. All three are kept because
	 * the clauses are independent by construction, and a type carrying one without a strength is the
	 * shape {@link #strengthClause} already warns a future caller it must write for.
	 */
	static String renderFinding(SafetyWarning finding) {
		String strength = strengthClause(finding);
		// Between the detail and the strength clause, so the clause stays SENTENCE-FINAL — which is
		// where the prompt's own two format demonstrations put it, and what its graded-safety rule
		// reads to decide how the answer opens. Provenance is about the evidence and belongs beside
		// the sentence it qualifies; the call the finding states is the last word either way.
		String provenance = finding.restsOnAnUncorroboratedChartMatch()
				? FINDING_UNCORROBORATED_MATCH
				: "";
		// Ahead of provenance, and for a reason rather than by chance: this clause says what the names
		// INSIDE the detail stand for in this chart, so it reads as a gloss on the sentence it follows,
		// while provenance qualifies how a rule reached the chart at all. The two cannot co-occur today
		// (only a contraindication carries provenance and only an interaction carries a bridge), so
		// nothing behavioural pins the order — measured: swapping these two leaves the whole build
		// green, while moving either AFTER the strength clause reddens
		// InteractionFindingChartOrderBridgeTest.theStrengthClauseStaysSentenceFinal and cases in
		// UncorroboratedFindingProvenanceTest. It survives on this comment.
		String chartOrders = chartOrderClause(finding);
		String detail = strength.isEmpty() && provenance.isEmpty() && chartOrders.isEmpty()
				? finding.getDetail()
				: DrugSafetyValidator.endSentence(finding.getDetail());
		return FINDING_PREFIX + finding.getDrug() + ": " + detail + chartOrders + provenance + strength;
	}

	/**
	 * The bridge clause of one finding, or the empty string — {@link #FINDING_CHART_ORDER_LEAD}
	 * followed by one {@code "<Substance> from <order display>"} item per attribution, {@code "; "}
	 * -joined and closed with a full stop (issue #349).
	 *
	 * <p>Rendered here and decided in {@code DrugSafetyValidator.chartOrderBridges}, which is where
	 * both the scoping argument and the silence test live. This method adds no rule of its own: an
	 * empty list renders nothing, and every list it is handed is one the validator already decided may
	 * be printed. That division is the same one {@link #FINDING_UNCORROBORATED_MATCH} has — the words
	 * are the injector's, the answer is the validator's — and it is what stops a second copy of the
	 * conditions appearing on the render side.
	 *
	 * <p>The items carry {@link SafetyWarning.ChartOrderBridge#toString()}'s own spelling rather than a
	 * second format string, so the pair a debug dump prints and the pair a model reads cannot differ.
	 */
	private static String chartOrderClause(SafetyWarning finding) {
		List<SafetyWarning.ChartOrderBridge> bridges = finding.chartOrderBridges();
		if (bridges.isEmpty()) {
			return "";
		}
		List<String> items = new ArrayList<String>(bridges.size());
		for (SafetyWarning.ChartOrderBridge bridge : bridges) {
			items.add(bridge.toString());
		}
		StringBuilder clause = new StringBuilder();
		appendSection(clause, FINDING_CHART_ORDER_LEAD, items);
		return clause.toString();
	}

	/**
	 * The strength clause for one finding. Every finding that reaches the model states one, and that
	 * is the invariant rather than a convenience (#283).
	 *
	 * <p><b>Silence is not a third answer, and it was measured to be the wrong one.</b> The first cut
	 * of #283 scoped the clause to INTERACTION findings, on the reasoning that a recorded allergy to
	 * the drug asked about licenses withholding without needing to say so. It does not, because the
	 * same change made the prompt's evidence-against claim CONDITIONAL on the finding saying it: the
	 * addressed-safety branch now offers a withholding branch and a caution branch, and a finding
	 * matching neither antecedent falls through to whichever the model reaches for. Measured on the
	 * standalone against {@code main} @ b0cfe545, one Severe recorded Aspirin allergy, one NSAID
	 * cross-reactivity chip and no interaction finding: <em>"No — ibuprofen should not be taken"</em>
	 * became <em>"Ibuprofen can be given, with one caution"</em>, 3 of 3, on the caution
	 * demonstration's own wording. The chip was identical on both sides; only the answer's call moved.
	 * So a contraindication states {@link #STRENGTH_WITHHOLD} — it is never a caution, and the record
	 * is where this module says so, per {@link DrugSafetyValidator#licensesWithholding(SafetyWarning)}.
	 *
	 * <p><b>A new type may not reach this renderer silently.</b> An OVERDOSE finding cannot arrive
	 * today — {@link #preAnswerFindings} validates with an EMPTY answer and the dose arm parses a
	 * stated dose out of the answer, so the arm cannot fire before there is one — and it wants neither
	 * clause as written, being a reason to change the DOSE, which withholding overstates and a caution
	 * understates. It therefore falls to the empty default here, and that default is now a defect
	 * waiting on a caller rather than a safe fallback: whoever renders findings after an answer exists
	 * must give the type its own clause in the same change.
	 *
	 * <p><b>What guards that, and what does not.</b> The PREMISE is pinned:
	 * {@code SafetyFindingSeverityStrengthTest
	 * .theTypeThatStatesNeitherClauseCannotReachTheRendererBeforeThereIsAnAnswer} drives an arrangement
	 * that DOES raise an overdose warning through the real {@code validate} given an answer, and asserts
	 * the pre-answer path raises none — so it reddens the moment the dose arm becomes reachable from
	 * here. The CONCLUSION is not, and this javadoc claimed it was until review read the case:
	 * {@code everyInjectedFindingStatesOneOfTheTwoStrengths} iterates the findings ONE fixed arrangement
	 * produced, no arrangement of {@link #injectRecords} produces an overdose finding, so it can never
	 * observe the type it was named as the guard for. Measured by mutation rather than argued: with
	 * {@link #preAnswerFindings} validating against a stated dose instead of the empty string, the
	 * premise case reddens and names the record that would reach the model ("The stated Amoxicillin
	 * dose ~4000 mg/day exceeds …", no clause on it) while
	 * {@code everyInjectedFindingStatesOneOfTheTwoStrengths} stays green. A caller that renders
	 * findings after an answer
	 * exists is a new path neither case runs; it writes its own clause with no test to lean on.
	 *
	 * <p>The interaction split is {@link DrugSafetyValidator#licensesWithholding(SafetyWarning)},
	 * never a local reading of the rating, and never {@code ratingLicensesWithholding} underneath it:
	 * unrated is not low-rated, and a FOLDED finding asserts an unrated class relationship its rating
	 * does not cover — two halves a second copy would get wrong in opposite directions.
	 *
	 * <p>The clause is a statement about the FINDING's strength, not an instruction about a
	 * prescribing action, and that is what keeps it true on every arm that renders through here. The
	 * screening arm (#113) states it of a pair both of whose drugs are the patient's own active
	 * orders, and the allergy-versus-active-order join (#143) of a drug they are already taking, so
	 * "withhold it" reads there as a reason to stop rather than a reason not to start. Both readings
	 * are the finding's own claim; neither is this module telling a clinician what to do, which is the
	 * line {@code DrugSafetyValidator}'s class javadoc draws. Measured on the standalone, the
	 * screening answer is unchanged by the clause.
	 */
	private static String strengthClause(SafetyWarning finding) {
		if (SafetyWarning.TYPE_INTERACTION.equals(finding.getType())) {
			return DrugSafetyValidator.licensesWithholding(finding) ? STRENGTH_WITHHOLD
					: STRENGTH_CAUTION;
		}
		if (SafetyWarning.TYPE_CONTRAINDICATION.equals(finding.getType())) {
			return STRENGTH_WITHHOLD;
		}
		return "";
	}

	/**
	 * The entry's interaction notes, ordered so the partners this patient is actually on come first —
	 * most severe of those first, see {@link #SEVERITY_DESCENDING} — then the rest in dataset order.
	 *
	 * <p>This ordering is what makes the {@link #MAX_INTERACTION_RENDER_CHARS} cut meaningful.
	 * Rendering in dataset order let the dataset's own sequence decide which partners a clinician's
	 * model could cite: in the full DDInter KB, Clarithromycin carries 898 partners with Simvastatin
	 * (Major) at index 324, so a patient on simvastatin asking about clarithromycin got a record
	 * naming ivosidenib, kanamycin and ketoprofen — none of which they take — and the one
	 * interaction that concerned them was truncated 300 entries earlier. The model then recited
	 * what it could see. {@link DrugSafetyValidator} was unaffected throughout, because it reads
	 * every interaction off the entry and never consults this text, so the chip named simvastatin
	 * while the prose named ivosidenib: the two disagreed by construction.
	 *
	 * <p>Relevance uses {@link PatientClinicalContext#hasActiveDrug} — deliberately the same
	 * predicate {@link DrugSafetyValidator} uses to decide an interaction concerns this patient (see
	 * {@link #promotable}, which is that predicate and the severity floor together, applied both here
	 * and inside the collapse below) — so a partner that raises a DRUG-IN-PLAY chip is exactly a
	 * partner promoted here, and WHICH partners this text names cannot drift from that chip.
	 *
	 * <p>Which is a claim about the SET, and since issue #297 about the NAME again. Issue #292 let a
	 * folded chip name the partner by the class arm's ladder while the note below kept
	 * {@code DrugSafetyValidator.partnerLabel}, so for such a partner the chip and this record called one
	 * active order two things — the trade ADR Decision 39 recorded and deferred because this text is
	 * PROMPT text. It is closed by {@link #reconciledPartnerNoteName}, which takes the name off the chip that
	 * decided it rather than re-deriving it: the two surfaces name one SUBSTANCE, each in its own
	 * vocabulary, since this record's prose may not carry {@code DrugReference.displayLabel()}. Where the
	 * reconciliation refuses or reaches no co-medication, this note is {@code partnerLabel} again
	 * exactly as before.
	 *
	 * <p><b>Since issue #339 that reconciliation is asked of every rule chip and not only of a folded
	 * one, and one thing this text used to say about it no longer holds.</b> It said the prompt's name
	 * union for a partner cannot GROW, on the ground that the folded chip's CLASS sentence already
	 * carried the ladder's name. An unfolded chip has no class sentence, so for a rule-only partner the
	 * ladder's name was not previously in the prompt at all and the rule's own token can be replaced
	 * outright — ADR Decision 39 measured 2,406 of 513,026 gate-satisfying rules whose handed-out label
	 * does not contain the token. What DOES still hold is the weaker and load-bearing half: this note's
	 * name is always a name the same prompt carries, because the chip that decided it reaches the
	 * prompt verbatim through {@link #renderFinding} and the note's {@code getName()} is a word of that
	 * chip's label. So the two surfaces cannot disagree, which is what issue #297 is about.
	 *
	 * <p>Scoped to that arm deliberately, and the scope is the correction
	 * {@link DrugSafetyValidator#addQuestionPairInteractions} asks for: across the whole chip set the
	 * correspondence does not hold, because a question-PAIR chip names two drugs the question named
	 * and neither need be an active order, so its partner is promoted nowhere. That does not reopen
	 * the chip-versus-prose split this ordering exists to close — since issue #110 every chip is also
	 * injected as its own numbered, citable record, carrying the chip's detail verbatim and the
	 * strength clause after it ({@code preAnswerFindings} → {@link #renderFinding}, #283), so a pair
	 * finding is grounded by that record rather than by these notes, and the promoted-note budget is
	 * untouched by it. Verbatim across passes too, not only within one: {@link #renderFinding} renders
	 * the chip the PRE-ANSWER pass raised, and between issue #236 and issue #238 the question-pair
	 * arm's subject was folded over a group the answer widens, so that record and the chip beside the
	 * answer could name one substance two ways — {@code DrugSafetyValidator.SubstanceSubjects} and ADR
	 * Decision 49 are where that residue and its measurement live. Issue #238 (ADR Decision 53) closed
	 * it: the arm's subject now comes from the naming group every question drug is already in, so the
	 * answer cannot move it. Said here as well as in the paragraph this one is paired with
	 * ({@link DrugSafetyValidator#addQuestionPairInteractions}), because the two came apart once already
	 * when only one of them was reworded.
	 *
	 * <p>That correspondence is per PARTNER, and since issue #174 site 2 this method renders one note
	 * per partner rather than one per ROW — the same collapse
	 * {@link DrugSafetyValidator#bestRulePerPartner} has made for the chips since issue #115, keyed
	 * on {@link DrugSafetyValidator#partnerLabel} case-folded — that method's own fallback key, for
	 * the reasons {@link #onePerPartner} sets out — and reaching the same survivor: the row the
	 * patient is on, then {@link DrugSafetyValidator#outranksOnRule} (most severe, then the longer
	 * note). Before it, a patient on one dexamethasone order got one Major chip beside a record reading
	 * "dexamethasone (Major …); dexamethasone (Moderate …); dexamethasone (Moderate …)" — a model
	 * answering from the record could name a severity the chip deliberately discarded, and from the
	 * more quotable half, since in that measured case the discarded Moderate note is 659 characters
	 * against the surviving Major row's 326 (re-measured 2026-08-07 through the real parser over both
	 * the fixture slice and the shipped KB, which agree).
	 *
	 * <p>Measured over the shipped 19 MB KB (2026-08-07; re-measure before relying on the figures):
	 * 1876 of its 2283 entries carried at least one repeated partner and 19,316 of the 590,312
	 * expanded rows were surplus, {@code Ozanimod} carrying the largest single surplus at 49. The cost
	 * of carrying them was not only tidiness: segment 1 of {@code render} deliberately overrides
	 * {@link #MAX_INTERACTION_RENDER_CHARS} so that a partner the patient is on is never invisible, so
	 * a partner filed under three rows spent three notes of budget the budget could not claw back.
	 *
	 * <p>The route vocabulary that is the data-side half of #115 is still missing, and this collapse
	 * does not need it: it decides which of several rows about ONE partner to SHOW, exactly as the
	 * chip decides which to raise, and neither has to know which variant the order is. What still
	 * waits on that vocabulary is stating a variant-specific severity at all.
	 *
	 * <p>Since issue #163 there is one more way they can differ, in the opposite direction, and it is
	 * stated here rather than left to be discovered: {@link #matchingEntries} now injects ONE record per
	 * substance, so this method sees only that substance's canonical row, while
	 * {@link DrugSafetyValidator#bestRulePerPartner} reads every row of it (issue #162). A partner whose
	 * rule sits only on a sibling row therefore raises a chip that this text does not name. What covers
	 * it is issue #110 rather than this method: that chip is itself injected as a citable
	 * safety-finding record carrying the rule's mechanism note verbatim ({@link #renderFinding}), which
	 * is the same mechanism a pair chip's grounding already relies on. See {@code collect} for the
	 * measured size of the residue.
	 *
	 * <p>Ordering alone is not sufficient, which is why {@code render} also overrides the budget for
	 * this segment: two above-floor partners can exceed {@link #MAX_INTERACTION_RENDER_CHARS}
	 * between them (measured on the 16-drug DDInter excerpt: methotrexate 783 + aspirin 809 against a 1500
	 * budget), and dropping the second reinstates exactly the chip-versus-prose split described
	 * above for the polypharmacy case. So the cap becomes a soft budget with a bounded overshoot
	 * rather than a hard ceiling — bounded by the patient's own active-drug count, not the dataset's
	 * breadth, and paid in the compact {@code name (Severity)} form rather than in full notes.
	 *
	 * @param context may be null (nothing to prioritise by) — the section then keeps dataset order
	 * @param orderEntries the reference entries the patient's active orders resolve to, which
	 *        {@link #onePerPartner} keys a promoted partner on (issue #190 item 2); an empty list falls
	 *        the grouping back to the label alone, as it was before that issue
	 * @param findings this injection's pre-answer chips, from which {@link #reconciledPartnerNoteName} takes
	 *        the name a rule chip gave a partner (issue #297; since #339 a chip that folded or not);
	 *        null or empty leaves every note on
	 *        {@code DrugSafetyValidator.partnerLabel}, which is what the {@code drugSafety} toggles being
	 *        off produces and what every note-text case in the suite runs on
	 */
	static OrderedInteractions orderedInteractionNotes(DrugReference ref, PatientClinicalContext context,
			List<DrugReference> orderEntries, List<SafetyWarning> findings) {
		List<InteractionNote> promoted = new ArrayList<InteractionNote>();
		List<InteractionNote> rest = new ArrayList<InteractionNote>();
		// Promotion honours the SAME severity floor the chips do (issue #84). Measured on the 3.7.1
		// standalone (2026-07-30): promoting on relevance alone surfaced DDInter's Unknown-severity
		// rows — which carry no mechanism text and which the floor deliberately suppresses from
		// chips — into the front of the prompt, and the model then answered from them. Two probe
		// cells that correctly abstained on the baseline started reporting "an Unknown severity
		// interaction between Erythromycin and Lisinopril", i.e. the render path was bypassing a
		// safety decision the chip path enforces. A sub-floor rule is not promoted; it keeps its
		// dataset position, exactly as before promotion existed.
		int floor = DrugSafetyValidator.configuredSeverityFloor();
		for (DrugReference.Interaction i : onePerPartner(ref, context, floor, orderEntries)) {
			String label = reconciledPartnerNoteName(findings, context, i);
			String note = ChartSearchAiUtils.firstNonBlank(i.getNote());
			// Kept identical to the previous rendering: a labelless rule still contributes its bare
			// note, and a null/blank pair contributes nothing (addIfPresent drops it) — the dataset
			// is operator-editable and must degrade, never throw or emit a literal "null".
			String rendered = label != null ? (note != null ? label + " (" + note + ")" : label) : note;
			if (ChartSearchAiUtils.isBlank(rendered)) {
				continue;
			}
			// Trim before the length comparison below, not after: comparing untrimmed and storing
			// trimmed lets a row whose note carries trailing whitespace still end up with a "compact"
			// form longer than the full one, which is the single thing that comparison exists to rule
			// out.
			rendered = rendered.trim();
			// The compact form exists so a relevant partner is never invisible when its full note
			// does not fit the budget (see render()). Severity is kept because it is the one thing a
			// clinician needs when the mechanism prose has to go; a labelless rule has nothing
			// shorter to fall back to, so it keeps its full text.
			//
			// It has a SECOND consumer, and in the common case the primary one: segment 2 renders the
			// dataset-tail representative compact unconditionally whenever a partner was promoted,
			// with budget still to spare (issue #117 — mechanism prose about a drug the patient is not
			// on is what the model recited). So do not assume reaching this form means the budget ran
			// out; it also means "breadth is all this partner is here for".
			String severity = ChartSearchAiUtils.firstNonBlank(i.getSeverity());
			String compact = (label == null ? rendered
					: (severity != null ? label + " (" + severity + ")" : label)).trim();
			// A row carrying a severity but no mechanism text renders full as just the label, which
			// the severity-bearing short form would then be LONGER than — a "compact" that costs
			// more than what it replaces. Fall back to the full text in that case so the name stays
			// true and the substitution can never grow the piece.
			if (compact.length() >= rendered.length()) {
				compact = rendered;
			}
			(promotable(i, context, floor) ? promoted : rest)
					.add(new InteractionNote(rendered, compact, i.getSeverity()));
		}
		// Within the promoted segment, severity — not dataset position — decides who keeps their
		// mechanism prose when the budget can only afford one full note (see render). Measured on the
		// 16-drug DDInter excerpt: a patient on lisinopril (Moderate x ibuprofen, 910 chars) and aspirin
		// (MAJOR, 809) exceeded the budget, and because lisinopril sits earlier in the dataset it
		// took the full note while the Major interaction was abbreviated to "aspirin (Major)". Both
		// severities stayed visible, so nothing was silently dropped — but the actionable half went
		// to the less dangerous interaction, decided by dataset accident. Stable, so equal severities
		// keep dataset order.
		Collections.sort(promoted, SEVERITY_DESCENDING);
		int promotedCount = promoted.size();
		// `promoted` becomes the whole ordered list from here — the count above is what keeps the two
		// segments distinguishable to render().
		promoted.addAll(rest);
		return new OrderedInteractions(promoted, promotedCount);
	}

	/**
	 * @return the name this record's interaction note must call {@code rule}'s partner by: the name the
	 *         chip about that same rule gave it where the reconciliation answered — a FOLDED chip until
	 *         issue #339 and any rule chip since
	 *         ({@link DrugSafetyValidator#partnerLabel}'s counterpart in this record's own vocabulary),
	 *         else {@code partnerLabel} itself — which is what this list has always printed.
	 *
	 *         <p><b>Issue #297.</b> Since issue #292 a folded chip can name an active order by the class
	 *         arm's ladder, and that chip reaches the prompt verbatim as a citable
	 *         {@code safety_finding} ({@link #renderFinding}) — while this note kept the rule's own
	 *         token. So one prompt carried one prescription under two names, the property
	 *         {@code CLAUDE.md} states {@code partnerLabel} exists to hold.
	 *
	 *         <p><b>Read off the findings this injection already holds, never re-derived.</b>
	 *         {@link #injectRecords} runs the whole fold once through {@link #preAnswerFindings}; asking
	 *         {@link DrugSafetyValidator} to walk {@code classRelationships} a second time so the two
	 *         answers could be compared is the two-resolutions-that-agree shape issue #151 forbids, and
	 *         that failure was silent and one-directional. It also makes the scope right for free: a
	 *         name can only come from a chip that was actually raised AND injected in this same
	 *         response — so every name this NOTE can newly carry is one the {@code safety_finding}
	 *         beside it already contains. That is the half of the union bound that survives issue #339;
	 *         the wider reading, that the prompt's name union for the partner cannot GROW at all, does
	 *         not, because the chip's own name moved for rule-only partners (see the paragraph on that
	 *         issue above, and ADR Decision 63). An
	 *         ORDER-DRIVEN record, which no interaction chip stands behind (see {@code collect}), is
	 *         therefore untouched rather than renamed after a chip that does not exist.
	 *
	 *         <p>The rule-identity condition is {@link SafetyWarning#reconciledPartnerNoteName}'s own,
	 *         asked there rather than here: this record collapses to one note per partner over ONE row
	 *         ({@link #onePerPartner}) while the chip chose across every row of the substance
	 *         ({@code DrugSafetyValidator.bestRulePerPartner}), so the two can elect different rules for
	 *         one partner and the answer there is then null. Falling back leaves the note exactly where
	 *         it was, which is what makes this change able only to remove a divergence and never to
	 *         create one.
	 *
	 *         <p><b>Issue #339 widened what that costs on the flattened shape, and the guard below is
	 *         kept anyway.</b> The reconciliation now answers for every rule chip, so on a context
	 *         carrying only the flattened code set an UNFOLDED chip can be reconciled while this note
	 *         is not — measured through the real {@code validate} over the pinned excerpt, a chart
	 *         carrying {@code Warfarin} and {@code B01AA03} and no per-order list chips
	 *         {@code interacts with active order Warfarin} beside a note reading {@code warfarin}.
	 *         Before #339 that pair agreed, both being {@code partnerLabel}. It is the residue issue
	 *         #297 already accepted for a FOLDED chip on that same shape, reaching further; the two
	 *         surfaces still name one SUBSTANCE, which is what this record's own vocabulary paragraph
	 *         above says they share. Closing it means dropping the condition below, which is what makes
	 *         the RECORD key-dependent — the thing
	 *         {@code OrderDrivenInjectionResolutionTest.oneOrderInjectsOneRecordSetWhicheverWayItResolves}
	 *         forbids — so the trade is unchanged by #339 and is recorded rather than taken.
	 *
	 *         <p><b>And only where the context carries per-order structure</b>, which is every context
	 *         {@link PatientClinicalContextBuilder} builds for a real patient. On the flattened shape of
	 *         issue #118 the class arm's own reach is key-dependent — {@code orderPartners} reads the
	 *         FLATTENED set for its code rung and the per-order list for its name rung, so the same
	 *         prescription folds when a dictionary published its ATC code and does not when it published
	 *         only its name. That asymmetry is pinned as current behaviour by
	 *         {@code DuplicateInteractionChipTest.aRuleOnlyPairIsWordedExactlyAsBefore} ("no ATC mapping,
	 *         so the class arm has nothing to say") and forbidden from reaching the PROMPT by
	 *         {@code OrderDrivenInjectionResolutionTest.oneOrderInjectsOneRecordSetWhicheverWayItResolves}
	 *         ("the prompt behind those chips cannot depend on which key the dictionary carried"). The
	 *         two together say the chip may be key-dependent on that shape and the injected records may
	 *         not, so this gate is what keeps the record out of it. Closing the asymmetry itself was
	 *         attempted and abandoned in the same change: a name rung over the flattened set reddens the
	 *         first of those two tests, because it makes the class arm speak where that test says it must
	 *         not. Issue #228 already made the ORDER-carrying shape key-symmetric, which is why the gate
	 *         costs production nothing.
	 *
	 *         <p><b>The fallback below and the fold's own answer for its non-entry rungs are one method,
	 *         not two spellings.</b> {@code DrugSafetyValidator.reconciledPartnerName} hands the note
	 *         {@code partnerLabel(rule)} on its NON-entry rungs — the rungs where no dataset name has
	 *         been PROVED to be this rule's, which is not the same as the dataset having none for the
	 *         partner — and that is verbatim what this method returns when it finds nothing, so a rule
	 *         reconciled on those rungs and a rule not reconciled at all print the same string BECAUSE
	 *         both call {@code partnerLabel}, which is what that method exists to be. Keep it that way:
	 *         if this fallback ever became something else, those rungs would keep the old string while
	 *         every unreconciled partner took the new one, and one prescription would be named two ways
	 *         inside one interaction list — issue #297 reopened a rung along.
	 *
	 *         <p><b>An ORDER-DRIVEN record's notes are outside all of this, and issue #339 widened what
	 *         that costs.</b> Such a record is injected because a drug is an active order, with no
	 *         interaction chip behind it (see {@code collect}), and it renders its OWN entry's rules —
	 *         {@code Interaction} objects no chip carries — so the identity test above never matches and
	 *         every one of its notes falls back to {@code partnerLabel}. That was harmless while an
	 *         unfolded chip printed {@code partnerLabel} too; since #339 it does not, so one prompt can
	 *         carry one prescription under two names across two records. Measured through the real
	 *         {@code injectRecords} over the pinned excerpt, a patient on Warfarin, Acetylsalicylic acid
	 *         and Digoxin asked {@code "Can I give her ibuprofen?"}: the in-play {@code Ibuprofen} record
	 *         lists {@code Warfarin}, the order-driven {@code Acetylsalicylic acid} record lists
	 *         {@code warfarin}, and before #339 both read {@code warfarin}. <b>It is not a corner, which
	 *         one example reads as</b> — measured at review round 9 over 200 synthetic arrangements
	 *         of the shipped knowledge base driven through the real {@code injectRecords} (the first 200
	 *         entries publishing two interaction tokens that resolve to another substance, each charted
	 *         as up to three of those partners as active orders): one prompt names one substance two ways
	 *         in 20 of them, against 2 of 200 at the merge base. None of them is a false claim. ADR
	 *         Decision 63's trade-off bullet carries the arrangement, the split by shape and the caveat,
	 *         and this paragraph does not restate them. Closing it means keying this
	 *         lookup on the PARTNER rather than on rule identity, which needs the partner entry to travel
	 *         beside the name — a change to what a {@link SafetyWarning} carries, not to this scan — and
	 *         it re-opens the key-dependence question the condition below exists for. Recorded in ADR
	 *         Decision 63's trade-offs rather than taken here.
	 *
	 *         <p>A linear scan of a list bounded by the chips this response raised, and deliberately not
	 *         a map: the accessor above will not hand out a name without being shown the rule it was
	 *         decided on, and a map keyed here would have to read the rule back out to build itself.
	 *
	 * @param findings the pre-answer chips, or an empty list when the {@code drugSafety} toggles are off
	 *        — in which case there is no chip for this note to agree with and it keeps its own label,
	 *        which is the same gating {@code preAnswerFindings} already applies to the record's
	 *        contraindication reading (issue #208 item 2)
	 */
	private static String reconciledPartnerNoteName(List<SafetyWarning> findings,
			PatientClinicalContext context, DrugReference.Interaction rule) {
		if (findings != null && context != null && !context.getActiveDrugOrders().isEmpty()) {
			for (SafetyWarning finding : findings) {
				String reconciled = finding.reconciledPartnerNoteName(rule);
				if (reconciled != null) {
					return reconciled;
				}
			}
		}
		return DrugSafetyValidator.partnerLabel(rule);
	}

	/**
	 * @return {@code ref}'s interaction rules, at most ONE per partner, in the dataset order of each
	 *         partner's first row — the rendering counterpart of
	 *         {@link DrugSafetyValidator#bestRulePerPartner} (issue #174 site 2).
	 *
	 *         <p><b>The key is the chip's own two-tier key</b> — the ACTIVE-ORDER ENTRY the rule names
	 *         where {@link DrugSafetyValidator#activeOrderEntryFor} resolves one, else
	 *         {@link DrugSafetyValidator#partnerLabel} case-folded, which is both the key
	 *         {@code bestRulePerPartner} falls back to and the very string this record prints, so the
	 *         grouping and the rendering cannot come to disagree about what one partner is. A rule
	 *         carrying NEITHER a token nor an ATC code keys on itself: it renders as a bare note with no
	 *         name to group on, and merging two such rows would silently drop one operator-authored
	 *         paragraph in favour of another. The three key spaces cannot collide — a
	 *         {@link DrugReference} and an {@link DrugReference.Interaction} define no {@code equals}, and
	 *         neither can ever equal a {@link String}.
	 *
	 *         <p><b>Why the tail stays on the LABEL, and why that is not the text-keying issue #173 ruled
	 *         out.</b> Every chip-side ledger keys on identity because a key made of rendered text rots
	 *         when the rendering changes, so this looks like the exception and is worth settling once
	 *         rather than re-litigating. Three things settle it.
	 *         <ul>
	 *           <li>There is usually no identity to be had. Every partner in the dataset tail, which is
	 *               where nearly all the surplus above lives, resolves to no active order, so for them
	 *               the chip's own key IS the label. Keying the tail on an entry would mean resolving
	 *               each partner token across the whole dataset, which is a THIRD resolution rather than
	 *               a port of the chip's.</li>
	 *           <li>It would not be safer, it would be less safe. {@code identifies} resolves through
	 *               an entry's alias list and its ATC codes, and the shipped KB shares both across
	 *               entities the dataset itself files as separate drugs. Measured 2026-08-07 over the
	 *               19 MB KB by calling that predicate: on 397 of 2283 entries, 487 notes, two rules
	 *               with DIFFERENT labels resolve to ONE entry — {@code trastuzumab} with
	 *               {@code trastuzumab deruxtecan}, {@code isosorbide} with
	 *               {@code isosorbide mononitrate}, {@code moderna covid-19 vaccine} with
	 *               {@code sars-cov-2 (covid-19) vaccine, mrna spike protein}. In a CHIP that
	 *               over-merge costs one duplicate and the survivor is still the most severe rule; in
	 *               a RECORD it costs a partner its name, and this record is the only place the tail
	 *               is named at all. Dropping a partner is the direction this module does not take.</li>
	 *           <li>What #173 ruled out was keying on an ASSEMBLED SENTENCE — the screening arm's
	 *               {@code (type, drug, detail)} triple, which stopped recognising a repeat the moment
	 *               either arm reworded its chip (see {@code DrugSafetyValidator.InteractionPairs}). A
	 *               partner's own coalesced, trimmed name is not that: it is the atomic unit of the
	 *               grouping, and issue #121's invariant — the key IS what the RECORD says — is
	 *               deliberate rather than incidental. <b>Since issue #297 that second half is scoped for
	 *               this record exactly as issue #292 scoped it for the chip</b>: on the no-entry branch
	 *               the key is still {@code partnerLabel} case-folded, while a partner the
	 *               reconciliation answered for can RENDER that answer here. Issue #339 widened which
	 *               partners those are — every rule chip's, not only a folded one's — so a note
	 *               rendering this key is now the case where the reconciliation declined rather than the
	 *               case where no class sentence folded. The grouping is unaffected, running before the
	 *               note is worded and on that key. (On the other branch {@link #onePerPartner} keys on
	 *               the ENTRY, and that is the branch the entry rung reconciles on — so it rendered
	 *               {@code partnerLabel} beside an entry key until issue #297, and now renders the
	 *               reconciled name there. Which is issue #190 item 2's residue seen from the other side,
	 *               the paragraph below it.) The chip half of that invariant is scoped since issue #292
	 *               and finished by issue #339 (see
	 *               {@code DrugSafetyValidator.reconciledPartnerName}); this key is not, and does not
	 *               follow the chip's rendered name — the KEY does not, though since issue #297 the
	 *               rendered NAME does.</li>
	 *         </ul>
	 *
	 *         <p><b>Issue #190 item 2</b> is the residue the label key left where an identity WAS to be
	 *         had: two rules naming ONE of the patient's own orders under two of its names — issue #136's
	 *         {@code warfarin}/{@code coumadin}, one entry reached by two aliases — were two notes beside
	 *         a single chip, because the chip had already keyed them on that entry. Taking the chip's own
	 *         answer closes it without buying any of the 397-entry over-merge above: that measurement is
	 *         a property of resolving a partner across the WHOLE dataset, and this resolution is bounded
	 *         by the patient's active orders. Where it does merge two labels, the chip merged them first
	 *         and the record now agrees with it — which is the invariant, not a cost.
	 *
	 *         <p>Applied over EVERY rule rather than only over the promoted ones, deliberately: the
	 *         floor decides which rules are worth PROMOTING, while a sub-floor row keeps its dataset
	 *         position in the tail (see the caller), so collapsing only the promoted half would leave
	 *         a sub-floor row of a partner in the tail beside that partner's promoted row — the same
	 *         partner twice, which is what this removes. (The survivor rule below does READ the floor,
	 *         through {@link #promotable}; what it does not do is filter the input by it.)
	 *
	 *         <p><b>Which row wins, and why promotability is asked FIRST.</b> Running before the floor
	 *         means the survivor rule decides which row's {@code (token, ATC)} pair the caller's
	 *         promotion predicate is then asked about — so the survivor must be a row that predicate
	 *         says yes to wherever the group has one, or the collapse can push a partner OUT of the
	 *         segment that overrides {@link #MAX_INTERACTION_RENDER_CHARS} and, with another partner
	 *         promoted and only one tail representative rendered, out of the record altogether. That
	 *         is {@link DrugSafetyValidator#bestRulePerPartner}'s behaviour reproduced rather than a
	 *         rule of its own: it never sees a non-matching row at all, having filtered on
	 *         {@code hasActiveDrug} before it groups. Within each half the order is
	 *         {@link DrugSafetyValidator#outranksOnRule}, so the promoted note is the row the chip
	 *         quotes, and a partner with no promotable row keeps its most severe one in the tail.
	 *         The floor half of {@link #promotable} cannot change a winner on its own — a group's
	 *         most severe row clears the floor whenever any of its rows does, since
	 *         {@code severityPriority} ranks unrated highest and is otherwise monotone in the rank the
	 *         floor compares — so only the {@code hasActiveDrug} half is doing work here. No shipped
	 *         dataset can make it: {@code ddinter} writes every rule's ATC from its partner row, and
	 *         measured 2026-08-07 through the real parser, 0 of the 19 MB KB's label groups hold rows
	 *         differing on either field. A hand-authored file reaches it immediately, which is the
	 *         same latency issue #174 site 4 is guarded at.
	 *
	 *         <p>A {@link LinkedHashMap}, so replacing a group's winner does not move the partner's
	 *         position — the tail's dataset order is what the caller's javadoc guarantees.
	 */
	private static Collection<DrugReference.Interaction> onePerPartner(DrugReference ref,
			PatientClinicalContext context, int floor, List<DrugReference> orderEntries) {
		Map<Object, DrugReference.Interaction> best =
				new LinkedHashMap<Object, DrugReference.Interaction>();
		for (DrugReference.Interaction i : ref.getInteractions()) {
			DrugReference partner = DrugSafetyValidator.activeOrderEntryFor(orderEntries, ref, i);
			String label = DrugSafetyValidator.partnerLabel(i);
			Object key = partner != null ? (Object) partner
					: (label != null ? (Object) label.toLowerCase(Locale.ROOT) : i);
			DrugReference.Interaction incumbent = best.get(key);
			if (incumbent == null || outranksForRendering(i, incumbent, context, floor)) {
				best.put(key, i);
			}
		}
		return best.values();
	}

	/** @return true when {@code candidate} is the row this record should show for a partner
	 *          {@code incumbent} already covers: the row the patient is on before one they are not,
	 *          then {@link DrugSafetyValidator#outranksOnRule}. See {@link #onePerPartner}. */
	private static boolean outranksForRendering(DrugReference.Interaction candidate,
			DrugReference.Interaction incumbent, PatientClinicalContext context, int floor) {
		boolean candidatePromotable = promotable(candidate, context, floor);
		if (candidatePromotable != promotable(incumbent, context, floor)) {
			return candidatePromotable;
		}
		return DrugSafetyValidator.outranksOnRule(candidate, incumbent);
	}

	/** @return whether {@code i} names a drug this patient is on by a rule the severity floor admits
	 *          — the promotion predicate of {@link #orderedInteractionNotes}, shared with
	 *          {@link #onePerPartner} so the collapse cannot discard the very row that would have been
	 *          promoted. Both arms are the ones {@link DrugSafetyValidator#bestRulePerPartner} applies
	 *          before it groups. */
	private static boolean promotable(DrugReference.Interaction i, PatientClinicalContext context,
			int floor) {
		return context != null && context.hasActiveDrug(i.getToken(), i.getAtc())
				&& DrugSafetyValidator.clearsSeverityFloor(i, floor);
	}

	/**
	 * Orders promoted interactions most-severe first. An unrated rule sorts ahead of Major: every
	 * curated hand-authored rule is unrated, and {@link DrugSafetyValidator#clearsSeverityFloor}
	 * already treats unrated as exempt rather than low — unrated is not low-rated, so it must not be
	 * the one abbreviated.
	 *
	 * <p>Which source a rule came from decides whether that branch is reachable at all: DDInter rates
	 * every row (all 295,184 in the full KB are Major/Moderate/Minor/Unknown — none unrecognised), so
	 * unrated arises only from an operator's curated JSON. A mixed deployment is therefore the only
	 * configuration in which the unrated-versus-rated tie-break is observable, which is why no
	 * bundled dataset can cover it.
	 */
	private static final Comparator<InteractionNote> SEVERITY_DESCENDING = new Comparator<InteractionNote>() {

		@Override
		public int compare(InteractionNote a, InteractionNote b) {
			return Integer.compare(b.severityPriority, a.severityPriority);
		}
	};

	/** One interaction's rendered text, with a short form for when the budget cannot take the note. */
	static final class InteractionNote {

		final String full;

		final String compact;

		/** {@link DrugSafetyValidator#severityPriority} — the shared ordering in which unrated sits
		 *  above Major; see {@link #SEVERITY_DESCENDING}. */
		final int severityPriority;

		InteractionNote(String full, String compact, String severity) {
			this.full = full;
			this.compact = compact;
			this.severityPriority = DrugSafetyValidator.severityPriority(severity);
		}
	}

	/** The ordered interaction notes plus the length of their patient-relevant prefix. */
	static final class OrderedInteractions {

		final List<InteractionNote> ordered;

		final int promotedCount;

		OrderedInteractions(List<InteractionNote> ordered, int promotedCount) {
			this.ordered = ordered;
			this.promotedCount = promotedCount;
		}
	}

	/**
	 * One reference entry rendered for the prompt, separated from the bookkeeping that describes
	 * the rendering.
	 *
	 * <p>{@code text} is the citable record: everything in it is quotable, because the model is
	 * instructed to cite records and it quotes what it cites. {@code source} and
	 * {@code withheldInteractions} are facts <em>about</em> that text — a dataset attribution and
	 * how many partners the text does not name — which used to be appended to it and were duly recited
	 * into clinician-facing answers ("…and 824 more interactions on file. Source: DDInter 2.0…",
	 * issue #117). They travel here instead, onto the {@link RecordMapping} and out to the client,
	 * where a citation chip can show provenance and honest truncation without the model ever
	 * seeing either.
	 */
	static final class RenderedReference {

		final String text;

		/** Dataset attribution, or null when the entry declares none. */
		final String source;

		/** Interaction partners the text does not name — dropped by the budget or, more often, by
		 *  segment 2 representing the dataset tail with one partner; 0 when it names them all.
		 *
		 *  <p>Partners, not rows, since issue #174 site 2: the entry's rules are collapsed to one per
		 *  partner before anything is rendered, so this counts what the field has always claimed to
		 *  count. It used to over-report by exactly the surplus — a record naming both of an entry's
		 *  partners through 4 of its 7 rows declared 3 withheld, a citation claiming to be a strict
		 *  subset of itself.
		 *
		 *  <p>Counted over the rendered ENTRY's own partners, which since issue #163 is one row of a
		 *  substance rather than every row of it: a partner carried only by a sibling row is ABSENT from
		 *  this record, not withheld from it, and so is not in this count. So {@code 0} means "names
		 *  every partner of the row this record was rendered from", not "of the substance it is named
		 *  after" — see {@code collect} for the size of that difference. Left as the row's own count
		 *  rather than widened, because what the field exists to describe is honest truncation OF THIS
		 *  TEXT, and a number counting rows the text never had a chance to name would describe something
		 *  else. */
		final int withheldInteractions;

		RenderedReference(String text, String source, int withheldInteractions) {
			this.text = text;
			this.source = source;
			this.withheldInteractions = withheldInteractions;
		}
	}

	/**
	 * @return whether an injected record may state what THIS patient's chart records of a drug's
	 *         contraindications — three things, all of which have to hold, and none of which is a
	 *         property of the drug:
	 *         <ul>
	 *           <li>there is a context at all;</li>
	 *           <li>the allergy and condition lists were actually READ
	 *               ({@link PatientClinicalContext#contraindicationRecordsRead}) rather than degraded to
	 *               empty by a swallowed failure — otherwise the record reports "this patient records
	 *               none of these" because the module could not look, which is issue #208's own defect
	 *               with the sign flipped, and the chips beside it fall silent on the same failure;</li>
	 *           <li>and the deployment has the contraindication chips switched on
	 *               ({@link DrugSafetyValidator#reportsContraindications}), because this reading is the
	 *               record's half of one.</li>
	 *         </ul>
	 *         The drug's own contraindication LIST is governed by none of this: it is reference
	 *         material, and it is rendered either way.
	 */
	private static boolean statesTheChartsContraindicationReading(PatientClinicalContext context) {
		return context != null && context.contraindicationRecordsRead()
				&& DrugSafetyValidator.reportsContraindications();
	}

	/**
	 * What one injection may say about this patient's own contraindication records: whether it may say
	 * anything at all ({@link #statesTheChartsContraindicationReading}) and, where it may, which
	 * substances their recorded allergies imply ({@link DrugSafetyValidator#allergicSubstanceKeys}).
	 *
	 * <p><b>NOTHING is supplied but the chart.</b> Whether the reading may be stated, the allergic
	 * substance keys and the service they are resolved from are all DERIVED here, so there is no pair a
	 * call site can hand over disagreeing — which is issue #298's discipline, whose own words are that
	 * "no constructor takes the label and the flag as separate arguments". Two earlier versions of this
	 * class fell short of it. Taking the SERVICE left a set resolvable from another dataset, read off the
	 * signature. Taking the FLAG left the worse half, and that one a reviewer CONSTRUCTED — measured,
	 * {@code new ContraindicationReading(true, null)} rendered
	 * "Not recorded for this patient: documented opium allergy", a denial about a chart nobody read,
	 * which is issue #208 item 2 with the sign flipped. Deriving needs no structural guard to hold it,
	 * which passing would.
	 *
	 * <p><b>An INNER class, not a static one, so the service is derivable at all.</b>
	 * {@link DrugSafetyValidator#allergicSubstanceKeys} compares
	 * {@link DrugReference#substanceGroupKey()}, which is the ROW ITSELF for an entry publishing no
	 * substance name — so a set resolved from a different {@link DrugReferenceService} than the rendered
	 * entries came from would contain nothing the caller can find, and every self-named allergy rule
	 * would read as uncorroborated. Taking the service in a constructor left exactly the pair this class
	 * exists to remove, one field along; reading the injector's own field removes it the way deriving
	 * the set does.
	 *
	 * <p><b>Decided once per injection</b>, for the reason the boolean already was: it reads global
	 * properties and resolves the patient's allergy list, and two records of one chart must not disagree
	 * about either. Held on this per-call object and never on the bean — {@link DrugReferenceInjector}
	 * is a Spring singleton and this memo is keyed on nothing at all (issue #172).
	 *
	 * <p><b>Resolved lazily</b>, because {@link DrugSafetyValidator#allergicSubstanceKeys} sweeps the
	 * dataset once per recorded allergen and the question is asked only of a matched self-named allergy
	 * rule. Neither bundled parser publishes a contraindication rule at all, so on every {@code ddinter}
	 * and {@code atc} load nothing here resolves anything.
	 */
	private final class ContraindicationReading {

		private final boolean states;

		private final PatientClinicalContext context;

		private Set<Object> allergicSubstanceKeys;

		ContraindicationReading(PatientClinicalContext context) {
			this.states = statesTheChartsContraindicationReading(context);
			this.context = context;
		}

		/** Whether a record of this injection may describe this patient's own records at all. */
		boolean states() {
			return states;
		}

		/** The chart the two answers above are about. Read from here rather than taken as a second
		 *  parameter beside this object: {@link #corroborated} asks one question of the context and one
		 *  of the resolved set, and a caller able to hand it a context other than the one the set was
		 *  resolved from is the same two-facts-that-can-disagree shape this class exists to remove. */
		PatientClinicalContext context() {
			return context;
		}

		/** The substances this patient's recorded allergies imply, keyed as the safety arms key them,
		 *  resolved on first ask off the injector's own service. */
		Set<Object> allergicSubstanceKeys() {
			if (allergicSubstanceKeys == null) {
				allergicSubstanceKeys =
						DrugSafetyValidator.allergicSubstanceKeys(drugReferenceService, context);
			}
			return allergicSubstanceKeys;
		}
	}

	/**
	 * @return whether anything CORROBORATES {@code c}'s match against this patient's chart, so that the
	 *         record may state the clause as the chart's own reading (issue #269). Asked only of a rule
	 *         that has already matched.
	 *
	 *         <p><b>The body now lives on {@link DrugSafetyValidator#corroboratedByTheChart}</b>, which
	 *         this delegates to, and the reasoning below is what that method points back at. It moved
	 *         for one reason: since issue #308 the injected {@code safety_finding} asks this same
	 *         question, and it is decided in the arm that BUILDS that finding — so a second copy here
	 *         would let the two records of one chart come apart again, which is precisely what #308
	 *         measured the cost of. The lazy memo is preserved across the move by handing the reading's
	 *         own accessor rather than its value, so leg 2's dataset sweep still happens only where
	 *         leg 1 fails.
	 *
	 *         <p><b>The UNION of two questions, and neither half will do.</b> A rule whose token is one
	 *         of its own entry's drug NAMES reaches the allergy list through
	 *         {@link PatientClinicalContext#hasAllergyToken}'s bare containment — deliberately bare,
	 *         because a curated token may name a CLASS or a fragment of free text — so {@code opium}
	 *         matches an allergen recorded as {@code Tiotropium} and the record used to say the chart
	 *         records a documented opium allergy. Either of two things redeems that match:
	 *         {@link DrugSafetyValidator#aMatchedRecordNamesTheEntry}, the chip rank's own predicate,
	 *         and {@link DrugSafetyValidator#allergicSubstanceKeys}, the allergen arm's identity
	 *         question over the whole allergy list.
	 *
	 *         <p>Each alone is wrong, in opposite directions, which is why this takes both:
	 *         <ul>
	 *           <li>the rank's predicate alone UNDERSTATES. It is per WITNESS of the rule that fired, and
	 *               {@code papaveretum} does not contain {@code opium} — so a patient allergic to
	 *               papaveretum and, separately, to tiotropium has a genuine opium allergy the allergen
	 *               arm chips, and the record would have hedged it.</li>
	 *           <li>the allergen arm's set alone OVERSTATES.
	 *               {@link DrugReferenceService#findImpliedSubstances} admits equal claimants only at the
	 *               strongest claimant's rank, so an allergy recorded as {@code Ketoconazole} reaches the
	 *               entry CALLED that and not one merely aliasing it — and a self-named rule on the
	 *               aliasing entry keeps the full {@code SELF_NAMED_RULE} chip rank while the record
	 *               would have hedged it. It is also why the third section's lead states what the module
	 *               established rather than a categorical about the chart — see
	 *               {@link #UNCORROBORATED_READING_LEAD}. That is the cost
	 *               {@code DrugSafetyValidator.contraindicationRank} records against swapping its own
	 *               predicate for this one, reached from the other side.</li>
	 *         </ul>
	 *         The union is MONOTONE, which is the whole argument for it: it can hedge nothing either
	 *         half admits, so it can neither understate a recorded allergy nor disagree with a chip
	 *         standing at full rank. Asked in cost order — the rank's predicate reads only the context
	 *         and the entry, so the dataset sweep happens only where it fails.
	 *
	 *         <p><b>Scoped to a self-named allergy rule</b>, exactly as the chip's demotion is, and that
	 *         is load-bearing rather than incidental: a rule whose token is NOT one of its entry's names
	 *         is asking about a class or about free text, which is what the bare match exists for, and
	 *         neither corroborating question can speak to it. The shipped seed's {@code nsaid} rule is
	 *         such a rule and the allergen arm resolves nothing at all from an allergy recorded as
	 *         {@code NSAIDs}, so an unscoped reading would hedge a correct clause. Tightening the MATCH
	 *         instead was measured and declined — see
	 *         {@link PatientClinicalContext#hasAllergyToken}.
	 */
	private static boolean corroborated(DrugReference ref, DrugReference.Contraindication c,
			ContraindicationReading reading) {
		return DrugSafetyValidator.corroboratedByTheChart(ref, c, reading.context(),
				reading::allergicSubstanceKeys);
	}

	/**
	 * Renders one reference entry into the citable line the LLM sees, plus the metadata that
	 * describes the rendering and must stay out of it — see {@link RenderedReference}. Numeric
	 * dosing is included only when an age band matches {@code age}; prose warnings,
	 * contraindications and interactions are always rendered.
	 *
	 * <p>{@code reading} is the {@link ContraindicationReading} the caller decided once for the
	 * whole injection rather than anything re-derived here: it reads global properties and resolves the
	 * patient's allergy list, and two records of one chart must not disagree about whether — or about
	 * what — this patient's chart records.
	 *
	 * <p>The chart comes off {@code reading}, and so does the AGE derived from it — neither is a
	 * parameter, because a second source for either lets one record's dose bands and its patient reading
	 * describe different patients. It orders the capped {@code Interactions:} section — see
	 * {@link #orderedInteractionNotes} — and it splits the contraindication list into what this patient's
	 * chart records, what it does not, and (issue #269) what it matched but nothing corroborates (issue
	 * #208 item 2, {@link #contraindicationSections}). It may be null, which is "nothing known about the
	 * patient": the interactions section then keeps dataset order and the contraindication list is
	 * rendered with no reading at all, because a record that cannot see the chart must not report an
	 * absence.
	 *
	 * <p>{@code orderEntries} stays a parameter and is NOT a counter-example to that: the context is
	 * BUILT from it ({@code withReferenceNames} at the call site), so there is nothing to re-derive here
	 * and re-resolving it per record is the second resolution CLAUDE.md's {@code findForActiveOrders}
	 * bullet refuses. It is passed straight through to the interactions method, which groups a partner
	 * the patient is on by the entry it resolves to (issue #190 item 2).
	 *
	 * <p>Private because {@code injectRecords} is the only caller. That is the whole reason: a
	 * package-private signature would let a caller outside this class pass a null reading — measured,
	 * a call passing a null for every parameter compiles the moment the modifier is dropped, since none
	 * of the five names a private type — and it would NPE on {@code reading.context()}. Re-measure by
	 * dropping the modifier rather than by counting the nulls here: the arity was four until issue #297
	 * added {@code findings}, and the count went stale in that change before review caught it.
	 *
	 * <p>{@code substance} is what this row's own fields cannot say: the rows of its substance the pass
	 * resolved, and which of them THIS RESPONSE names the substance by — the caller's
	 * {@link #matchingEntries} answers, not anything re-derived here, because both are facts about the
	 * whole row GROUP and this method holds one row of it (see {@link SubstanceRendering}). They feed
	 * {@link #rowAttribution} and {@link #otherRowDosing} and nothing else: for a one-row substance whose
	 * chart says nothing — every entry of every bundled dataset — this method's output is byte-identical
	 * to what it produced before issues #237/#259.
	 *
	 * <p>{@code findings} is this injection's pre-answer chips, threaded through to
	 * {@link #orderedInteractionNotes} so a promoted interaction note can name its partner the way the
	 * chip about that same rule named it (issue #297). It is the caller's own list rather than a
	 * second validation, which is the point — see {@link #reconciledPartnerNoteName}.
	 */
	private static RenderedReference render(DrugReference ref, List<DrugReference> orderEntries,
			ContraindicationReading reading, SubstanceRendering substance,
			List<SafetyWarning> findings) {
		PatientClinicalContext context = reading.context();
		Integer age = context != null ? context.getAgeYears() : null;
		StringBuilder sb = new StringBuilder("Drug reference — ").append(ref.getName());
		StringBuilder paren = new StringBuilder();
		if (ref.getDrugClass() != null && !ref.getDrugClass().isEmpty()) {
			paren.append(ref.getDrugClass());
		}
		// Normalized (not raw) codes: null/blank elements in an operator-authored file must not
		// leak a literal "null" into the record the LLM cites.
		Set<String> atcCodes = ref.normalizedAtcCodes();
		if (!atcCodes.isEmpty()) {
			if (paren.length() > 0) {
				paren.append("; ");
			}
			paren.append("ATC ").append(String.join(", ", atcCodes));
		}
		if (paren.length() > 0) {
			sb.append(" (").append(paren).append(")");
		}
		sb.append(".");

		// BEFORE everything it qualifies, which is the whole record — the same reason issue #208 item 2
		// puts the contraindication reading in front of the list rather than after it: a model reading
		// forward has the qualifier before the content, and the numbers below are the first content it
		// reaches.
		sb.append(rowAttribution(ref, substance.subject));

		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band != null) {
			sb.append(" Dosing for ages ").append(band.getMinYears()).append("-").append(band.getMaxYears())
					.append(": ").append(dosingNumbers(band));
			if (band.getMaxDailyDoseMg() <= 0) {
				sb.append(" (no pediatric daily maximum published for this age — consult a dosing reference)");
			}
			sb.append(".");
		}

		// AFTER the sentence it extends, unlike the attribution clause above: this is more content of the
		// same kind rather than a qualifier on it, and issue #208's "qualifier before the content" rule is
		// about the latter. A model reading forward meets the row's own ceiling, then the others.
		appendSection(sb, " Also published for other rows of this substance: ",
				otherRowDosing(ref, substance.rows, band, age));

		// The dataset is operator-editable: a null/blank element in any section must degrade to
		// "skip that element" — never a thrown exception (which would fail the whole query) and
		// never a literal "null" in the record the LLM cites.
		List<String> warningLines = new ArrayList<String>();
		for (String warning : ref.getWarnings()) {
			addIfPresent(warningLines, warning);
		}
		appendSection(sb, " Warnings: ", warningLines);

		// No guard beyond appendSection's own: every collection is empty when the entry publishes no
		// contraindication rule, and the reading's sections are subsets of the clause list (see
		// contraindicationSections), so none of them can be non-empty when the list is.
		ContraindicationSections contraindications = contraindicationSections(ref, reading);
		// The patient-specific reading BEFORE the list it qualifies (issue #208 item 2), so a model
		// reading forward has the qualifier before the content — the same reason the interactions section
		// below promotes this patient's own partners to its front rather than appending them. Omitted
		// entirely when the context is null, which is "nothing known" and not "nothing recorded": a record
		// that cannot see the chart must not report an absence — see
		// statesTheChartsContraindicationReading for the three things that decides.
		if (reading.states()) {
			// EVERY section named, each by its own clauses, and none left to be inferred from another.
			// Two weaker forms were tried live on the 3.7.1 standalone 2026-08-13 and BOTH were measured
			// failing on the model this module ships against:
			//   * positive half only ("…this patient's chart records: documented ibuprofen allergy.") —
			//     "List all contraindications to ibuprofen for this patient" was then answered "…for this
			//     patient include: documented ibuprofen allergy, active gastrointestinal bleeding, active
			//     peptic ulcer disease", which is WORSE than no marking at all: the unmarked record had
			//     been answered "the GENERAL contraindications listed in the drug reference include …",
			//     the distinction drawn by the model itself.
			//   * a bare "records: none" for an entry nothing matched — a question about amoxicillin for a
			//     patient with no penicillin allergy was answered "the patient has a documented
			//     amoxicillin allergy", quoting a clause of the list beside that very sentence.
			// Both failures are the same shape: a sentence that names some clauses and expects the reader
			// to infer the rest. So each clause is named on the side it is actually on — which is also why
			// issue #269 gave the uncorroborated clauses a section rather than dropping them out of the
			// reading. The three sections are disjoint and cover every clause the module can evaluate AND
			// get an answer about; a clause it cannot evaluate at all (an unrecognised rule type, a rule
			// with no token) is listed and claimed by none of them.
			appendSection(sb, RECORDED_READING_LEAD, contraindications.recorded);
			appendSection(sb, NOT_RECORDED_READING_LEAD, contraindications.notRecorded);
			// Third and last of the reading's sections, after the two that make a claim: it makes none —
			// see UNCORROBORATED_READING_LEAD and corroborated(). Last rather than first because a model
			// reading forward meets what the chart says before what it only appeared to say.
			appendSection(sb, UNCORROBORATED_READING_LEAD, contraindications.uncorroborated);
		}
		appendSection(sb, " Contraindicated with: ", contraindications.clauses);

		OrderedInteractions interactions = orderedInteractionNotes(ref, context, orderEntries, findings);
		List<InteractionNote> ordered = interactions.ordered;
		int withheld = 0;
		if (!ordered.isEmpty()) {
			// Cap what is *rendered* into the prompt, not what is parsed: a broad interaction
			// dataset (e.g. the ddinter source's Warfarin, ~934 partners) would otherwise write
			// tens of thousands of tokens into a single citable line and blow the LLM context
			// window. The safety validator still reads every interaction off the entry, so this
			// only bounds the grounding text. How many were withheld is reported on the
			// RecordMapping, never in this text — see RenderedReference.
			List<String> shown = new ArrayList<String>();
			int used = 0;

			// Segment 1 — the partners this patient is actually on. Never invisible: the full note
			// while the budget allows, else the compact "name (Severity)" form. Dropping one of
			// these is how the chip and the prose come to disagree, which is the whole defect this
			// ordering exists to fix, so the budget yields to them rather than the reverse. Bounded
			// by the patient's own active-drug list, not by the dataset's breadth.
			for (int i = 0; i < interactions.promotedCount; i++) {
				InteractionNote n = ordered.get(i);
				String piece = shown.isEmpty() || used + n.full.length() <= MAX_INTERACTION_RENDER_CHARS
						? n.full : n.compact;
				shown.add(piece);
				used += piece.length() + 2;
			}

			// Segment 2 — the dataset tail. It exists because this entry is also the only reference
			// material the model has about the drug in general, so the record must not read as if
			// the patient's own overlap were the drug's only interaction. What it does NOT need to
			// do is put mechanism prose for drugs this patient has nothing to do with in front of a
			// model that reports what it can see: measured live (issue #117), a patient on
			// simvastatin asked about erythromycin got the correct simvastatin finding in sentence
			// one and then ~1150 further characters reciting the ivosidenib and ixabepilone notes —
			// which rendered only because a short promoted note left budget to spend. The tail's job
			// is breadth, and one partner named with its severity states breadth; the mechanism text
			// is what is actionable for a partner the patient is actually on, and #117 also records
			// this quantised model garbling long verbatim copies, so every irrelevant paragraph
			// offered is a paragraph of mangled clinical prose a clinician may be shown.
			//
			// So: with a promoted partner present, exactly one representative, in the compact
			// "name (Severity)" form — with one operator-authored exception, since InteractionNote
			// keeps the full text for a rule carrying no token and no ATC (there is no name to shorten
			// to), so such a row can still land a full paragraph in this slot. That stays inside the
			// same one-note overshoot the budget already tolerates; it just is not always ~20 chars.
			// With none, the record has nothing patient-specific to say and
			// the general material IS its content, so the budget is spent on full notes exactly as
			// before, the first always rendering however long it is — the pre-existing "at least one
			// interaction is always shown" guarantee, which is why the explicit re-add this replaced
			// could only ever fire in the promoted case, and that case now always shows one.
			// MAX_INTERACTION_RENDER_CHARS stays a soft budget whose overshoot is at most one note,
			// and a compact representative shrinks that overshoot rather than widening it.
			int restStart = interactions.promotedCount;
			if (restStart == 0) {
				for (int i = 0; i < ordered.size(); i++) {
					String n = ordered.get(i).full;
					if (!shown.isEmpty() && used + n.length() > MAX_INTERACTION_RENDER_CHARS) {
						break;
					}
					shown.add(n);
					used += n.length() + 2;
				}
			} else if (restStart < ordered.size()) {
				shown.add(ordered.get(restStart).compact);
			}

			appendSection(sb, " Interactions: ", shown);
			withheld = ordered.size() - shown.size();
		}

		// The dataset attribution and the withheld count leave with the RenderedReference instead of
		// being appended here: everything in this string is quotable, and the model quoted both into
		// clinician-facing answers (issue #117). Trimmed and blank-coalesced for the same reason the
		// sections above are — the dataset is operator-editable.
		String source = ChartSearchAiUtils.firstNonBlank(ref.getSource());
		return new RenderedReference(sb.toString(), source != null ? source.trim() : null, withheld);
	}

	/**
	 * @return the clause saying which row of a substance this record describes, or the empty string when
	 *         this response names that substance by the very row being rendered — which is every record
	 *         of a one-row substance, and so every record any BUNDLED CURATED dataset can produce.
	 *
	 *         <p><b>Issues #237 and #259.</b> The row a record renders is {@link DrugReference#canonicalRow}'s,
	 *         a fold over the dataset that cannot see the chart; the row every CHIP names is
	 *         {@link DrugSafetyValidator#interactionSubject}'s, which ranks the patient's own record
	 *         first (issues #187, #194, #206). So wherever the chart names a non-canonical row, one
	 *         response called one substance two things, in two citable records:
	 *         <pre>
	 *         [4] Drug reference — Dexamethasone (ATC …)
	 *         [5] Safety finding — Dexamethasone (ophthalmic): … interacts with active order phenytoin
	 *         </pre>
	 *         Measured 2026-08-14 over the shipped 19 MB KB through the real {@code injectRecords} and
	 *         the real {@code validate}: 104 of its 129 multi-row substances, being every one that could
	 *         be posed with both a record and a chip. Re-measure before relying on the figure.
	 *
	 *         <p><b>#259 is the same split reaching a NUMBER</b>, and a number is worse: the record
	 *         renders the canonical row's age band, so a clinician reading the cited record sees
	 *         {@code maximum 3000 mg/day} beside a chip that warned at the charted row's 2000, with
	 *         nothing saying whose 3000 that is. This clause says whose it is, and the record's headline
	 *         ceiling stays its own row's, exactly as issue #244 kept the chip's. It is <b>not</b> the
	 *         whole of #259: naming the row does not put the chip's 2000 into the citable evidence, which
	 *         is {@link #otherRowDosing}'s half and the reason that method exists beside this one.
	 *
	 *         <p><b>Rendering the CHARTED row instead was measured and declined.</b> Over the same KB,
	 *         drawing the patient's partner from the canonical row, a record rendered from the charted
	 *         row fails to name that partner in <b>74 of 129</b> families against 0 for the canonical row
	 *         — the route-unspecified row is the one carrying the breadth, which is why
	 *         {@code canonicalRow} was the right choice for issue #163 and still is. Swapping the row
	 *         would trade a naming fix for a coverage loss, and would move every citation's
	 *         {@code resourceId} besides. So this changes no row's turn to be rendered; what it changes
	 *         is that the record SAYS which row it is.
	 *
	 *         <p><b>Worded as a CONTRAST</b>, and the wording is issue #244's rather than a new one —
	 *         {@code DrugSafetyValidator.ceilingAttribution} solved this exact problem for the chip and
	 *         its reasoning transfers whole: a bare second name reads as a second formulation in play,
	 *         while naming both rows and saying which claim attaches to which leaves the sentence a fact
	 *         about the DATASET, which is what it is. The guard is literally shared
	 *         ({@link DrugSafetyValidator#worthNamingApart}) rather than restated, because the case it
	 *         exists for is the same one: an operator-editable file may put two rows under ONE display
	 *         name, for which "for X, not for X" is a contradiction shown to a clinician.
	 *
	 *         <p><b>NOT gated on the {@code drugSafety.*} switches</b>, which is the opposite of the
	 *         patient-specific reading rendered a few lines above it and so has to be said rather than
	 *         left to look like an oversight. Issue #208's reading ADDS a claim about the patient and is
	 *         the record's half of a chip, so with the chips off it would be prose with no chip and it
	 *         stands down with them ({@link #statesTheChartsContraindicationReading}). This clause does
	 *         the reverse: it NARROWS a claim the record makes anyway — the ceiling, the notes and the
	 *         drug's name are rendered whatever those switches say. Gating a correction on a switch ships
	 *         the UNCORRECTED sentence whenever the switch is off, which is issue #259 reachable by
	 *         configuration. {@code ReferenceRecordRowAttributionToggleContextTest} pins both switches,
	 *         with the #208 reading standing down in the same record as the witness that the toggle
	 *         really moved.
	 *
	 *         <p><b>A null {@code subject} is the common case, not a defensive check.</b> It is
	 *         {@link #chartAnchoredSubject}'s "the chart names no row of this substance in particular" —
	 *         which covers every one-row substance, every patient with nothing charted for the drug, and
	 *         a null context, since an empty recorded-name set ties every row and leaves the answer to
	 *         the fold. That is what keeps this sentence's "the row this patient's record names"
	 *         truthful: it is printed only where a recorded name out-claimed the other rows.
	 */
	private static String rowAttribution(DrugReference ref, DrugReference subject) {
		// Only where the DATASET declared these rows one substance. Otherwise the group was keyed on
		// getId() — matchingEntries' documented fallback for a source publishing no substance name — and
		// a curated file may repeat an id (the parse drops an entry only for a blank id or name, and no
		// validity rule reports a duplicate). Two rows sharing an id are one CITABLE entity and not
		// necessarily one substance, so the sentence's "filed separately for the same substance" would
		// claim something the file never said. Silence there costs nothing: the record renders exactly as
		// it did before issues #237/#259.
		if (subject == null || ref.substanceKey() == null) {
			return "";
		}
		// getName(), NEVER displayLabel(): the synonym-augmented label is that method's chip-display
		// vocabulary, and DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText pins it
		// out of THIS record's text — which is the property to cite, not "it never enters prompt text",
		// a claim displayLabel's own javadoc used to make and which the safety_finding record falsifies
		// (renderFinding copies a chip detail verbatim). This clause is prompt text like the rest of it,
		// and it must name the rows the way the header above names them or the sentence would contrast a
		// name the record never uses.
		// (That is why worthNamingApart takes two strings rather than two rows: the chip supplies its
		// display vocabulary and the record its prompt vocabulary, and only the comparison is shared.)
		String rendered = ref.getName();
		String named = subject.getName();
		if (!DrugSafetyValidator.worthNamingApart(rendered, named)) {
			return "";
		}
		// "Published BY THIS DATASET for", not a bare "Published for": the shorter form reads as a
		// clinical claim — "indicated for X, not for Y" — which is a licensing statement this module has
		// no basis for and the opposite of what the sentence means. Naming the dataset is what keeps the
		// attribution a fact about where the row was FILED, which is the same reason
		// DrugSafetyValidator.ceilingAttribution says "a ceiling this dataset publishes for".
		return " Published by this dataset for " + rendered + ", not for " + named + " — the row this "
				+ "patient's record names, filed separately for the same substance.";
	}

	/**
	 * @return one item per OTHER row of {@code ref}'s substance that publishes dosing for this patient's
	 *         age differing from {@code band}'s, naming the row and its numbers — empty for every one-row
	 *         substance, which is every entry of every bundled dataset and of every {@code ddinter} file.
	 *
	 *         <p><b>Issue #259, the numeric half.</b> {@link #rowAttribution} says WHICH row a record
	 *         describes, which settles a name. It does not settle a NUMBER: the record rendered one row's
	 *         band alone while {@code DrugSafetyValidator.addOverdose} reads the band of the subject row
	 *         AND of every sibling — so a response could carry {@code maximum 3000 mg/day} as its citable
	 *         reference beside a chip warning at another row's 2000, and a clinician reading the record
	 *         had no route to the number the warning used. The asymmetry is the defect rather than either
	 *         surface's choice of row, so the record is given the row set the chips already fold. No row's
	 *         turn to be RENDERED changes — that was measured and declined (see {@link #rowAttribution}).
	 *
	 *         <p><b>The guard is deliberately NOT {@link DrugSafetyValidator#worthNamingApart}</b>, which
	 *         {@link #rowAttribution} shares with {@code DrugSafetyValidator.ceilingAttribution}. That
	 *         predicate asks whether CONTRASTING two rows would say anything, and "for X, not for X" is a
	 *         contradiction. This is an ENUMERATION under a lead that already says these are other rows,
	 *         so an item whose name folds to the record's own still says something actionable — a
	 *         different number — and staying silent there would keep the defect in the one dataset shape
	 *         where the chip's own attribution is also silent, i.e. where this record is the reader's only
	 *         route to the ceiling. Asking one question with the other's predicate is the conflation
	 *         CLAUDE.md's ATC bullet forbids, one feature along; the name is checked for BLANKNESS only,
	 *         which is the degradation every section of this record takes on an operator-editable dataset.
	 *
	 *         <p><b>What bounds it, each bound load-bearing.</b> Only where the dataset declared these
	 *         rows one substance ({@link DrugReference#substanceKey()} non-null) — the {@code getId()}
	 *         fallback groups rows a file never called one substance, and "other rows of this substance"
	 *         would then claim that about a number. Only bands matching the patient's age, which is the
	 *         record's existing rule and not a new one ({@code config.xml}: a pediatric maximum is never
	 *         surfaced for an adult query). Only rows whose numbers DIFFER from the rendered row's,
	 *         compared as the strings this record would print — so "differs" means "would say something
	 *         different" rather than "differs in a double", and a substance whose rows agree pays nothing.
	 *         And only the rows THIS PASS resolved, never a walk of the dataset: a record is not a
	 *         formulary, and issue #163 exists because near-duplicate row material crowds the chart out of
	 *         the prompt.
	 *
	 *         <p><b>Why DOSING crosses rows here while interaction PARTNERS do not.</b> The two look like
	 *         an inconsistency and are not: {@code RenderedReference.withheldInteractions} records that a
	 *         partner carried only by a sibling row is absent from this record, and it stays absent. What
	 *         separates them is size, which is issue #163's whole subject — one sibling's dosing is one
	 *         clause, while a sibling's partner list is the thing that reaches
	 *         {@link #MAX_INTERACTION_RENDER_CHARS} on its own (the {@code ddinter} source's Warfarin row
	 *         publishes ~934). Widening this to partners would spend the budget #163 exists to protect;
	 *         that is the reason, and not that a sibling's partners would be less true.
	 *
	 *         <p><b>The phrasing is load-bearing outside this class, and newly so.</b> The model may recite
	 *         a record it is told to cite, so a ceiling here can arrive back in the ANSWER that
	 *         {@code DrugSafetyValidator} parses for a prescribed dose. Its {@code LIMIT_CUE} reads a
	 *         number as a ceiling only when a cue ({@code maximum}, {@code up to}, {@code no more than} …)
	 *         sits BEFORE it, so every number here keeps the {@code "maximum N mg/day"} order the rendered
	 *         row's own sentence uses; written {@code "N mg/day max"} a recited ceiling becomes a stated
	 *         dose.
	 *
	 *         <p>Why that guard did not matter before and does now: dose attribution is CLAUSE-scoped and
	 *         alias-anchored, and the rendered row's dosing sentence carries no drug name, so its ceiling
	 *         is attributed to nobody however it is worded. An item here is the first sentence THIS CLASS
	 *         composes that pairs a row's NAME with a published ceiling inside one clause — dataset free
	 *         text can of course pair anything, and always could. It always pairs a row with its OWN
	 *         ceiling, so
	 *         the figure can exceed something only where the SUBJECT row is a different row publishing a
	 *         stricter one — measured, not argued: mutating {@code maximum} out of {@code LIMIT_CUE} left
	 *         this file green until a fixture pair shaped that way existed, and then produced
	 *         {@code "The stated Nitrofurantoin dose ~200 mg/day exceeds the 100 mg/day maximum"} from a
	 *         record nobody had prescribed anything from.
	 *         {@code ReferenceRecordSubstanceCeilingsTest.aRecitedRecordIsReadAsCeilingsAndNotAsADose}
	 *         feeds this record back through the real {@code validate} and is what pins it.
	 *
	 *         <p><b>The residue, stated rather than discovered.</b> The chips' post-answer pass can also
	 *         resolve rows the ANSWER's own wording names, which no pre-answer record can carry — it is
	 *         written before the answer exists. So this closes every shape reachable from the pass that
	 *         writes the record, and the bound is the rows that pass resolved
	 *         ({@code DrugSafetyValidator.resolvedSubstanceRows}, which the ANSWER still widens
	 *         deliberately per issue #175). This used to cite
	 *         {@code DrugSafetyValidator.SubstanceSubjects} as recording the same bound, and since issue
	 *         #238 it no longer does: what a substance is CALLED is now folded over the question's and the
	 *         orders' rows alone, so the two passes no longer differ over the ANSWER when they name a
	 *         substance, while still differing over which rows are in play — which is the bound this
	 *         residue is about. "No longer differ over the answer" and not "agree": each pass reads the
	 *         chart itself, which {@code DrugSafetyValidator.SubstanceSubjects} records.
	 *
	 * @param band the rendered row's own band for this patient, or null when it publishes none — in which
	 *        case every sibling band differs, which is the starker form of the same defect: the record
	 *        carried no number at all while {@code anyActionableBand} let a chip warn on a sibling's
	 */
	private static List<String> otherRowDosing(DrugReference ref, List<DrugReference> rows,
			DrugReference.AgeBand band, Integer age) {
		List<String> items = new ArrayList<String>();
		// Asked of the RENDERED row alone, and that is sufficient rather than lax — but only because of
		// something invisible here: `collect` keys a declared substance on substanceKey(), which is a
		// LIST, and the fallback on getId(), which is a String. The two can never collide, so a group
		// whose rendered row declares a substance holds only rows sharing that same declared key. Were
		// substanceKey() ever to return a String, an entry whose id equals another entry's substance name
		// would join that group and this loop would call it a row of the same substance.
		if (ref.substanceKey() == null) {
			return items;
		}
		String rendered = band != null ? dosingNumbers(band) : null;
		for (DrugReference row : rows) {
			// Identity, not equals: DrugReference defines none, and the rendered row is one of these
			// objects rather than a copy of it.
			//
			// NOT independently observable, said rather than left to look tested: removing this skip
			// reddens NOTHING (mutated 2026-08-18), because the rendered row's own numbers are by
			// construction the ones the difference test below excludes, and where it publishes no band for
			// this age the null check above drops it. It is kept as the loop's own statement of what
			// "other rows" means rather than as insurance the suite checks — a future edit to that
			// difference test would otherwise decide, silently, whether a record can name itself.
			if (row == ref || ChartSearchAiUtils.isBlank(row.getName())) {
				continue;
			}
			DrugReference.AgeBand other = row.bandForAge(age);
			if (other == null) {
				continue;
			}
			String numbers = dosingNumbers(other);
			if (numbers.equals(rendered)) {
				continue;
			}
			// No de-duplication of identical items, deliberately. Two rows of one substance carrying the
			// same name AND the same numbers would print twice, and that is a bad-DATA shape — a duplicated
			// row — which CLAUDE.md's validity bullet says belongs to DrugReferenceValidity as one rule
			// with one remedy, not to a guard at the one call site that happens to notice it. No rule
			// reports it today; if one is added, this needs no change.
			items.add(row.getName() + " " + numbers + " (ages " + other.getMinYears() + "-"
					+ other.getMaxYears() + ")");
		}
		return items;
	}

	/** @return the numbers a band publishes, in the vocabulary this record states them in — shared by the
	 *          rendered row's own dosing sentence and by every row {@link #otherRowDosing} names, so the
	 *          two cannot come to word one dataset's ceilings two ways, and so "these rows publish the
	 *          same dosing" can be asked as "these would print the same". The rendered row's sentence adds
	 *          the missing-daily-maximum advice around this; a sibling item does not repeat advice. */
	private static String dosingNumbers(DrugReference.AgeBand band) {
		StringBuilder sb = new StringBuilder(DrugReference.formatNumber(band.getMgPerKgMin())).append("-")
				.append(DrugReference.formatNumber(band.getMgPerKgMax())).append(" mg/kg per dose");
		if (band.getMaxDailyDoseMg() > 0) {
			sb.append(", maximum ").append(DrugReference.formatNumber(band.getMaxDailyDoseMg()))
					.append(" mg/day");
		}
		return sb.toString();
	}

	/** Appends one section of a rendered record — {@code lead}, the items joined by the {@code "; "}
	 *  every section of this record separates its items with, and the full stop — or NOTHING when there
	 *  are no items. Every section obeys that rule for the same reason: an empty
	 *  "Recorded for this patient: ." states nothing and spends prompt budget saying it, and the dataset
	 *  is operator-editable so any section can arrive empty. Written out four times before issue #208
	 *  needed a fifth, and #259 a sixth ({@link #otherRowDosing}, whose emptiness is the common case). */
	private static void appendSection(StringBuilder sb, String lead, Collection<String> items) {
		if (!items.isEmpty()) {
			sb.append(lead).append(String.join("; ", items)).append(".");
		}
	}

	/** The contraindication half of a rendered record: every rule the entry publishes, and that list
	 *  split by what the patient's own chart records. One value rather than four calls because all four
	 *  are computed in ONE walk of the rules — the reading's sections are selections FROM the clauses,
	 *  keyed on the same collapsed rule, so recomputing any of them beside the others is how a record
	 *  comes to mark a clause it does not carry (or carry one it cannot mark).
	 *
	 *  <p>All three reading sections are empty where the injection may state no reading at all
	 *  ({@link #statesTheChartsContraindicationReading}), so no consumer can read a partition that was
	 *  never computed. Otherwise they are subsets of {@code clauses} in clause order and pairwise
	 *  disjoint, and together they are every clause but ONE shape: a rule
	 *  {@link DrugSafetyValidator#evaluatesAgainstTheChart} rejects is in the LIST and in no section,
	 *  because the record may not say a patient does not have something nobody checked. Issue #269 did
	 *  not change WHAT is excluded — the clause it moved was in the recorded section, which was the
	 *  defect — and gave it a section of its own: {@code uncorroborated}, a clause the chart matched
	 *  that {@link #corroborated} could not support, which is neither a claim nor a denial. */
	private static final class ContraindicationSections {

		private final Collection<String> clauses;

		private final Collection<String> recorded;

		private final Collection<String> notRecorded;

		private final Collection<String> uncorroborated;

		ContraindicationSections(Collection<String> clauses, Collection<String> recorded,
				Collection<String> notRecorded, Collection<String> uncorroborated) {
			this.clauses = clauses;
			this.recorded = recorded;
			this.notRecorded = notRecorded;
			this.uncorroborated = uncorroborated;
		}
	}

	/**
	 * @return one clause per contraindication RULE, keyed by the very method the chip ledger keys on —
	 *         {@link DrugSafetyValidator#contraindicationFinding}, which is the {@code (type, token)}
	 *         pair normalized, except for an ALLERGY rule naming the entry it is filed on, which is
	 *         keyed on the SUBSTANCE (issue #146). Each clause carries the distinct notes its rows
	 *         authored, in dataset order.
	 *
	 *         <p><b>Issue #190 item 1.</b> This rendered one clause per ROW while
	 *         {@code DrugSafetyValidator.ContraindicationChips} raised one chip per rule, so an entry
	 *         filing one rule twice put two clauses in the record beside one chip and the model was told
	 *         the drug has two contraindications where the deterministic layer had found one. Keyed on
	 *         the rule the CHIP compares, not on the rendered text, so the two counts cannot drift —
	 *         which is why the exception issue #146 added on that side had to be added here too, and why
	 *         a future change to that key belongs in both places or in neither.
	 *
	 *         <p><b>Curated-source-only</b>, by construction rather than by measurement: neither
	 *         {@code ddinter} nor {@code atc} publishes contraindications at all, so only an
	 *         operator-authored file can file one rule twice — and the bundled seed does not (its four
	 *         ibuprofen rows are four distinct keys: since issue #146 the self-named allergy one is the
	 *         substance and the other three are their own {@code (type, token)}), so no shipped
	 *         rendering moves. {@code InjectedContraindicationClauseTest} pins both halves.
	 *
	 *         <p><b>Joined, not dropped</b>, and that is the deliberate difference from issue #174 site 2:
	 *         that collapse could discard a repeated row because the repeats were near-identical, while
	 *         two rows of one rule here carry two DIFFERENT operator-authored notes. The chip keeps only
	 *         the incumbent's (ties keep the incumbent, which
	 *         {@code ContraindicationRouteVariantTest.oneCuratedRuleAuthoredTwiceRaisesOneChip} pins), so
	 *         a record that dropped the sibling would remove that clinical instruction from the
	 *         deployment altogether — this record being the only place the prompt carries it. They are
	 *         joined with the em dash the module already attaches a note with, rather than with the
	 *         {@code "; "} that separates CLAUSES, so the join cannot read as a second contraindication.
	 *
	 *         <p>A rule whose note and token are both blank contributes nothing, exactly as before — the
	 *         dataset is operator-editable and every section must degrade to "skip that element" rather
	 *         than emit a literal {@code null}.
	 *
	 *         <p><b>Issue #208 item 2 — and which of them the patient's chart records, in the same
	 *         walk.</b> This record is the only reference material the prompt carries about the drug, so
	 *         the list stays the drug's whole list; what it may not do is leave a model unable to tell
	 *         the drug's properties from this patient's, because a model reports what it can see and
	 *         since issue #110 this record is citable evidence. Measured live on the 3.7.1 standalone:
	 *         a patient with no such condition on record got a record reading "Contraindicated with: …
	 *         active gastrointestinal bleeding", with no chip beside it. The predicate is
	 *         {@link DrugSafetyValidator#recordedContraindicationKind}, the chip arm's own, for the same
	 *         reason the KEY here is the chip ledger's own; and a clause is marked when ANY rule folded
	 *         into it matched, which is when the ledger raises a chip for that key. Since issue #269 the
	 *         positive marking additionally needs {@link #corroborated} of that rule, and a matched rule
	 *         it refuses takes a third section rather than either half — so the walk resolves each key as
	 *         a MAX over its rules, one corroborated rule carrying the key. Selecting
	 *         from the clauses in this walk rather than recomputing them afterwards is what keeps the
	 *         marked strings a subset of the rendered ones by construction.
	 *
	 *         <p><b>"When", and no longer "exactly when".</b> Since the active-order contraindication
	 *         arm became subject-matter scoped, a record rendered for an order the response is NOT about
	 *         can mark a clause the ledger raises no chip for: {@link #matchingEntries} admits an order
	 *         that merely shares a class with a question-named drug, and such an entry is not in the
	 *         drugs-in-play set, so its chip goes through that scoping while this marking does not. The
	 *         marking is a statement about the CHART and stays true either way, which is why it is not
	 *         gated here as well — this record must not report an absence it cannot substantiate
	 *         (issue #208 item 2), and scoping it would make it do exactly that. What the residue costs
	 *         is stated at {@link #preAnswerFindings}, where the prompt-versus-chip channels are
	 *         enumerated; it is unreachable on any bundled dataset.
	 */
	private static ContraindicationSections contraindicationSections(DrugReference ref,
			ContraindicationReading reading) {
		PatientClinicalContext context = reading.context();
		// The very key the chip ledger uses and the very clause the chip ledger's own corroboration fold
		// reads, from the very methods it uses, so "ALLERGY"/"Ibuprofen" and "allergy"/"ibuprofen" are
		// one rule here exactly as they are one chip there — including issue #146's exception, where an
		// allergy rule NAMING this entry is keyed on the substance because that is the fact it reports.
		// Shared rather than restated: a copy is how the two came apart when that exception was added,
		// two such rules under two aliases of one drug becoming one chip and two clauses, which is #190
		// item 1 re-opened one rule shape along. The chip's own key additionally carries the SUBJECT and
		// the patient's match, neither of which a record about the drug has any business consulting;
		// what has to agree is the collapse UNIT — and, since issue #308, the clause TEXT the three
		// sections below are resolved over, which DrugSafetyValidator.addContraindications reads to ask
		// this walk's own cross-key precedence question of the same strings.
		Map<Object, String> byRule = DrugSafetyValidator.contraindicationClauses(ref);
		Set<Object> recordedRules = new HashSet<Object>();
		Set<Object> uncorroboratedRules = new HashSet<Object>();
		Set<Object> unevaluableRules = new HashSet<Object>();
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			// A rule stating neither a note nor a token contributes no clause, so it is in no section:
			// the same emptiness contraindicationClauses skips, asked from the same method so the walk
			// and the clause list cannot come to disagree about which rules exist.
			if (DrugSafetyValidator.contraindicationClause(c) == null) {
				continue;
			}
			Object key = DrugSafetyValidator.contraindicationFinding(ref, c);
			if (DrugSafetyValidator.recordedContraindicationKind(c, context) != null) {
				// ANY rule of the collapsed key, because that is precisely when the ledger raises a chip
				// for it: two spellings of one rule are one clause and one chip, and the patient matching
				// either is the drug being contraindicated once.
				//
				// Which SECTION that key lands in is decided per rule and resolved as a MAX below, and the
				// max is not a formality: contraindicationFinding keys a self-named allergy rule on the
				// SUBSTANCE (issue #146), so two such rules of one entry under different tokens are one
				// clause, while corroborated() reads each rule's own token — so they can disagree, and one
				// corroborated rule of the key is enough for the key.
				//
				// SINCE ISSUE #308 THIS FOLD HAS A SECOND SPELLING, and a change here belongs in both
				// places: DrugSafetyValidator.addContraindications folds the same question for the
				// injected safety_finding, because that record states the answer too and the two must not
				// disagree about one chart. Deliberately NOT unified — this walk resolves keys no chip was
				// raised for, since a record renders the whole rule list with or without one — and
				// deliberately over the SAME unit, which is the point rather than an accident: this
				// entry's matched rules, keyed by contraindicationFinding, unscoped by subject matter.
				// BOTH STAGES of it, and the second is the one below rather than this one: the sections
				// are resolved over clause TEXT as well as over keys (uncorroborated.removeAll(recorded)),
				// so a fold stopping here leaves that walk stating a string as this chart's reading while
				// the finding beside it hedges the identical string. ADR Decision 44 records the units
				// that were tried first and what each printed.
				// Asked only where the record may state
				// the reading at all: otherwise no section is rendered, and asking would resolve the
				// patient's allergy list for a sentence nothing prints (see ContraindicationReading).
				if (reading.states() && !corroborated(ref, c, reading)) {
					uncorroboratedRules.add(key);
				} else {
					recordedRules.add(key);
				}
			} else if (!DrugSafetyValidator.evaluatesAgainstTheChart(c)) {
				// A rule this module cannot put to the chart at all — an unrecognised type, or no token to
				// look for. Not the same as "the chart says no", and the record may not say it is.
				unevaluableRules.add(key);
			}
		}
		// Walked in CLAUSE order, not in the order the matches were found: a rule authored twice can be
		// matched by its second spelling while its clause sits at the first's position, and a reading
		// that listed those out of order would be a section a reader cannot line up against the list. One
		// loop for all three, so they follow the clauses rather than agreeing with them.
		//
		// SETS of clause TEXT, and the weaker claim yields: two rules of different keys may render the
		// same string — an allergy rule and a condition rule may carry one note, which is a natural way to
		// author "recorded either way" — and "Recorded for this patient: X. Not recorded for this patient:
		// X." is a record contradicting itself. Whichever section is true of the string is the one that
		// keeps it, and the recorded one is the one that can be true.
		//
		// Extended to three, in that order: of the two that remain, only the DENIAL can be false of the
		// string, so it is the one that yields. The uncorroborated section asserts nothing about the
		// patient, so it cannot contradict a section that does — but printing it beside a denial of the
		// same words would still say the module both could and could not answer for them.
		Set<String> recorded = new LinkedHashSet<String>();
		Set<String> notRecorded = new LinkedHashSet<String>();
		Set<String> uncorroborated = new LinkedHashSet<String>();
		// All three left EMPTY where the record may state no reading, rather than filled with a partition
		// nothing prints. The walk above still runs — the clause LIST is rendered either way — but with no
		// reading there is nothing true to put in these, and the corroboration question was not asked, so
		// a caller reading them would get "recorded" for a clause nothing corroborates. render() happens
		// to gate them itself; that is not a property to leave a future consumer resting on.
		if (reading.states()) {
			for (Map.Entry<Object, String> clause : byRule.entrySet()) {
				if (recordedRules.contains(clause.getKey())) {
					recorded.add(clause.getValue());
				} else if (uncorroboratedRules.contains(clause.getKey())) {
					uncorroborated.add(clause.getValue());
				} else if (!unevaluableRules.contains(clause.getKey())) {
					notRecorded.add(clause.getValue());
				}
			}
			uncorroborated.removeAll(recorded);
			notRecorded.removeAll(recorded);
			notRecorded.removeAll(uncorroborated);
		}
		return new ContraindicationSections(byRule.values(), recorded, notRecorded, uncorroborated);
	}

	/**
	 * The budget the drug-reference ENTRIES spend, for the DEBUG line in {@code injectRecords}.
	 *
	 * <p>Deliberately narrower than {@code ChartSearchAiUtils.referenceSlice}, which counts every
	 * reference-group record and is what the audit row carries (issue #229). This one is issue #163's
	 * question — one near-duplicate entry per route variant — and
	 * {@code ReferenceRecordSubstanceCollapseTest.theDebugLineReportsTheDrugReferenceEntryCharacterTotalAndOnlyThat}
	 * pins it to exclude the safety findings rendered beside those entries. Keep both; a reader
	 * replacing either with the other reports one population under the other's name.
	 *
	 * @return how many characters of {@code drug_reference} record text {@code mappings} carries
	 */
	private static int referenceCharacters(List<RecordMapping> mappings) {
		int chars = 0;
		for (RecordMapping mapping : mappings) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(mapping.getResourceType())
					&& mapping.getText() != null) {
				chars += mapping.getText().length();
			}
		}
		return chars;
	}

	/** Adds {@code value} to {@code out} only when it is non-null and non-blank. */
	private static void addIfPresent(List<String> out, String value) {
		if (!ChartSearchAiUtils.isBlank(value)) {
			out.add(value.trim());
		}
	}
}
