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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * A chart read the module could not perform is reported where a stock install can see it, and the
 * verdict survives a pass that fails after the read (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a> item 1).
 *
 * <p>The last of those belongs here rather than beside the other published-verdict cases because
 * the seam it needs — a {@code DrugReferenceService} whose source throws — is package-private.
 *
 * <p>{@code PatientClinicalContextBuilder} degrades each failed read to an empty set. That is the
 * right fail-safe for the answer path and it is not what this class is about: what it is about is
 * that the failure was logged at DEBUG, which core's shipped {@code log4j2.xml} discards by putting
 * {@code org.openmrs} at WARN — so on a default install nothing was emitted at all, and an operator
 * looking for the reason a safety net reported nothing found an empty log.
 *
 * <p><b>The LEVEL is the assertion; the one thing asserted about the TEXT is which privilege it
 * names.</b> {@link LogCapture}'s own class javadoc gives the reason for the first half and gives it
 * about issue #149, which is the shape this ticket invokes: a test asserting on wording INSTEAD of
 * the level would let a re-wording silently drop the guard, and the return value cannot pin it
 * either, because an empty token set is the correct fail-safe in BOTH the healthy-but-empty and the
 * unreadable case.
 *
 * <p>The second half is not that trade — it is an added conjunct, so the level assertion loses
 * nothing — and it is what the deliberate trace-drop rests on. {@code warnUnreadable} omits the
 * stack trace for an {@code APIAuthenticationException} BECAUSE the message already names the
 * privilege to grant — a justification {@code LogCapture.hasThrowableAt} repeats, and a claim
 * {@code README.md} makes to operators in its own words ("the failure is logged at WARN, naming
 * the core privilege to check"). So for the cause these cases call the commonest, that message is
 * the only diagnosis the module emits and an operator acts on it alone. The three calls are
 * near-identical copy-paste, and a catch paired with a neighbour's {@code PrivilegeConstant} sends
 * that operator to grant a privilege the blind arm does not use. Mutate one call's constant and
 * read the failure; under a level assertion alone it ships green.
 *
 * <p><b>Three cases and not one.</b> The three assignments are three lines in three {@code try}
 * blocks, so a case for the allergy read alone stays green when either of the others is reverted —
 * the reason {@code StandingChartAlertsToggleContextTest} states for its own pair of stamp cases.
 *
 * <p><b>The capture is scoped to the BUILDER's own logger, not to the package.</b> These are
 * POSITIVE assertions, and for a positive the package's reach is a liability rather than a
 * protection: {@code DrugReferenceValidity}, {@code DrugReferenceService}'s inert-load line,
 * {@code JsonDrugReferenceSource} and {@code DrugReferenceInjector}'s reconciliation line all log
 * under {@code …chartsearchai.reference}, and any of them firing inside the window would make these
 * pass while the line under test stayed at DEBUG.
 *
 * <p><b>What that scoping does NOT exclude is the builder's own nameless-order WARN</b>
 * ({@code PatientClinicalContextBuilder}, the active-order loop), which logs under the very logger
 * these cases capture. It does not fire for this fixture — the negative case is what shows that —
 * but it is the residue, and a fixture whose orders lost their names would make these positives
 * pass vacuously. {@link #aChartTheModuleCanReadIsNotReportedAsAFailedRead} carries its own
 * liveness witness rather than relying on the package's reach.
 *
 * <p>The read is failed the way production fails it, not by throwing from a stub: core annotates
 * each of the three service calls the builder makes with an {@code @Authorized} privilege, so a user
 * context refusing exactly one reproduces the role each of these defects is about — a site that
 * grants {@code AI Query Patient Data} without one of core's chart-read privileges. The technique is
 * {@code StandingChartAlertsToggleContextTest}'s, where it already drives all three stamps.
 */
public class ChartReadFailureLoudnessContextTest extends BaseModuleContextSensitiveTest {

	/**
	 * The logger these cases capture. Named once: {@link #aChartTheModuleCanReadIsNotReportedAsAFailedRead}
	 * is a negative over the same name, so a name that matches nothing reddens the three positives
	 * rather than leaving the negative to pass vacuously.
	 */
	private static final String BUILDER_LOGGER = PatientClinicalContextBuilder.class.getName();

	/** An answer and a question that put a drug in play, so the pass under test is one that would
	 *  screen rather than one that returns before reading anything. */
	private static final String QUESTION = "Can I give him ibuprofen?";

	private static final String ANSWER = "Ibuprofen can be given.";

	private void configure(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/**
	 * The two switches above the seam. Written explicitly because
	 * {@code ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_ENABLED} is {@code false}: left to the
	 * default, {@code validate} returns before building a context at all and every case here would be
	 * measuring the switch instead of the log.
	 */
	private void enableTheScreen() {
		configure(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "true");
	}

	/**
	 * Drives the real production entry — the arity {@code LlmInferenceService} calls, which builds the
	 * clinical context itself — with {@code privilege} refused, and answers whether the builder said
	 * anything a stock install would print.
	 *
	 * <p>It ANSWERS rather than asserts because its caller needs both directions from it: the
	 * discriminator below asks it for a healthy chart and for a failing one.
	 * {@link #assertTheFailedReadIsAudibleAndNamesItsOwnPrivilege} is the positives' entry, which
	 * asks a second question of the same pass and so cannot reduce to a boolean.
	 *
	 * @param privilege the one privilege to refuse, or {@code null} to hold every one
	 */
	private boolean builderReportedAFailureAudibly(String privilege) {
		enableTheScreen();
		Patient patient = Context.getPatientService().getPatient(7);
		assertNotNull(patient, "precondition: the standard test patient must exist");
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		return DrugReferenceTestSupport.refusingPrivilege(privilege, () -> {
			try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
				validator.validate(ANSWER, QUESTION, patient, null, null);
				return capture.hasEventAtOrAbove(Level.WARN);
			}
		});
	}

	/**
	 * One pass with {@code privilege} refused, asserting BOTH things that one line has to do: be
	 * audible on a stock install, and name the privilege gating ITS OWN read.
	 *
	 * <p>Nothing here spells a catch-to-privilege mapping the production code could be compared
	 * against: the privilege asserted IS the one refused, and the refusal is what made this read
	 * fail, so a line naming a neighbour's constant cannot satisfy it. {@code records} scopes the
	 * conjunct to the line about THIS read rather than to any WARN in the window — it is the
	 * builder's own logger and its nameless-order line lives there too — and a re-wording of that
	 * noun reddens this case rather than quietly satisfying it.
	 *
	 * <p>Both assertions read one capture over one pass, so they cannot describe different builds.
	 *
	 * @param privilege the one privilege to refuse: core's {@code @Authorized} on the service call
	 *            this read makes, which is what fails the read the way production fails it
	 * @param records how the line names the records it could not read
	 * @param whyAudible why this read in particular has to be heard
	 */
	private void assertTheFailedReadIsAudibleAndNamesItsOwnPrivilege(String privilege, String records,
			String whyAudible) {
		enableTheScreen();
		Patient patient = Context.getPatientService().getPatient(7);
		assertNotNull(patient, "precondition: the standard test patient must exist");
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		DrugReferenceTestSupport.refusingPrivilege(privilege, () -> {
			try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
				validator.validate(ANSWER, QUESTION, patient, null, null);
				assertTrue(capture.hasEventAtOrAbove(Level.WARN), whyAudible);
				assertTrue(capture.hasMessageAt(Level.WARN, records, privilege),
						"the line reporting the unreadable " + records + " must name " + privilege
								+ ", the privilege whose refusal made that read fail. The trace is "
								+ "dropped for this cause precisely because the message names the "
								+ "privilege to grant, so a copy-paste slip naming a neighbour's "
								+ "constant sends an operator to grant a privilege this arm does not "
								+ "use, with nothing else to go on (issue #247). Captured: "
								+ capture.describeAll());
			}
			return null;
		});
	}

	/**
	 * The allergy catch. Without this line the contraindication screen evaluates against "this patient
	 * has no allergies", which is indistinguishable from a patient who genuinely has none — and on a
	 * stock install there is nothing in the log to tell them apart.
	 */
	@Test
	public void aRoleThatCannotReadAllergiesIsReportedWhereAStockInstallWouldSeeIt() {
		assertTheFailedReadIsAudibleAndNamesItsOwnPrivilege(PrivilegeConstants.GET_ALLERGIES,
				"allergies",
				"a failed allergy read must be reported at WARN or above: core's shipped log4j2.xml "
						+ "puts org.openmrs at WARN, so a DEBUG line is emitted by no default install "
						+ "and the safety layer goes blind with nothing to say so (issue #247)");
	}

	/** The condition catch, which is a second line in a second try block. */
	@Test
	public void aRoleThatCannotReadConditionsIsReportedWhereAStockInstallWouldSeeIt() {
		assertTheFailedReadIsAudibleAndNamesItsOwnPrivilege(PrivilegeConstants.GET_CONDITIONS,
				"conditions",
				"a failed condition read must be reported at WARN or above, for the reason the allergy "
						+ "read beside it must be");
	}

	/**
	 * The active-order catch. It is here because the verdict this issue publishes is the WHOLE pass —
	 * {@code PatientClinicalContext.chartReadForSafety()} — so the order read decides that key exactly
	 * as the two record reads do, and leaving one of three silent would make the answer path's log
	 * channel narrower than the standing surface's for the identical failure.
	 */
	@Test
	public void aRoleThatCannotReadOrdersIsReportedWhereAStockInstallWouldSeeIt() {
		assertTheFailedReadIsAudibleAndNamesItsOwnPrivilege(PrivilegeConstants.GET_ORDERS,
				"active drug orders",
				"a failed active-order read must be reported at WARN or above: it blinds the "
						+ "interaction arms the same way, and it is one of the two stamps the "
						+ "published verdict is made of");
	}

	/**
	 * The discriminator, and it carries its own liveness witness.
	 *
	 * <p>A negative on a class logger that stays silent in the healthy case is the vacuity
	 * {@link LogCapture} exists to prevent — a capture attached to a name nothing logs under would
	 * pass it without observing anything. So this case asserts BOTH directions through the one helper:
	 * the healthy read is silent, and the same arrangement with one privilege refused is not. The
	 * second half is what proves the capture was live.
	 */
	@Test
	public void aChartTheModuleCanReadIsNotReportedAsAFailedRead() {
		assertFalse(builderReportedAFailureAudibly(null),
				"a chart the module read successfully must produce no WARN from the builder — without "
						+ "this the three cases above pass on any WARN the path happens to emit");
		assertTrue(builderReportedAFailureAudibly(PrivilegeConstants.GET_ALLERGIES),
				"the liveness witness for the negative above: the same capture, over the same logger, "
						+ "must see the line when the read really fails");
	}

	/**
	 * The recurring cause logs no stack trace; every other cause keeps one.
	 *
	 * <p>An {@code APIAuthenticationException} is a role missing a privilege the message already
	 * names, and the condition persists until an operator acts on it — so on the unrate-limited
	 * {@code /chartalerts} poll its trace would repeat forever and add nothing. A database fault
	 * underneath the same call is the opposite: there the trace IS the diagnosis.
	 *
	 * <p>The level assertions above cannot see this difference — both branches log at WARN — which
	 * is why it needs a case of its own. The non-authorization cause is driven through the real
	 * service call by a user context whose privilege check itself throws, so the exception reaches
	 * the builder's catch from inside {@code getAllergies} exactly as a failing datasource would.
	 */
	@Test
	public void theRepeatingAuthorizationCauseLogsNoTraceWhileEveryOtherCauseKeepsOne() {
		enableTheScreen();
		Patient patient = Context.getPatientService().getPatient(7);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		DrugReferenceTestSupport.refusingPrivilege(PrivilegeConstants.GET_ALLERGIES, () -> {
			try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
				validator.validate(ANSWER, QUESTION, patient, null, null);
				assertTrue(capture.hasEventAtOrAbove(Level.WARN),
						"precondition: the missing privilege must still be reported at WARN");
				assertFalse(capture.hasThrowableAt(Level.WARN),
						"a missing privilege is fully described by the message, which names the "
								+ "privilege to grant; attaching core's authorization trace to a line "
								+ "that repeats on every poll of an unrate-limited endpoint adds no "
								+ "diagnosis (issue #247). Captured: " + capture.describeAll());
			}
			return null;
		});

		// The other branch needs a cause that is NOT an authorization refusal, reaching the builder's
		// catch from inside the same real service call. A user context whose privilege check itself
		// throws does that: the exception travels out of getAllergies exactly as a fault in the store
		// underneath it would, and is not an APIAuthenticationException.
		DrugReferenceTestSupport.withUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String held) {
				if (PrivilegeConstants.GET_ALLERGIES.equals(held)) {
					throw new IllegalStateException("the store underneath is unreachable");
				}
				return true;
			}
		}, () -> {
			try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
				validator.validate(ANSWER, QUESTION, patient, null, null);
				assertTrue(capture.hasThrowableAt(Level.WARN),
						"a cause that is NOT a missing privilege keeps its stack trace — there the "
								+ "trace is the whole diagnosis, and dropping it would make this "
								+ "failure as silent as the DEBUG line issue #247 replaced. Captured: "
								+ capture.describeAll());
			}
			return null;
		});
	}

	/**
	 * A pass that fails AFTER reading the chart still states what it read.
	 *
	 * <p>{@code DrugReferenceInjector.inject} records the verdict as soon as the context exists and
	 * before {@code injectRecords} runs, and its javadoc asserts exactly this property. Nothing
	 * pinned it: moving the record below {@code injectRecords} leaves the whole build green, and
	 * silently turns {@code FALSE} into {@code null} in the one arrangement that matters — an
	 * unreadable chart on a pass that then throws, which is what the surrounding
	 * {@code catch (RuntimeException)} exists for. {@code null} there would say "nobody measured"
	 * about a read that demonstrably failed.
	 */
	@Test
	public void aPassThatThrowsAfterReadingTheChartStillStatesWhatItRead() {
		enableTheScreen();
		Patient patient = Context.getPatientService().getPatient(7);
		DrugReferenceService throwing = new DrugReferenceService();
		throwing.setSource(() -> {
			throw new IllegalStateException("boom, after the chart was read");
		});
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(throwing);
		PatientChart chart = DrugReferenceTestSupport.oneRecordChart();

		ChartReadStatus status = new ChartReadStatus();
		DrugReferenceTestSupport.refusingPrivilege(PrivilegeConstants.GET_ALLERGIES, () -> {
			assertSame(chart, injector.inject(chart, patient, QUESTION, status),
					"precondition: the injection must have failed and degraded to the chart it was "
							+ "given, or this case is not exercising the throwing path at all");
			return null;
		});

		assertEquals(Boolean.FALSE, status.stated(),
				"the read happened and failed before the rendering threw, so the pass must still "
						+ "report the chart as unread — null here would state no measurement about a "
						+ "failure the module observed");
	}
}
