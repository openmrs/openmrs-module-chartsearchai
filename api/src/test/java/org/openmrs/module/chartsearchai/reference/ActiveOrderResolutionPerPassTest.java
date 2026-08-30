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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The patient's active orders are resolved ONCE per injection pass, not once by the injector and
 * again by the {@code validate} it calls (issue #255).
 *
 * <p>{@code DrugReferenceInjector.injectRecords} resolves
 * {@code DrugReferenceService.findForActiveOrders} for its own promotion predicate, for
 * {@code matchingEntries}' candidate set (issue #151) and for the reference names it attaches — and
 * then calls {@code preAnswerFindings}, whose {@code validate} resolved the very same list again from
 * the very same context. A second derivation of a value the pass already holds, which is the shape
 * {@code CLAUDE.md} prescribes removing rather than caching: "wherever a caller already holds the
 * resolved list, pass it down rather than resolving again".
 *
 * <p><b>What these cases count, and why that is the honest unit.</b> A timing assertion would be
 * flaky and machine-shaped. The repeat's cost is a whole resolution — walks of the loaded dataset for
 * every order name the chart carries — and the number of them is a deterministic integer, independent
 * of the dataset's size and of how fast the box is. So the count is taken through a subclass that
 * increments and delegates to {@code super.findForActiveOrders}: the real service, the real parser,
 * the real injector and the real validator behind it. An instrument, not a mock — nothing of the
 * pipeline is re-expressed.
 *
 * <p><b>The count alone cannot see a wrong list</b>, which is why
 * {@link #theInjectedFindingsAreThoseTheSelfResolvingPassProduces} is here and drives the composed
 * entry point rather than the seam. An injector threading a plausible-but-wrong list — the keys of
 * {@code matchingEntries}, say, or any other list it already holds — still resolves exactly once for
 * itself, so it satisfies the count while changing what the prompt is told. That case is what fails
 * on it.
 */
public class ActiveOrderResolutionPerPassTest {

	/** The real service over the pinned excerpt, counting the resolutions of the patient's orders. */
	private static final class ResolutionCountingService extends DrugReferenceService {

		private int resolutions;

		@Override
		public List<DrugReference> findForActiveOrders(PatientClinicalContext context) {
			resolutions++;
			return super.findForActiveOrders(context);
		}
	}

	/** Active orders the concept dictionary mapped to no ATC code — the majority shape on the 3.7.1
	 *  reference dictionary, and the one whose entry is resolved by NAME. */
	private static final List<String> ORDER_NAMES = Arrays.asList("Warfarin", "Amiodarone",
			"Ciprofloxacin", "Digoxin");

	/** A question naming a substance the excerpt classifies, so the pre-answer pass has findings to
	 *  inject and the vacuity guards below are met. */
	private static final String QUESTION = "Can I give her simvastatin?";

	/**
	 * The canonical screening question (issue #113) — and the one the cases below that must observe a
	 * WRONG list are driven with, which is a measured choice rather than variety.
	 *
	 * <p>{@link #QUESTION} cannot observe one. Its findings come from the drug-in-play interaction
	 * arm, which reads the context's reference names through {@code hasActiveDrug} — and the injector
	 * has already attached those before it calls in, so handing {@code validate} an EMPTY list leaves
	 * every one of them standing. Measured by mutating the production call to thread
	 * {@code Collections.emptyList()}: all three cases here stayed green, the build compiled and the
	 * cases ran. A screening question is different in exactly the way this needs — it names no drug,
	 * so its subjects ARE the resolved active orders, straight off the threaded list
	 * ({@code addActiveOrderPairInteractions}) — and under the same mutation it reddens.
	 */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	private static ResolutionCountingService service() {
		return DrugReferenceTestSupport.withEntriesAndGroups(new ResolutionCountingService(),
				DrugReferenceTestSupport.ddinterEntries());
	}

	private static PatientClinicalContext chartWithOrders() {
		List<PatientClinicalContext.ActiveDrugOrder> orders =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		Set<String> names = new LinkedHashSet<String>();
		for (String name : ORDER_NAMES) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-" + name, name, name));
			names.add(name.toLowerCase());
		}
		return DrugReferenceTestSupport.ctx(60, 70.0, names, null, null, null, orders);
	}

	/** The {@code safety_finding} records of {@code chart}, in chart order, as they were rendered. */
	private static List<String> injectedFindings(PatientChart chart) {
		List<String> rendered = new ArrayList<String>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				rendered.add(mapping.getText());
			}
		}
		return rendered;
	}

	/** {@code findings} as the injector renders them — its own renderer, not a second expression. */
	private static List<String> rendered(List<SafetyWarning> findings) {
		List<String> rendered = new ArrayList<String>();
		for (SafetyWarning finding : findings) {
			rendered.add(DrugReferenceInjector.renderFinding(finding));
		}
		return rendered;
	}

	/**
	 * One injection pass resolves the patient's active orders once.
	 *
	 * <p>The vacuity guard is the finding records: without them {@code preAnswerFindings} short-
	 * circuited — no validator wired, or {@code drugSafety.validateAnswers} off — and the pass never
	 * reached the second resolution at all, so a count of one would be satisfied by an arrangement
	 * that cannot observe the defect. Measured while writing this case: with an injector carrying no
	 * validator the count is already one; it is the validator behind it that made it two.
	 */
	@Test
	public void theInjectorResolvesTheActiveOrdersOncePerPass() {
		ResolutionCountingService service = service();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injectorWithSafety(service);

		service.resolutions = 0;
		PatientChart injected = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				chartWithOrders(), QUESTION);

		assertFalse(injectedFindings(injected).isEmpty(), "the arrangement must reach the pre-answer "
				+ "findings pass, or the count below is satisfied by an injection that never calls "
				+ "validate at all and this case asserts nothing");
		assertEquals(1, service.resolutions, "one injection pass resolved the patient's active orders "
				+ service.resolutions + " times (issue #255). The injector already holds that list — it "
				+ "resolves it for its own promotion predicate — so whatever now needs it must be "
				+ "handed it, not resolve the chart again: CLAUDE.md's findForActiveOrders bullet, "
				+ "\"wherever a caller already holds the resolved list, pass it down rather than "
				+ "resolving again\".");
	}

	/**
	 * The findings the pass injects are the ones it would have produced when {@code validate} resolved
	 * the orders for itself — so the list the injector threads down is that resolution and not merely
	 * some list it had to hand.
	 *
	 * <p>This is the case a count cannot be: an injector threading the wrong list resolves exactly
	 * once for itself and satisfies {@link #theInjectorResolvesTheActiveOrdersOncePerPass} while
	 * changing which partners the class arm reasons over, which is what reaches the model as citable
	 * {@code safety_finding} text.
	 *
	 * <p>Both sides are production's own: the left is what the real {@code injectRecords} put in the
	 * chart, the right is the 2-arg {@code preAnswerFindings} — the seam that resolves for itself —
	 * rendered through {@code DrugReferenceInjector.renderFinding}, the renderer the injection itself
	 * uses.
	 */
	@Test
	public void theInjectedFindingsAreThoseTheSelfResolvingPassProduces() {
		ResolutionCountingService service = service();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injectorWithSafety(service);
		PatientClinicalContext context = chartWithOrders();

		PatientChart injected = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				context, SCREENING_QUESTION);
		List<String> selfResolved = rendered(injector.preAnswerFindings(context, SCREENING_QUESTION));

		assertFalse(selfResolved.isEmpty(), "the arrangement must produce pre-answer findings, or the "
				+ "comparison below is between two empty lists");
		assertEquals(selfResolved, injectedFindings(injected), "the findings the pass injected are not "
				+ "the ones it produces when validate resolves the patient's orders for itself, so the "
				+ "list threaded down (issue #255) is not that resolution. A wrong list here changes "
				+ "which co-medications the class arm reasons about, and reaches the model as citable "
				+ "safety_finding text.");
	}

	/**
	 * Asking for the resolution of the ENRICHED context and of the RAW one it was built from answers
	 * alike — the premise the transport rests on, and nothing more than that.
	 *
	 * <p>Production is asymmetric and that is why the premise needs stating: {@code injectRecords}
	 * resolves the entries from the raw context and then hands the ENRICHED one to
	 * {@code preAnswerFindings}, so before issue #255 {@code validate} resolved from the enriched
	 * context and after it consumes a list resolved from the raw one. The two cannot differ —
	 * {@code withActiveDrugReferenceNames} copies the active drug names, the ATC codes and the orders
	 * through and writes only the reference names, none of which {@code findForActiveOrders} reads —
	 * but nothing said so, and this is what would redden if a later widening made the resolution read
	 * the names it attaches. That is the "trap" {@code DrugSafetyValidator} recorded and did not fix.
	 *
	 * <p>It pins {@code validate}'s INPUT, not the injector's threading; the case above is what pins
	 * that. It passes on both sides of the change.
	 */
	@Test
	public void theResolutionIsTheSameWhicheverContextItIsAskedOf() {
		ResolutionCountingService service = service();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injectorWithSafety(service);
		PatientClinicalContext raw = chartWithOrders();
		PatientClinicalContext enriched = service.withReferenceNames(raw,
				service.findForActiveOrders(raw));

		List<SafetyWarning> resolvingForItself = injector.preAnswerFindings(enriched, SCREENING_QUESTION);
		List<SafetyWarning> handedTheRawResolution = injector.preAnswerFindings(enriched,
				SCREENING_QUESTION, service.findForActiveOrders(raw));

		assertFalse(resolvingForItself.isEmpty(), "the arrangement must produce pre-answer findings, "
				+ "or the comparison below is between two empty lists");
		assertEquals(rendered(resolvingForItself), rendered(handedTheRawResolution),
			"a validate handed the orders resolved from the RAW context found something different from "
					+ "one that resolved the ENRICHED context for itself. The transport in issue #255 "
					+ "rests on those being one answer: withReferenceNames writes only the reference "
					+ "names, which findForActiveOrders does not read.");
	}
}
