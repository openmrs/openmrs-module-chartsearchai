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
 * than on "the module injected it".
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
 *       code hit OR the order's own display name) rather than the ATC hit alone, so this layer and
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
	 */
	static final int MAX_INTERACTION_RENDER_CHARS = 1500;

	/** querystore's resource type for a drug-order document (its {@code DrugOrderRecordSerializer}
	 *  contract), which the chart carries through unchanged. The type the active-order
	 *  reconciliation looks for, and the type it asks the chart's completeness declaration about. */
	private static final String QUERYSTORE_DRUG_ORDER_TYPE = "drug_order";

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
	 *       (issue #143, {@code DrugSafetyValidator.addActiveOrderContraindications}) — whose GATE and
	 *       whose SUBJECTS read no question and no answer at all, only the chart. It does read the
	 *       drugs-in-play set, but only to skip what the loop above has already covered; see the
	 *       parenthetical below.</li>
	 * </ul>
	 * Neither can therefore differ between this pre-answer pass and the post-answer chips pass, which
	 * is the property that keeps a finding in the prompt from ever being asserted without a chip beside
	 * the answer. (The #143 arm skips a drug already in play, so a question naming one of the patient's
	 * own orders moves that chip from this arm to the drug-in-play loop rather than adding or dropping
	 * one — the same chips, from a different arm.)
	 *
	 * <p>The list is empty whenever the deterministic layer finds nothing, so a question that nothing
	 * bears on gains no record and its abstention survives by construction rather than by prompt
	 * wording — the direction issue #107 guards.
	 */
	List<SafetyWarning> preAnswerFindings(PatientClinicalContext context, String question) {
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
		return drugSafetyValidator.validate("", question, context);
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
		// Resolved once and kept, exactly as DrugSafetyValidator.validate does: orderedInteractionNotes
		// groups a partner by the active-order ENTRY the rule names (issue #190 item 2), which is the
		// chip's own key, so these entries have to be the same resolution the names above come from —
		// two resolutions is how the record and the chip come to disagree about which rows are one
		// partner, which is the very thing that grouping exists to settle. Since issue #151 the same
		// list is also the order-driven leg's candidate set, which used to resolve itself and by a
		// narrower key — see matchingEntries.
		List<DrugReference> orderEntries = drugReferenceService.findForActiveOrders(rawContext);
		PatientClinicalContext context = drugReferenceService.withReferenceNames(rawContext, orderEntries);
		List<DrugReference> matched = matchingEntries(orderEntries, question);
		List<SafetyWarning> findings = preAnswerFindings(context, question);
		List<PatientClinicalContext.ActiveDrugOrder> unrepresented = unrepresentedActiveOrders(chart, context);
		if (matched.isEmpty() && findings.isEmpty() && unrepresented.isEmpty()) {
			return chart;
		}

		Integer age = context != null ? context.getAgeYears() : null;
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

		for (DrugReference ref : matched) {
			RenderedReference rendered = render(ref, age, context, orderEntries);
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

		// The drug-reference character total is here because that slice's SIZE is the thing issue #163 is
		// about and the REST response cannot show it: the response returns only CITED references, so a
		// question injecting one near-duplicate record per route variant looked identical from outside
		// while spending several times the prompt budget. A count alone did not settle it either — what
		// crowds out chart records is characters — so an operator (or a verification pass) can now read
		// both off one line.
		log.debug("Injected {} active-order, {} drug-reference ({} chars) and {} safety-finding record(s) "
				+ "into chart for question '{}'", unrepresented.size(), matched.size(),
				referenceCharacters(mappings), findings.size(), question);
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
	 * drug-order record whose text names the drug. The name fallback is deliberate insurance in the
	 * conservative direction: were the uuid contract to change, uuid-only matching would report
	 * every order as missing on every query, and a WARN that fires always reports nothing. A
	 * <em>live</em> drug-order record naming the drug already tells the model the patient has an
	 * order for it, so there is nothing for the answer to deny and nothing to repair.
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
	 * flow. Only records describing a live order now substantiate one
	 * ({@link #describesEndedOrder}).
	 *
	 * <p>Second, the match itself was a plain substring test, so a short order name was found inside
	 * an unrelated word — an active {@code ASA} order read as substantiated by
	 * {@code "Drug order: Nasal spray"} — and a sibling record could mask an order whose name is a
	 * substring of it ({@code Aspirin} inside {@code Aspirin/Dipyridamole}).
	 * {@code ActiveDrugOrder.namedIn} now shares {@link DrugReference#containsWord} — the existing
	 * symmetric-boundary rule that already backs alias-in-prose matching — rather than introducing a
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
		StringBuilder drugOrderText = new StringBuilder();
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
				String lower = mapping.getText().toLowerCase(Locale.ROOT);
				if (!describesEndedOrder(lower)) {
					drugOrderText.append(lower).append('\n');
				}
			}
		}
		String drugOrderTextLower = drugOrderText.toString();

		List<PatientClinicalContext.ActiveDrugOrder> unrepresented =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			boolean substantiated = (order.getUuid() != null && chartResourceUuids.contains(order.getUuid()))
					|| order.namedIn(drugOrderTextLower);
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

	/** Lowercased marker querystore renders a drug order's END date under — it emits
	 *  {@code ". Stopped: <date>"} for both {@code getDateStopped()} and {@code getAutoExpireDate()}
	 *  (its {@code DrugOrderRecordSerializer} contract). */
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
	 * <p>Why this reads the rendered text rather than a status field. querystore DOES carry the
	 * structural signal — its {@code putOrderBaseFields} puts {@code action}, {@code date_stopped}
	 * and {@code auto_expire_date} into the {@code QueryDocument} metadata — but that metadata is
	 * dropped three layers upstream of here: {@code QueryStoreChartBuilder.toSerializedRecords}
	 * carries only the obs-group fields into {@code SerializedRecord}, and the reconciliation sees
	 * only the resulting {@link RecordMapping} (index, type, uuid, date, text). Threading a status
	 * flag through would mean widening two shared internal types, and — the reason it is not simply
	 * the better fix — it would rest on metadata surviving querystore's index round-trip, which is
	 * unverified here and unverifiable in this module's tests (querystore does not index under
	 * {@code BaseModuleContextSensitiveTest}). The rendered text, by contrast, is demonstrably what
	 * the chart carries: it is the same {@code getText()} this method already matches names against.
	 * Plumbing the structural field is the better long-term fix once that round-trip is confirmed.
	 *
	 * <p>Because it keys on rendered prose, the markers are pinned against the REAL querystore
	 * serializer's output in {@code QuerystoreOrderTextMarkerTest} — a wording change there fails
	 * loudly instead of silently reopening issue #118.
	 *
	 * <p><strong>Known limitation, and the strongest argument for the structural fix above.</strong>
	 * Running that serializer (rather than reading it) showed querystore does NOT render an
	 * auto-expire date into the text: an order that lapsed by {@code autoExpireDate} passing carries
	 * no end marker at all, so it can still substantiate a live order. querystore does carry
	 * {@code auto_expire_date} in the document METADATA, so the structural route would cover this
	 * case and rendered prose cannot. The narrower renewal shape this method exists for — an order
	 * explicitly stopped or discontinued — IS covered, and {@code QuerystoreOrderTextMarkerTest}
	 * pins the auto-expire gap so it fails loudly if querystore ever starts rendering it.
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
	 */
	static String renderActiveOrder(PatientClinicalContext.ActiveDrugOrder order) {
		return "Active drug order: " + order.getDisplay() + ".";
	}

	/**
	 * Deduplicated union of question-driven and patient-driven matches, query matches first — <b>one
	 * entry per SUBSTANCE</b>, not one per reference row (issue #163, see {@code collect}).
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
	 *       monotone and is the one issue #163 asks for ({@link DrugReference#canonicalRow} only ever
	 *       moves toward {@link DrugReference#namesNoRoute()}), and it makes this record agree with the
	 *       chip layer's subject rather than diverge from it.</li>
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
	 */
	List<DrugReference> matchingEntries(List<DrugReference> orderEntries, String question) {
		// One record per SUBSTANCE, not per reference row (issue #163). A per-call local, never a field:
		// a memoised DrugReference outliving a getAll() hot-reload breaks the reference comparisons the
		// safety arms make against the same objects (issue #172).
		Map<Object, DrugReference> bySubstance = new LinkedHashMap<Object, DrugReference>();

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

		return new ArrayList<DrugReference>(bySubstance.values());
	}

	/**
	 * Record {@code ref} as the entry to inject for its substance, keeping the row that best represents
	 * it ({@link DrugReference#canonicalRow}) when the substance is filed as several.
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
	private static void collect(Map<Object, DrugReference> bySubstance, DrugReference ref) {
		Object substance = ref.substanceKey();
		Object key = substance != null ? substance : ref.getId();
		bySubstance.put(key, DrugReference.canonicalRow(bySubstance.get(key), ref));
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
	 * One deterministic finding as a chart line. The detail text is reused verbatim — it is the same
	 * string the clinician already sees on the chip, so the prose and the chip cannot describe the
	 * same finding differently.
	 */
	static String renderFinding(SafetyWarning finding) {
		return FINDING_PREFIX + finding.getDrug() + ": " + finding.getDetail();
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
	 * partner promoted here, and the rendered text cannot drift from that chip.
	 *
	 * <p>Scoped to that arm deliberately, and the scope is the correction
	 * {@link DrugSafetyValidator#addQuestionPairInteractions} asks for: across the whole chip set the
	 * correspondence does not hold, because a question-PAIR chip names two drugs the question named
	 * and neither need be an active order, so its partner is promoted nowhere. That does not reopen
	 * the chip-versus-prose split this ordering exists to close — since issue #110 every chip is also
	 * injected verbatim as its own numbered, citable record ({@code preAnswerFindings} →
	 * {@link #renderFinding}), so a pair finding is grounded by that record rather than by these
	 * notes, and the promoted-note budget is untouched by it.
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
	 * between them (measured on the bundled sample: methotrexate 783 + aspirin 809 against a 1500
	 * budget), and dropping the second reinstates exactly the chip-versus-prose split described
	 * above for the polypharmacy case. So the cap becomes a soft budget with a bounded overshoot
	 * rather than a hard ceiling — bounded by the patient's own active-drug count, not the dataset's
	 * breadth, and paid in the compact {@code name (Severity)} form rather than in full notes.
	 *
	 * @param context may be null (nothing to prioritise by) — the section then keeps dataset order
	 * @param orderEntries the reference entries the patient's active orders resolve to, which
	 *        {@link #onePerPartner} keys a promoted partner on (issue #190 item 2); an empty list falls
	 *        the grouping back to the label alone, as it was before that issue
	 */
	static OrderedInteractions orderedInteractionNotes(DrugReference ref, PatientClinicalContext context,
			List<DrugReference> orderEntries) {
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
			String label = DrugSafetyValidator.partnerLabel(i);
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
		// bundled sample: a patient on lisinopril (Moderate x ibuprofen, 910 chars) and aspirin
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
	 *               grouping, and issue #121's invariant — the key IS what the chip says — is
	 *               deliberate rather than incidental.</li>
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
	 * Renders one reference entry into the citable line the LLM sees, plus the metadata that
	 * describes the rendering and must stay out of it — see {@link RenderedReference}. Numeric
	 * dosing is included only when an age band matches {@code age}; prose warnings,
	 * contraindications and interactions are always rendered.
	 *
	 * <p>{@code context} does two things here. It orders the capped {@code Interactions:} section — see
	 * {@link #orderedInteractionNotes} — and it splits the contraindication list into what this patient's
	 * chart records and what it does not (issue #208 item 2, {@link #contraindicationSections}). It may be
	 * null, which is "nothing known about the patient": the interactions section then keeps dataset order
	 * and the contraindication list is rendered with no reading at all, because a record that cannot see
	 * the chart must not report an absence. {@code orderEntries} is passed straight through to the
	 * interactions method, which groups a partner the patient is on by the entry it resolves to (issue
	 * #190 item 2).
	 */
	static RenderedReference render(DrugReference ref, Integer age, PatientClinicalContext context,
			List<DrugReference> orderEntries) {
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

		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band != null) {
			sb.append(" Dosing for ages ").append(band.getMinYears()).append("-").append(band.getMaxYears())
					.append(": ").append(DrugReference.formatNumber(band.getMgPerKgMin())).append("-")
					.append(DrugReference.formatNumber(band.getMgPerKgMax())).append(" mg/kg per dose");
			if (band.getMaxDailyDoseMg() > 0) {
				sb.append(", maximum ").append(DrugReference.formatNumber(band.getMaxDailyDoseMg())).append(" mg/day");
			} else {
				sb.append(" (no pediatric daily maximum published for this age — consult a dosing reference)");
			}
			sb.append(".");
		}

		// The dataset is operator-editable: a null/blank element in any section must degrade to
		// "skip that element" — never a thrown exception (which would fail the whole query) and
		// never a literal "null" in the record the LLM cites.
		List<String> warningLines = new ArrayList<String>();
		for (String warning : ref.getWarnings()) {
			addIfPresent(warningLines, warning);
		}
		if (!warningLines.isEmpty()) {
			sb.append(" Warnings: ").append(String.join("; ", warningLines)).append(".");
		}

		ContraindicationSections contraindications = contraindicationSections(ref, context);
		if (!contraindications.clauses.isEmpty()) {
			// The patient-specific reading BEFORE the list it qualifies (issue #208 item 2), so a model
			// reading forward has the qualifier before the content — the same reason the interactions
			// section below promotes this patient's own partners to its front rather than appending them.
			// Omitted entirely when the context is null, which is "nothing known" and not "nothing
			// recorded": a record that cannot see the chart must not report an absence.
			if (context != null) {
				// BOTH halves named, each by its own clauses, and neither left to be inferred from the
				// other. Two weaker forms were tried live on the 3.7.1 standalone 2026-08-13 and BOTH were
				// measured failing on the model this module ships against:
				//   * positive half only ("…this patient's chart records: documented ibuprofen allergy.")
				//     — "List all contraindications to ibuprofen for this patient" was then answered
				//     "…for this patient include: documented ibuprofen allergy, active gastrointestinal
				//     bleeding, active peptic ulcer disease", which is WORSE than no marking at all: the
				//     unmarked record had been answered "the GENERAL contraindications listed in the drug
				//     reference include …", the distinction drawn by the model itself.
				//   * a bare "records: none" for an entry nothing matched — a question about amoxicillin
				//     for a patient with no penicillin allergy was answered "the patient has a documented
				//     amoxicillin allergy", quoting a clause of the list beside that very sentence.
				// Both failures are the same shape: a sentence that names some clauses and expects the
				// reader to infer the rest. So each clause is named on the side it is actually on. The two
				// halves partition the list, so at least one sentence is always emitted and a clause is
				// never in neither.
				appendClauseReading(sb, " Recorded for this patient: ", contraindications.recorded);
				appendClauseReading(sb, " Not recorded for this patient: ", contraindications.notRecorded);
			}
			sb.append(" Contraindicated with: ").append(String.join("; ", contraindications.clauses))
					.append(".");
		}

		OrderedInteractions interactions = orderedInteractionNotes(ref, context, orderEntries);
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

			sb.append(" Interactions: ").append(String.join("; ", shown)).append(".");
			withheld = ordered.size() - shown.size();
		}

		// The dataset attribution and the withheld count leave with the RenderedReference instead of
		// being appended here: everything in this string is quotable, and the model quoted both into
		// clinician-facing answers (issue #117). Trimmed and blank-coalesced for the same reason the
		// sections above are — the dataset is operator-editable.
		String source = ChartSearchAiUtils.firstNonBlank(ref.getSource());
		return new RenderedReference(sb.toString(), source != null ? source.trim() : null, withheld);
	}

	/** Appends {@code lead} and {@code clauses} as one sentence, or nothing at all when that half of
	 *  the split is empty — an empty "Recorded for this patient: ." states nothing and costs prompt
	 *  budget to do it. */
	private static void appendClauseReading(StringBuilder sb, String lead, Collection<String> clauses) {
		if (!clauses.isEmpty()) {
			sb.append(lead).append(String.join("; ", clauses)).append(".");
		}
	}

	/** The contraindication half of a rendered record: every rule the entry publishes, and that list
	 *  split by what the patient's own chart records. One value rather than three calls because all
	 *  three are computed in ONE walk of the rules — the two halves are selections FROM the clauses,
	 *  keyed on the same collapsed rule, so recomputing either beside them is how a record comes to
	 *  mark a clause it does not carry (or carry one it cannot mark). {@code recorded} and
	 *  {@code notRecorded} partition {@code clauses} exactly, each in clause order. */
	private static final class ContraindicationSections {

		private final Collection<String> clauses;

		private final Collection<String> recorded;

		private final Collection<String> notRecorded;

		ContraindicationSections(Collection<String> clauses, Collection<String> recorded,
				Collection<String> notRecorded) {
			this.clauses = clauses;
			this.recorded = recorded;
			this.notRecorded = notRecorded;
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
	 *         into it matched, which is exactly when the ledger raises a chip for that key. Selecting
	 *         from the clauses in this walk rather than recomputing them afterwards is what keeps the
	 *         marked strings a subset of the rendered ones by construction.
	 */
	private static ContraindicationSections contraindicationSections(DrugReference ref,
			PatientClinicalContext context) {
		Map<Object, String> byRule = new LinkedHashMap<Object, String>();
		Set<Object> recordedRules = new LinkedHashSet<Object>();
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			List<String> notes = new ArrayList<String>();
			addIfPresent(notes, ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken()));
			if (notes.isEmpty()) {
				continue;
			}
			// The very key the chip ledger uses, from the very method it uses, so "ALLERGY"/"Ibuprofen"
			// and "allergy"/"ibuprofen" are one rule here exactly as they are one chip there — including
			// issue #146's exception, where an allergy rule NAMING this entry is keyed on the substance
			// because that is the fact it reports. Shared rather than restated: a copy is how the two came
			// apart when that exception was added, two such rules under two aliases of one drug becoming
			// one chip and two clauses, which is #190 item 1 re-opened one rule shape along. The chip's
			// own key additionally carries the SUBJECT and the patient's match, neither of which a record
			// about the drug has any business consulting; what has to agree is the collapse UNIT.
			Object key = DrugSafetyValidator.contraindicationFinding(ref, c);
			String clause = byRule.get(key);
			if (clause == null) {
				byRule.put(key, notes.get(0));
			} else if (!clause.contains(notes.get(0))) {
				// contains(), so a row re-authored with the identical note adds nothing — the drop issue
				// #174 site 2 could make, made only where it is provably lossless.
				byRule.put(key, clause + " — " + notes.get(0));
			}
			if (DrugSafetyValidator.recordedContraindicationKind(c, context) != null) {
				// ANY rule of the collapsed key, because that is precisely when the ledger raises a chip
				// for it: two spellings of one rule are one clause and one chip, and the patient matching
				// either is the drug being contraindicated once.
				recordedRules.add(key);
			}
		}
		List<String> recorded = new ArrayList<String>();
		List<String> notRecorded = new ArrayList<String>();
		// Walked in CLAUSE order, not in the order the matches were found: a rule authored twice can be
		// matched by its second spelling while its clause sits at the first's position, and a reading
		// that listed those out of order would be a half a reader cannot line up against the list. One
		// loop for both halves, so they partition the clauses by construction rather than by agreement.
		for (Map.Entry<Object, String> clause : byRule.entrySet()) {
			(recordedRules.contains(clause.getKey()) ? recorded : notRecorded).add(clause.getValue());
		}
		return new ContraindicationSections(byRule.values(), recorded, notRecorded);
	}

	/** @return how many characters of {@code drug_reference} record text {@code mappings} carries — the
	 *          prompt budget the reference slice spends, for the DEBUG line in {@code injectRecords}. */
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
