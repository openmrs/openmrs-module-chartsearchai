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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.api.context.UserContext;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * {@code chartsearchai.drugSafety.warnOnContraindications} silences the standing surface (issue #280)
 * exactly as it silences the answer one — the flag is read in the one place both passes go through.
 *
 * <p>Context-sensitive because the point IS the global property: each case writes a real GP through
 * the admin service and reads it back through the real {@code standingChartAlerts} path, so neither
 * can pass on the hardcoded default. Every case in {@link StandingChartAlertsTest} runs with the GP
 * absent, which fails safe to {@code true}, so none of them can tell a surface that honours the
 * operator's switch from one that ignores it.
 *
 * <p>Both directions, because a 0-alert assertion under a disabled flag proves nothing unless the same
 * arrangement provably alerts when it is enabled — the reason
 * {@code ContraindicationToggleContextTest}, whose shape this follows, states its own pair.
 *
 * <p>The two GPs ABOVE this seam ({@code chartsearchai.drugReference.enabled} and
 * {@code chartsearchai.drugSafety.validateAnswers}) are not reachable from here: they gate the
 * {@code Patient}-taking entry, whose chart read needs a patient with a real allergy and a real active
 * order — but that entry gates on nothing but {@code reportsStandingChartAlerts()}, which
 * {@link #theScreenedStatementIsFalseWhereverASwitchThatSilencesTheSurfaceIsOff} measures here, one
 * switch at a time. {@code StandingChartAlertsTest.theStandingEntryGatesOnTheSharedTogglePredicate}
 * pins the other half: that the entry consults that predicate and spells no switch of its own.
 */
public class StandingChartAlertsToggleContextTest extends BaseModuleContextSensitiveTest {

	/** Writes the GP the way an implementation would. */
	private void configureContraindicationWarnings(String value) {
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, value);
	}

	private void configure(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/** The three switches {@code reportsStandingChartAlerts} composes, each of which silences the
	 *  surface on its own — written out here rather than read from the production predicate, which is
	 *  what this class is measuring. */
	private static final String[] GATES = {
			ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED,
			ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
			ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS };

	/**
	 * The standing surface's own shape, on the bundled curated dataset: a patient prescribed ibuprofen
	 * and allergic to it, with no response at all — so nothing but the active-order contraindication
	 * arm can raise anything.
	 */
	private List<SafetyWarning> alertsForAPrescribedAllergy() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.standingChartAlerts(DrugReferenceTestSupport.prescribedIbuprofenChart(
						DrugReferenceTestSupport.set("ibuprofen"), null))
				.getAlerts();
	}

	@Test
	public void theStandingSurfaceStandsDownWhenContraindicationWarningsAreOff() {
		configureContraindicationWarnings("false");

		assertTrue(alertsForAPrescribedAllergy().isEmpty(),
				"an operator who switched contraindication warnings off must get none from the "
						+ "standing surface either");
	}

	/**
	 * The toggle predicate the published {@code screened} verdict rests on is false wherever any one of
	 * the three switches that silence the surface is off.
	 *
	 * <p>The predicate is not the verdict: {@code StandingChartAlerts.isScreened()} narrows it with the
	 * chart reads and the pass completing. What this pins is the toggle half, which is the half no
	 * other case can reach.
	 *
	 * <p>Nothing else in the suite measures the real predicate: the omod wire test overrides it, and
	 * the cases above enter below the two switches the {@code Patient} entry reads. So without this,
	 * a predicate that answered {@code true} unconditionally would publish "this chart was screened"
	 * on an install where the screen stands down — which is the one meaning the key exists to deny,
	 * and the reason it is asserted per switch rather than on the composed default.
	 *
	 * <p>It reads the switches through the real admin service, so the assertion cannot pass on a
	 * hardcoded default. The enabled direction is asserted first, and it is what makes the three
	 * negatives discriminating rather than three ways of observing the shipped
	 * {@code drugReference.enabled=false}.
	 */
	@Test
	public void theScreenedStatementIsFalseWhereverASwitchThatSilencesTheSurfaceIsOff() {
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
		for (String gate : GATES) {
			configure(gate, "true");
		}
		assertTrue(validator.reportsStandingChartAlerts(),
				"precondition: with every switch on the surface screens, or the negatives below "
						+ "observe the shipped default rather than the switch under test");

		for (String off : GATES) {
			configure(off, "false");
			assertFalse(validator.reportsStandingChartAlerts(),
					"the gate the published verdict rests on must be false with " + off + " off");
			configure(off, "true");
		}
	}

	/**
	 * The PUBLIC entry, executed — gate, chart read and all — rather than reasoned about.
	 *
	 * <p><b>Nothing else in the suite runs that method's body.</b> Every case above enters the
	 * package-private {@code PatientClinicalContext} seam beneath it, and the omod wire test overrides
	 * the public method outright, so before this case the entry was covered only by a source scan —
	 * and a review agent showed what that is worth: keeping the literal
	 * {@code if (!reportsStandingChartAlerts()) {} while replacing the RETURN inside it left the whole
	 * suite green, on a surface that then serves standing alerts to an install where the screen stands
	 * down. A guard over TEXT cannot see what a body does; this one does.
	 *
	 * <p>It asserts the flag in BOTH directions over one real patient, because the enabled direction is
	 * what makes the disabled one discriminating rather than an observation of the shipped
	 * {@code drugReference.enabled=false}. The patient's own findings are beside the point — the
	 * standard test patient is prescribed nothing this dataset contraindicates — so this asserts
	 * {@code isScreened()} and not the list, which is exactly the half a text scan could not reach.
	 */
	@Test
	public void thePublicEntryHonoursTheGateWhenItIsActuallyRun() {
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
		Patient patient = Context.getPatientService().getPatient(7);
		assertNotNull(patient, "precondition: the standard test patient must exist");

		for (String gate : GATES) {
			configure(gate, "true");
		}
		assertTrue(validator.standingChartAlerts(patient).isScreened(),
				"precondition: with every switch on, a readable chart must come back screened — "
						+ "otherwise the disabled directions below observe something else");

		for (String off : GATES) {
			configure(off, "false");
			assertFalse(validator.standingChartAlerts(patient).isScreened(),
					"the public entry must honour " + off + " when it is actually run, not merely name "
							+ "the predicate that reads it");
			configure(off, "true");
		}
	}

	/**
	 * The BUILDER's own record of a failed order read, driven by taking the privilege away.
	 *
	 * <p>Everything else that covers this stamp uses a hand-built context
	 * ({@code DrugReferenceTestSupport.unreadableOrdersCtx}), so the production WRITE — one assignment
	 * in {@code PatientClinicalContextBuilder}'s order catch — was deletable with the whole build
	 * green. Measured by a review agent, and this case is what closes it: deleting that assignment now
	 * reddens here.
	 *
	 * <p>It fails the read the way production does rather than by throwing a stub: core annotates
	 * {@code OrderService.getActiveOrders} with {@code @Authorized(GET_ORDERS)}, so a user context that
	 * refuses that one privilege and grants the rest reproduces the exact role this defect is about — a
	 * site that grants {@code AI Query Patient Data} without core's {@code Get Orders}. The user context
	 * is restored whatever happens; this class shares a JVM with the rest of the suite.
	 */
	@Test
	public void aRoleThatCannotReadOrdersGetsAChartTheBuilderMarksUnread() {
		for (String gate : GATES) {
			configure(gate, "true");
		}
		Patient patient = Context.getPatientService().getPatient(7);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
		assertTrue(validator.standingChartAlerts(patient).isScreened(),
				"precondition: with every privilege held this chart screens, or the refusal below is "
						+ "observing something other than the missing privilege");

		UserContext prior = Context.getUserContext();
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return !PrivilegeConstants.GET_ORDERS.equals(privilege);
			}
		});
		try {
			assertFalse(validator.standingChartAlerts(patient).isScreened(),
					"a role that cannot read this patient's orders must get a chart the builder marks "
							+ "unread, not one reported clean");
		}
		finally {
			Context.setUserContext(prior);
		}
	}

	@Test
	public void theSameChartAlertsWhenContraindicationWarningsAreOn() {
		// The discriminator for the case above: written explicitly rather than left to the default, so
		// this reads the same GP through the same path and the pair of cases isolates the flag itself.
		configureContraindicationWarnings("true");

		assertEquals(1, alertsForAPrescribedAllergy().size(),
				"with the switch on, the prescribed allergy must still be a standing alert");
	}
}
