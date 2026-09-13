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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Drug;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * A {@code Drug} the module cannot read costs that order its coded NAME, and not the chart's whole
 * active-order read (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/413">#413</a>).
 *
 * <p><b>What was wrong.</b> {@code DrugOrder.drug} is a LAZY association —
 * {@link PatientClinicalContextBuilder#drug} carries the mapping that makes it one, and is the one
 * home for that fact. {@code PatientClinicalContextBuilder} dereferenced it in three places, all
 * inside the active-order {@code for} and all before the order is added to the per-order list,
 * while the only {@code catch} sits OUTSIDE that loop. So one unreadable drug cost the order it was
 * on and every order AFTER it — an empty {@code getActiveDrugOrders()} only where the failing order
 * was the first, which is patient 7 here and was the issue's own reproduction — with
 * {@code activeDrugOrdersRead()} false and the chart stamped unread either way, so
 * {@code GET /chartsearchai/chartalerts} answered {@code screened: false} for a patient whose chart
 * holds findings.
 *
 * <p><b>How the throw is arranged, and why this way.</b> A context-sensitive test holds an open
 * Hibernate session by construction, so the {@code LazyInitializationException} the issue reproduces
 * ("no Session") cannot be staged here. What can is the OTHER way initialising that proxy throws and
 * a real database can be in: the order pointing at a {@code drug} row that is not there, which
 * Hibernate's default {@code not-found="exception"} answers with an
 * {@code org.hibernate.ObjectNotFoundException}. Both are {@code RuntimeException}s raised by
 * initialising this one association, which is what the three call sites above each did.
 *
 * <p><b>Since issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/421">#421</a> there is one
 * such site rather than three</b>, and the difference is what this class's structural case pins:
 * {@code PatientClinicalContextBuilder.drug} reads the name, concept and dose form inside its own
 * {@code try} and hands back those VALUES, so the loop holds no {@code Drug} to dereference. The
 * behavioural cases below are unchanged by that and still drive the real builder; what changed is
 * that the guard beneath them asks a question the compiler can answer rather than one a text scan
 * has to spell.
 *
 * <p><b>The fixture COMMITS, and that is why it restores by hand.</b> The repointing is raw SQL —
 * the technique {@code NonCodedDrugOrderNameTest} already arranges order 111 with — around core's
 * {@code turnOffDBConstraints}, because the test database is H2 built by
 * {@code hibernate.hbm2ddl.auto=create-drop} and really does carry the foreign key: measured, the
 * same UPDATE without the toggle is refused as {@code Referential integrity constraint violation}.
 * What that buys has a price the sibling classes do not pay — {@code SET REFERENTIAL_INTEGRITY}
 * COMMITS the open transaction, so this class's writes outlive the rollback that returns theirs,
 * and surefire is configured with neither {@code forkCount} nor {@code reuseForks}, so one reused
 * JVM runs every class against the one H2 database. Measured: with the restore below removed and
 * the suite run {@code -Dsurefire.runOrder=reversealphabetical}, this class reddens several sibling
 * classes that drive the same rows, {@code NonCodedDrugOrderNameTest} among them. So {@link #restoreTheFixture} puts back every column and
 * property a case here writes, commits them through {@link #commitEverythingWrittenSoFar}, and then
 * reads every one of them back — a restore that silently did nothing is the failure being guarded
 * against, and asserting one of them would have hidden the rest. What the read-back cannot see is
 * stated on {@link #restored}.
 *
 * <p>A case here therefore assumes nothing about the stock state of the rows it uses, since another
 * class in this package mutates the same order.
 */
public class UnreadableOrderDrugTest extends BaseModuleContextSensitiveTest {

	/** Patient 7's single active drug order, carrying {@code drug_inventory_id=3} (drug "ASPIRIN")
	 *  and {@code route=22} — the same fixture {@code NonCodedDrugOrderNameTest} drives. */
	private static final int ORDER = 111;

	/** Concept 88, the concept order 111 was written against. */
	private static final int ORDERED_CONCEPT = 88;

	/** Concept 792, behind patient 2's orders 222 and 3. Blanking it is how
	 *  {@link #anOrderTheModuleCannotNameAtAllLeavesTheOrdersBesideItOnTheList} drops one order of
	 *  several; order 3 keeps its own readable drug, so only 222 falls. */
	private static final int SECOND_PATIENT_CONCEPT = 792;

	/** Every row this class writes to, captured in {@link #setUp} and put back in
	 *  {@link #restoreTheFixture}. */
	private static final int[] ORDERS = { 111, 222 };

	private static final int[] CONCEPTS = { ORDERED_CONCEPT, SECOND_PATIENT_CONCEPT };

	/** Drug "ASPIRIN", which order 111 points at in the standard dataset.
	 *  {@link #aDrugWithNoNameOfItsOwnIsStillADrugTheModuleRead} writes its {@code name} and
	 *  {@code dosage_form}, so both are captured and put back with everything else. */
	private static final int STOCK_DRUG = 3;

	/** One of patient 2's several active drug orders, and the one
	 *  {@link #noOtherOrderLosesItsPlaceOnTheListWhenOneOrdersDrugCannotBeRead} breaks. It carries
	 *  {@code drug_inventory_id=2} ("Triomune-30"). */
	private static final int SECOND_PATIENT_ORDER = 222;

	/** How {@code addRaw} collects drug 2's name, and so the token order 222 LOSES when its drug
	 *  cannot be read. Used to identify the order this fixture broke rather than trusting its
	 *  position on the list. */
	private static final String SECOND_PATIENT_DRUG_TOKEN = "triomune-30";

	/** A {@code drug_id} no row carries, asserted rather than assumed by
	 *  {@link #pointTheOrderAtADrugRowThatIsGone}. */
	private static final int MISSING_DRUG = 9999;

	/** Renamed onto the concept so the drug's name and the concept's are DISTINGUISHABLE: both are
	 *  "ASPIRIN" in the stock dataset, and under that pairing a case cannot see which of the two
	 *  survived. */
	private static final String CONCEPT_NAME = "Salicylate concept";

	/** What {@code addRaw} collects {@link #CONCEPT_NAME} as. */
	private static final String CONCEPT_TOKEN = "salicylate concept";

	/** What the coded drug's own name is collected as — the one token an unreadable drug costs. */
	private static final String DRUG_TOKEN = "aspirin";

	private static final String RELATIVE_SOURCE =
			"src/main/java/org/openmrs/module/chartsearchai/reference/PatientClinicalContextBuilder.java";

	/** The logger the two WARN assertions capture — the builder's own, so a line from a neighbouring
	 *  class in the same package cannot satisfy them. */
	private static final String BUILDER_LOGGER = PatientClinicalContextBuilder.class.getName();

	/** The standing surface's own logger — the operator-facing line, which is a different channel
	 *  from the builder's and says what the SURFACE does rather than which read failed. */
	private static final String VALIDATOR_LOGGER = DrugSafetyValidator.class.getName();

	/** The remedy the standing line offers where a read is gated on a core privilege. */
	private static final String PRIVILEGE_REMEDY = "Check that the querying role holds";

	/** What that same line APPENDS where an order was also left off the list — the second cause, which
	 *  the privilege remedy above cannot reach (issue #421). */
	private static final String COMBINED_CAUSE = "An active order was also left off the list";

	/** A drug the curated seed carries, used only to make the enrichment copy happen. */
	private static final String ENRICHING_DRUG = "ibuprofen";

	/** A phrase from the accessor's own WARN, and one from the drop's. Both lines name the order, so
	 *  the uuid alone would let EITHER satisfy either assertion — measured: lowering the drop's line
	 *  to {@code log.debug} left a uuid-only assertion green on the accessor's line. */
	private static final String DEGRADED_WARN = "Could not read the coded drug";

	/** The accessor names its cause rather than logging a stack trace, so the exception type is part
	 *  of what an operator gets — without this needle, dropping {@code e.toString()} reddens
	 *  nothing. */
	private static final String DEGRADED_WARN_CAUSE = "ObjectNotFoundException";

	private static final String DROPPED_WARN = "no readable name and no ATC code";

	/** The breadth of the accessor's catch, asserted as text because the exception the issue reports
	 *  — a {@code LazyInitializationException} from a detached proxy — cannot be staged in a test
	 *  that holds an open session. */
	private static final String BROAD_CATCH = "catch (RuntimeException";

	/** The one method that may reach the association. */
	private static final String GUARDED_ACCESSOR =
			"private static CodedDrug drug(DrugOrder drugOrder) {";

	/** The two global properties {@link #setUp} turns on. They are committed by the first constraint
	 *  toggle like everything else here, so their prior values are captured and put back. A property
	 *  this class found ABSENT is put back as the empty string rather than purged, which every
	 *  consumer reads the same way it reads absent, {@code ChartSearchAiUtils}'s boolean reader
	 *  mapping both to the property's DEFAULT — which for one of these two is {@code true}, so the
	 *  point is the equivalence and not the value. {@link #asFound} is what holds those two
	 *  spellings together, on both sides of the assertion. */
	private static final String[] PROPERTIES = { ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED,
			ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS };

	private final String[] priorPropertyValues = new String[PROPERTIES.length];

	private final String[] priorDrugIds = new String[ORDERS.length];

	private final String[] priorNonCoded = new String[ORDERS.length];

	private final String[] priorConceptNames = new String[CONCEPTS.length];

	private String priorDrugName;

	private String priorDosageForm;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		for (int i = 0; i < PROPERTIES.length; i++) {
			priorPropertyValues[i] = Context.getAdministrationService().getGlobalProperty(PROPERTIES[i]);
			Context.getAdministrationService().setGlobalProperty(PROPERTIES[i], "true");
		}
		for (int i = 0; i < ORDERS.length; i++) {
			priorDrugIds[i] = drugIdOf(ORDERS[i]);
			priorNonCoded[i] = nonCodedOf(ORDERS[i]);
		}
		for (int i = 0; i < CONCEPTS.length; i++) {
			priorConceptNames[i] = nameOf(CONCEPTS[i]);
		}
		priorDrugName = restored("select name from drug where drug_id = " + STOCK_DRUG);
		priorDosageForm = restored("select dosage_form from drug where drug_id = " + STOCK_DRUG);
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * Points {@code orderId} at {@code drugId} and clears the free text beside it, so the only names
	 * the order can carry are the ones a case arranges.
	 *
	 * <p>The constraint toggle is core's own and is turned back on immediately, so only this one
	 * UPDATE escapes the foreign key. It has to be that method rather than the statement inline:
	 * measured, {@code executeSQL("SET REFERENTIAL_INTEGRITY FALSE", false)} is refused by the DAO
	 * with "Method is only allowed for a query".
	 */
	private void pointTheOrderAt(int orderId, int drugId) throws Exception {
		Connection connection = getConnection();
		turnOffDBConstraints(connection);
		try {
			Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = "
					+ drugId + ", drug_non_coded = null where order_id = " + orderId, false);
			Context.flushSession();
			Context.clearSession();
		}
		finally {
			turnOnDBConstraints(connection);
		}
	}

	/** Points {@code orderId} at a {@code drug} row that does not exist, so every read of the
	 *  association throws. */
	private void pointTheOrderAtADrugRowThatIsGone(int orderId) throws Exception {
		List<List<Object>> existing = Context.getAdministrationService()
				.executeSQL("select drug_id from drug where drug_id = " + MISSING_DRUG, true);
		assertTrue(existing.isEmpty(), "precondition: drug_id " + MISSING_DRUG + " must not exist, or "
				+ "the association would resolve and this fixture would arrange nothing; was: " + existing);
		pointTheOrderAt(orderId, MISSING_DRUG);
	}

	/** Absent and empty are one value here — see {@link #PROPERTIES}. Applied to BOTH sides of the
	 *  restore assertion, so the check cannot fail on a spelling it caused itself. */
	private static String asFound(String value) {
		return value == null ? "" : value;
	}

	/**
	 * Commits everything written before it. {@code SET REFERENTIAL_INTEGRITY} commits the open
	 * transaction, so this is a toggle pair with no statement between — named, because the restore
	 * used to depend on a repoint happening to be last, and a reordering would have put the writes
	 * back on the rollback path silently.
	 */
	private void commitEverythingWrittenSoFar() throws Exception {
		Connection connection = getConnection();
		turnOffDBConstraints(connection);
		turnOnDBConstraints(connection);
	}

	/**
	 * Reads one value back out of the table it was restored into.
	 *
	 * <p><b>What it proves and what it does not.</b> It catches a restore that did not happen — a
	 * wrong constant, an UPDATE matching no row, a step dropped from {@link #restoreTheFixture}. It
	 * does NOT prove the restore was COMMITTED, and an earlier version of this method claimed it did:
	 * reading through a second {@code getConnection()} was measured to see this class's own pending
	 * writes, so moving a restore after {@link #commitEverythingWrittenSoFar} left this whole class
	 * green. The commit half rests on that named step and on the whole suite passing under
	 * {@code -Dsurefire.runOrder=reversealphabetical}, which is where the leak showed itself.
	 */
	private static String restored(String sql) {
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(sql, true);
		if (rows.isEmpty() || rows.get(0).get(0) == null) {
			return null;
		}
		return String.valueOf(rows.get(0).get(0));
	}

	/**
	 * Puts back every column and property a case here writes, and then proves it did.
	 *
	 * <p>Everything is CAPTURED in {@link #setUp} rather than restored to a constant, columns
	 * included. This class commits, so a constant would silently revert a sibling that had committed
	 * its own change to these rows — the hazard the capture exists to avoid, and there is no reason
	 * for the columns to be the exception.
	 *
	 * <p>The writes go first and {@link #commitEverythingWrittenSoFar} last, because the constraint
	 * toggle is what commits: a restore left pending is rolled back with the test and leaks. The
	 * read-back that follows proves the values were written; what it cannot prove is stated on
	 * {@link #restored}.
	 */
	@AfterEach
	public void restoreTheFixture() throws Exception {
		for (int i = 0; i < CONCEPTS.length; i++) {
			DrugReferenceTestSupport.nameTheConcept(CONCEPTS[i], priorConceptNames[i]);
		}
		for (int i = 0; i < PROPERTIES.length; i++) {
			Context.getAdministrationService().setGlobalProperty(PROPERTIES[i],
					asFound(priorPropertyValues[i]));
		}
		for (int i = 0; i < ORDERS.length; i++) {
			restoreTheOrder(ORDERS[i], priorDrugIds[i], priorNonCoded[i]);
		}
		describeTheDrug(priorDrugName, priorDosageForm);
		commitEverythingWrittenSoFar();

		for (int i = 0; i < ORDERS.length; i++) {
			assertEquals(priorDrugIds[i], drugIdOf(ORDERS[i]),
					"order " + ORDERS[i] + " must be left pointing at the drug this class found it on");
			assertEquals(asFound(priorNonCoded[i]), asFound(nonCodedOf(ORDERS[i])),
					"and carrying the free text this class found on it — nulled by every arrangement here,"
							+ " so a sibling that had committed some would lose it");
		}
		assertEquals(asFound(priorDrugName),
				asFound(restored("select name from drug where drug_id = " + STOCK_DRUG)),
				"drug " + STOCK_DRUG + " must be left with the name this class found on it");
		assertEquals(asFound(priorDosageForm),
				asFound(restored("select dosage_form from drug where drug_id = " + STOCK_DRUG)),
				"and with the dose form this class found on it");
		for (int i = 0; i < CONCEPTS.length; i++) {
			assertEquals(priorConceptNames[i], nameOf(CONCEPTS[i]),
					"concept " + CONCEPTS[i] + " must be left with the name this class found — leaving one"
							+ " renamed or blank reddens sibling classes under a reversed run order, the blank"
							+ " one as an NPE rather than as a legible failure");
		}
		for (int i = 0; i < PROPERTIES.length; i++) {
			assertEquals(asFound(priorPropertyValues[i]),
					asFound(restored("select property_value from global_property where property = '"
							+ PROPERTIES[i] + "'")),
					PROPERTIES[i] + " must be left as this class found it — the constraint toggle commits"
							+ " it, so an unrestored value outlives the JVM's next test class");
		}
	}

	/** Puts one order's two naming columns back to what was captured, through the same constraint
	 *  toggle every arrangement here uses. */
	private void restoreTheOrder(int orderId, String drugId, String nonCoded) throws Exception {
		Connection connection = getConnection();
		turnOffDBConstraints(connection);
		try {
			Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = "
					+ (drugId == null ? "null" : drugId) + ", drug_non_coded = "
					+ (nonCoded == null ? "null" : "'" + nonCoded.replace("'", "''") + "'")
					+ " where order_id = " + orderId, false);
			Context.flushSession();
			Context.clearSession();
		}
		finally {
			turnOnDBConstraints(connection);
		}
	}

	/** Writes drug {@link #STOCK_DRUG}'s own name and dose form. Both are captured in {@link #setUp}
	 *  and put back, this class being the only one in the repo that commits. */
	private void describeTheDrug(String name, String dosageForm) throws Exception {
		Connection connection = getConnection();
		turnOffDBConstraints(connection);
		try {
			Context.getAdministrationService().executeSQL("update drug set name = "
					+ (name == null ? "null" : "'" + name.replace("'", "''") + "'") + ", dosage_form = "
					+ (dosageForm == null ? "null" : dosageForm) + " where drug_id = " + STOCK_DRUG,
					false);
			Context.flushSession();
			Context.clearSession();
		}
		finally {
			turnOnDBConstraints(connection);
		}
	}

	private static String drugIdOf(int orderId) {
		return restored("select drug_inventory_id from drug_order where order_id = " + orderId);
	}

	private static String nonCodedOf(int orderId) {
		return restored("select drug_non_coded from drug_order where order_id = " + orderId);
	}

	private static String nameOf(int conceptId) {
		return restored("select name from concept_name where concept_id = " + conceptId
				+ " and concept_name_type = 'FULLY_SPECIFIED'");
	}

	private static Set<String> orderUuids(List<PatientClinicalContext.ActiveDrugOrder> orders) {
		Set<String> uuids = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			uuids.add(order.getUuid());
		}
		return uuids;
	}

	private static PatientClinicalContext.ActiveDrugOrder byUuid(
			List<PatientClinicalContext.ActiveDrugOrder> orders, String uuid) {
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			if (uuid.equals(order.getUuid())) {
				return order;
			}
		}
		throw new AssertionError("order " + uuid + " is not on the list; was: " + orders);
	}

	/**
	 * The blast radius on ONE order, measured against what the SAME order yields when its drug IS
	 * readable, so the case says what is lost rather than only that something survived.
	 *
	 * <p>The administration terms are compared whole. For this fixture they are the ROUTE's names —
	 * drug 3 carries no dosage form — so the equality pins that guarding the dosage-form read did not
	 * cost the route beside it, and does not claim to pin the dosage form itself.
	 */
	@Test
	public void oneUnreadableDrugCostsThatOrderItsCodedNameAndNothingElse() throws Exception {
		DrugReferenceTestSupport.nameTheConcept(ORDERED_CONCEPT, CONCEPT_NAME);
		restoreTheOrder(ORDER, priorDrugIds[0], null);
		PatientClinicalContext.ActiveDrugOrder readable = DrugReferenceTestSupport
				.onlyActiveOrder(PatientClinicalContextBuilder.build(patient));
		assertTrue(readable.getNames().contains(DRUG_TOKEN),
				"precondition: with a readable drug the order must carry the drug's own name, or the"
						+ " assertion below that it is LOST measures nothing; was: " + readable.getNames());

		String orderUuid = restored("select uuid from orders where order_id = " + ORDER);
		pointTheOrderAtADrugRowThatIsGone(ORDER);
		PatientClinicalContext context;
		try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
			context = PatientClinicalContextBuilder.build(patient);
			assertTrue(capture.hasMessageAt(Level.WARN, orderUuid, DEGRADED_WARN, DEGRADED_WARN_CAUSE),
					"the degradation must be reported where a stock install can see it — core's shipped"
							+ " log4j2.xml puts org.openmrs at WARN and discards DEBUG, which is issue"
							+ " #247's own finding, and this line is the only trace that a medication list"
							+ " quietly lost a drug; was: " + capture.describeAll());
		}

		assertTrue(context.activeDrugOrdersRead(),
				"the active-order read did happen — one order whose drug cannot be read is not a chart"
						+ " whose orders could not be read, and stamping it so reports this patient's"
						+ " medications as unknown (issue #413)");
		PatientClinicalContext.ActiveDrugOrder order = DrugReferenceTestSupport.onlyActiveOrder(context);
		assertFalse(order.getNames().contains(DRUG_TOKEN),
				"the coded drug's name is what an unreadable drug costs, was: " + order.getNames());
		assertTrue(order.getNames().contains(CONCEPT_TOKEN),
				"and the concept's name, which is read off the ORDER, is not lost with it, was: "
						+ order.getNames());
		assertEquals(CONCEPT_NAME, order.getDisplay(),
				"and the DISPLAY a chip would print falls back to that name rather than to the code-only"
						+ " rung's [ATC …] stand-in (#290); hasKnownName() is not asserted beside this"
						+ " because it cannot fail independently of the names above, was: "
						+ order.getDisplay());
		assertFalse(readable.getAdministrationTerms().isEmpty(),
				"precondition: the readable order must carry administration terms, or the equality below"
						+ " holds between two empty sets and says nothing");
		assertEquals(readable.getAdministrationTerms(), order.getAdministrationTerms(),
				"and the route terms, read off the order rather than off the drug, survive too; was: "
						+ order.getAdministrationTerms());
	}

	/**
	 * The other half of the issue's headline, and the half a one-order patient cannot show: the
	 * orders BESIDE the broken one keep their place. Patient 7 has a single active drug order, so
	 * {@link #oneUnreadableDrugCostsThatOrderItsCodedNameAndNothingElse} can only pin the stamp and
	 * that one order's survival; "every later order dropped" needs a list.
	 *
	 * <p>Asserted as the whole uuid SET rather than a count, so an order silently exchanged for
	 * another fails it. The set is taken from a readable build first for the same reason the case
	 * above takes one — a comparison against a number this fixture wrote down would go stale with the
	 * dataset.
	 */
	@Test
	public void noOtherOrderLosesItsPlaceOnTheListWhenOneOrdersDrugCannotBeRead() throws Exception {
		Patient secondPatient = Context.getPatientService().getPatient(2);
		List<PatientClinicalContext.ActiveDrugOrder> readable =
				PatientClinicalContextBuilder.build(secondPatient).getActiveDrugOrders();
		assertTrue(readable.size() > 1,
				"precondition: this case needs a patient with SEVERAL active drug orders, or it measures"
						+ " the same thing as the case above; was: " + readable);
		// By uuid off the row, never by name: several of this patient's orders point at drug 2, so
		// "the order named triomune-30" is not necessarily the one this case repoints.
		String broken = restored("select uuid from orders where order_id = " + SECOND_PATIENT_ORDER);
		assertTrue(orderUuids(readable).contains(broken),
				"precondition: order " + SECOND_PATIENT_ORDER + " must be one of the ACTIVE orders this"
						+ " case is about, or it repoints a row the builder never reads; was: "
						+ orderUuids(readable));
		assertTrue(byUuid(readable, broken).getNames().contains(SECOND_PATIENT_DRUG_TOKEN),
				"precondition: and it must carry its coded drug's name while that drug is readable, or"
						+ " the assertion below that it is LOST measures nothing; was: "
						+ byUuid(readable, broken).getNames());

		pointTheOrderAtADrugRowThatIsGone(SECOND_PATIENT_ORDER);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(secondPatient);

		assertTrue(context.activeDrugOrdersRead(), "the active-order read still happened (issue #413)");
		assertEquals(orderUuids(readable), orderUuids(context.getActiveDrugOrders()),
				"every order keeps its place on the list — before this fix an unreadable drug took the"
						+ " order it was on and every order after it; was: "
						+ orderUuids(context.getActiveDrugOrders()));
		PatientClinicalContext.ActiveDrugOrder damaged = byUuid(context.getActiveDrugOrders(), broken);
		assertFalse(damaged.getNames().contains(SECOND_PATIENT_DRUG_TOKEN),
				"and the order this case BROKE is the one that lost its coded name — without this the"
						+ " case passes over an order it never made unreadable; was: "
						+ damaged.getNames());
		assertFalse(damaged.getNames().isEmpty(),
				"while still being named by its own concept, was: " + damaged.getNames());
	}

	/**
	 * The one case where #413's degradation is NOT enough on its own, and the stamp has to say so.
	 *
	 * <p>An order left with no name at all and no ATC code reaches neither rung and is dropped — the
	 * skip is older than this issue, but an unreadable {@code Drug} is a new way into it. Were the
	 * pass still stamped read, a patient with an active prescription would get
	 * {@code screened: true} beside an empty {@code alerts}, which {@code README.md} tells a client
	 * to read as a measurement of none. That is the confusion issue #247 exists to remove, so the
	 * {@code else} in {@code build} reports the pass unread instead.
	 *
	 * <p>Blanking the concept's fully specified name is what leaves the order nameless: the drug's
	 * name is gone with the drug, the order carries no free text, and concept 88 carries no ATC
	 * mapping in the standard dataset.
	 */
	@Test
	public void anOrderTheModuleCannotNameAtAllIsNotCertifiedAsAScreenedEmptyChart() throws Exception {
		DrugReferenceTestSupport.nameTheConcept(ORDERED_CONCEPT, "");
		pointTheOrderAtADrugRowThatIsGone(ORDER);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		String orderUuid = restored("select uuid from orders where order_id = " + ORDER);
		PatientClinicalContext context;
		try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
			context = PatientClinicalContextBuilder.build(patient);
			assertTrue(capture.hasMessageAt(Level.WARN, orderUuid, DROPPED_WARN),
					"the dropped order must be NAMED where a stock install can see it, or the only sign"
							+ " of a lost prescription is a stamp that says the read was incomplete"
							+ " without saying which order; was: " + capture.describeAll());
		}

		assertTrue(context.getActiveDrugOrders().isEmpty(),
				"precondition: this fixture must leave the order on neither rung, or the case measures"
						+ " the degradation above instead; was: " + context.getActiveDrugOrders());
		assertFalse(context.activeDrugOrdersRead(),
				"an order dropped from the list is a prescription the module cannot account for, so the"
						+ " pass must not report the active orders as read (issue #413)");
		assertFalse(validator.standingChartAlerts(patient).isScreened(),
				"and the standing surface must not certify an empty alert list as a clean screen for a"
						+ " patient who has an active prescription");
	}

	/**
	 * The claim the loop's own comment makes about a DROPPED order, which no other case here reaches:
	 * the orders beside it keep their place. The degraded case above cannot show it — its broken
	 * order stays on the list — and the dropped case above it cannot either, patient 7 having one
	 * active order to lose.
	 *
	 * <p>Concept 792 is blanked rather than order 222's own, because 222 and 3 share it: order 3
	 * keeps its own readable drug and so keeps a name, which is what makes this a list with one
	 * order missing rather than an empty one.
	 */
	@Test
	public void anOrderTheModuleCannotNameAtAllLeavesTheOrdersBesideItOnTheList() throws Exception {
		Patient secondPatient = Context.getPatientService().getPatient(2);
		Set<String> readable =
				orderUuids(PatientClinicalContextBuilder.build(secondPatient).getActiveDrugOrders());
		String broken = restored("select uuid from orders where order_id = " + SECOND_PATIENT_ORDER);
		assertTrue(readable.contains(broken) && readable.size() > 1,
				"precondition: the order this case drops must be one of SEVERAL active orders, was: "
						+ readable);

		DrugReferenceTestSupport.nameTheConcept(SECOND_PATIENT_CONCEPT, "");
		pointTheOrderAtADrugRowThatIsGone(SECOND_PATIENT_ORDER);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(secondPatient);

		Set<String> surviving = orderUuids(context.getActiveDrugOrders());
		assertFalse(surviving.contains(broken),
				"precondition: this fixture must actually drop that order, or the case measures the"
						+ " degradation instead; was: " + surviving);
		readable.remove(broken);
		assertEquals(readable, surviving,
				"every other order keeps its place — the dropped one costs itself and nothing else,"
						+ " where before this fix it took every order after it too; was: "
						+ surviving);
		assertFalse(context.activeDrugOrdersRead(),
				"and the pass says it is incomplete, one order having gone missing from it");
	}

	/**
	 * A {@code Drug} whose own {@code name} column is null is still a {@code Drug} this pass READ,
	 * and the accessor must hand it back rather than report the order as carrying no coded drug.
	 *
	 * <p>The two are not interchangeable and the difference is silent: returning the no-coded-drug
	 * answer would classify the order from {@code drugOrder.getConcept()} instead of the drug's own
	 * concept — issue #353's subject — and skip the dose-form read. This case measures the dose form,
	 * that being the half with an observable difference on this fixture; drug 3 carries none in the
	 * standard dataset, so the case gives it one and compares against the same chart without it.
	 *
	 * <p>It is also the only case here that exercises the dose-form half of
	 * {@code addAdministration} at all.
	 */
	@Test
	public void aDrugWithNoNameOfItsOwnIsStillADrugTheModuleRead() throws Exception {
		describeTheDrug(null, null);
		Set<String> withoutAForm = DrugReferenceTestSupport
				.onlyActiveOrder(PatientClinicalContextBuilder.build(patient)).getAdministrationTerms();

		describeTheDrug(null, String.valueOf(SECOND_PATIENT_CONCEPT));
		Set<String> withAForm = DrugReferenceTestSupport
				.onlyActiveOrder(PatientClinicalContextBuilder.build(patient)).getAdministrationTerms();

		assertTrue(withAForm.containsAll(withoutAForm),
				"the route's own terms are not disturbed by giving the drug a dose form, was: "
						+ withAForm + " against " + withoutAForm);
		assertFalse(withAForm.equals(withoutAForm),
				"and the dose form recorded on a drug with no name of its own must still reach the"
						+ " administration terms — an accessor that answered \"no coded drug\" for it"
						+ " would skip that read and classify the order from its own concept instead of"
						+ " the drug's (issue #353), silently; was: " + withAForm);
	}

	/**
	 * The GATE, which nothing else here measures: the stamp follows a failed drug READ and not the
	 * drop. An order that never had a coded drug, a name or an ATC code is dropped by the same
	 * branch's condition being false — nothing failed, so the pass is still a complete read.
	 *
	 * <p>Widening the branch to a bare {@code else} flips this chart to {@code screened: false}, which
	 * a client must render as a screen that did not run — so every finding the chart holds is withheld
	 * over one order that was never named, and base answers {@code true} here, which makes it a
	 * regression rather than a trade. Measured that way on a constructed chart, with an allergy
	 * contraindication raised by a sibling order; this fixture's own chart raises no alert, so what
	 * the case below asserts is the flag. Before it was written the whole suite was green under that
	 * widening.
	 */
	@Test
	public void anOrderThatSimplyNeverHadANameIsNotAFailedReadAndDoesNotStampThePassUnread()
			throws Exception {
		Patient secondPatient = Context.getPatientService().getPatient(2);
		String never = restored("select uuid from orders where order_id = " + SECOND_PATIENT_ORDER);
		DrugReferenceTestSupport.nameTheConcept(SECOND_PATIENT_CONCEPT, "");
		// No coded drug and no free text, so nothing about this order can fail to read.
		restoreTheOrder(SECOND_PATIENT_ORDER, null, null);

		PatientClinicalContext context = PatientClinicalContextBuilder.build(secondPatient);

		assertFalse(orderUuids(context.getActiveDrugOrders()).contains(never),
				"precondition: the order must reach neither rung, or this measures nothing; was: "
						+ context.getActiveDrugOrders());
		assertTrue(context.activeDrugOrdersRead(),
				"nothing failed to read, so the pass is complete — stamping it unread here withholds"
						+ " every finding on the chart over an order that was never named (issue #413)");
		assertTrue(DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.standingChartAlerts(secondPatient).isScreened(),
				"and the standing surface still screens this chart");
	}

	/**
	 * The other side of the accessor's null: an order carrying NO coded drug is the ordinary shape of
	 * a free-text prescription, nothing failed, and nothing must be logged about it.
	 *
	 * <p>It pins the null short-circuit. Without it the accessor reads {@code getName()} on a null
	 * and its own catch reports a {@code NullPointerException} as an unreadable drug, on orders that
	 * are perfectly healthy — a WARN an operator cannot act on, which is worse than none. This case
	 * is what reddens for that; before it was written the whole api suite stayed green while the
	 * builder logged those WARNs throughout. It is also the only case here that enters {@code drug()}
	 * by the null path rather than by the throw.
	 */
	@Test
	public void anOrderWithNoCodedDrugAtAllIsNotReportedAsAnUnreadableOne() throws Exception {
		restoreTheOrder(ORDER, null, null);

		PatientClinicalContext context;
		try (LogCapture capture = LogCapture.on(BUILDER_LOGGER)) {
			context = PatientClinicalContextBuilder.build(patient);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a chart with nothing wrong with it must produce no WARN from the builder, was: "
							+ capture.describeAll());
		}
		assertTrue(context.activeDrugOrdersRead(), "and the pass is a complete read");
		assertFalse(DrugReferenceTestSupport.onlyActiveOrder(context).getNames().isEmpty(),
				"with the order still named, by the concept it was written against");
	}

	/**
	 * The surface the issue reproduces on: {@code GET /chartsearchai/chartalerts} answered
	 * {@code screened: false, alerts: []} for a chart holding findings, because the unreadable drug
	 * left {@code chartReadForSafety()} false. Entered at the public {@code Patient} arity, which is
	 * the one the handler calls.
	 *
	 * <p><b>It pins the flag and not the payload.</b> Measured, this fixture's chart raises no alert
	 * either way, so what the case shows is that the surface stops calling the chart unscreened —
	 * not that a withheld chip now reaches a client. The issue's own reproduction had three, and a
	 * patient whose standing screen raises one would make this the whole of it.
	 */
	@Test
	public void theStandingSurfaceStillReportsTheChartAsScreened() throws Exception {
		pointTheOrderAtADrugRowThatIsGone(ORDER);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		DrugSafetyValidator.StandingChartAlerts standing = validator.standingChartAlerts(patient);

		assertTrue(standing.isScreened(),
				"one order whose drug cannot be read must not make the standing surface report a chart"
						+ " it did screen as unscreened (issue #413)");
	}

	/**
	 * The structural half: the association is reached through ONE guarded accessor and nowhere else.
	 *
	 * <p>{@code SourceScan} blanks comments and string literals, so a javadoc naming the accessor is
	 * not a use of it, and several in that file do. The count alone would not say WHERE the one use
	 * is, which is why the offset is placed inside the accessor's own body: a single dereference that
	 * had migrated back out to a call site would satisfy a count and reinstate the defect.
	 *
	 * <p><b>The third assertion stopped being text, and that is issue #421.</b> It used to permit a
	 * short allow-list of reads on the {@code Drug} the accessor handed back and refuse everything
	 * else. A text rule cannot answer that question: measured on {@code 01337385}, a chain written
	 * {@code drug.getConcept()} + newline + {@code .getName();} in {@code addDrugName} left the whole
	 * api suite green, and so did a receiver wrapped as {@code drug} + newline + {@code .getConcept();}
	 * — the second for a different reason, the needle {@code drug.get} matching no offset at all, so
	 * the allow-list never ran. Sharpening the pattern would have closed the first and not the second.
	 * So the production side stopped handing back an entity instead: {@code CodedDrug} carries the
	 * three VALUES the loop needs, every one read inside the accessor's own {@code try}, and a
	 * dereference at a call site is now a compile error rather than a spelling for a scan to
	 * recognise. What this assertion pins is that property — the accessor hands back no {@code Drug}
	 * — asked of the compiled class rather than of the source text.
	 *
	 * <p><b>What these three assertions are for, and what they are not.</b> They catch the ordinary
	 * edit — a {@code Drug} put back on {@code CodedDrug} so the loop can reach it again, a second
	 * naming of the association, a catch narrowed to the exception this suite happens to stage. Two
	 * of the three are still text over one file, so an edit that sets out to evade them can: a decoy
	 * {@code catch (RuntimeException …)} placed inside the accessor while the real handler is
	 * narrowed. That was measured green, and adding a rule per spelling is the loop this class
	 * declines to enter — a reviewer, not a regex, is what catches an edit written to get past them.
	 * The residues that remain: the text pair reads ONE file, so {@code drugOrder.getDrug()} called
	 * from another class is outside them — there is none today, and nothing here would notice one
	 * arriving; and the {@code getDrug()} needle carries no receiver, so a call wrapped across lines
	 * IS caught while one spelled {@code getDrug ()} is not.
	 *
	 * <p><b>And the third assertion is about the {@code Drug} alone, deliberately.</b> It walks the
	 * builder and every class nested in it, so a SECOND carrier holding the entity is caught as well
	 * as {@code CodedDrug} — measured, a name-keyed version of this assertion was green on exactly
	 * that edit. What it admits on purpose is the two {@code Concept} proxies the carrier holds, the
	 * drug's own concept and its dose form: {@code Drug.hbm.xml} maps both default-lazy, so a read of
	 * one at a call site compiles and can throw just as a read of the entity could, and measured, a
	 * {@code coded.dosageForm.getUuid()} added at a call site leaves this class green. That is not
	 * this guard's subject and must not become it — the loop NEEDS those two, and what keeps them safe
	 * is that every helper reading a concept opens its own {@code try}, which
	 * {@code PatientClinicalContextBuilder.conceptUuid}'s javadoc is the one home for. None of these
	 * residues is worth a cleverer pattern: what this guard is for is the ordinary edit, and the
	 * behavioural cases above redden for any of them that this fixture's orders reach.
	 */
	@Test
	public void nothingReachesTheDrugAssociationExceptTheGuardedAccessor() throws IOException {
		SourceScan scan = new SourceScan(RELATIVE_SOURCE);

		List<Integer> dereferences = scan.literalOffsets("getDrug()");

		assertEquals(1, dereferences.size(),
				"DrugOrder.getDrug() must be named exactly once in the builder — inside the accessor that"
						+ " materialises it in its own try — and was named at lines "
						+ scan.linesOf(dereferences) + ". A second naming is a read of a lazy association"
						+ " outside that try, and the catch it would land in is outside the active-order"
						+ " loop, so it costs that order and every order after it rather than one"
						+ " order's name (issue #413).");
		assertTrue(scan.body(GUARDED_ACCESSOR).contains(dereferences.get(0)),
				"and that one naming must sit inside " + GUARDED_ACCESSOR + ", not at a call site; it was"
						+ " at line " + scan.lineOf(dereferences.get(0)));

		boolean catchesBroadly = false;
		for (Integer at : scan.literalOffsets(BROAD_CATCH)) {
			catchesBroadly = catchesBroadly || scan.body(GUARDED_ACCESSOR).contains(at);
		}
		assertTrue(catchesBroadly,
				"and it must catch " + BROAD_CATCH + " — inside its OWN body, this file's other reads"
						+ " catching the same thing being no help to a file-wide scan. Narrowing it to"
						+ " the exception"
						+ " this suite can"
						+ " stage (ObjectNotFoundException) leaves every case here green while removing"
						+ " the guard against the one issue #413 actually reports, a"
						+ " LazyInitializationException that no context-sensitive test can produce —"
						+ " which is the whole reason this assertion is structural rather than"
						+ " behavioural.");

		List<String> handingBackAnEntity = new ArrayList<String>();
		collectEntityCarriers(PatientClinicalContextBuilder.class, handingBackAnEntity);
		assertTrue(handingBackAnEntity.isEmpty(),
				"nothing in " + PatientClinicalContextBuilder.class.getSimpleName() + " may hand the"
						+ " build loop a Drug — the accessor reads what the loop needs inside its own try"
						+ " and carries the VALUES — and one is handed back through " + handingBackAnEntity
						+ ". A Drug reachable at a call site is a lazy association dereferenced outside"
						+ " that try, and the catch it would land in is outside the active-order loop, so"
						+ " it costs that order and every order after it (issue #413). Materialising the"
						+ " entity does not materialise its lazy collections, and"
						+ " getFullName/getDisplayName reach its own lazy concept, so a further read can"
						+ " throw where the three the accessor makes cannot. Read it in the accessor and"
						+ " carry the value (issue #421).");
	}

	/**
	 * Every field and every return type declared by {@code type} and, recursively, by the classes
	 * nested inside it — the walk rather than a lookup keyed on one carrier's name, because the
	 * question is whether ANY of them hands a {@code Drug} out.
	 *
	 * <p>Measured: a SECOND private nested carrier holding the entity, handed out on {@code CodedDrug}
	 * and dereferenced in the build loop, is issue #413's defect exactly and left a name-keyed version
	 * of this assertion green. {@code DrugReferenceSourceValidityChannelTest.collectSources} walks
	 * nested types for the same reason, and its javadoc records the same hole in the same words.
	 *
	 * <p>Reflection does not report anonymous or local classes, so one declared inside a method body
	 * is outside this walk; and it asks about fields and RETURN types, so a parameter of that type is
	 * too. Neither is reachable from the loop today — there is nothing there holding a {@code Drug} to
	 * pass — and nothing here would notice one arriving.
	 */
	private static void collectEntityCarriers(Class<?> type, List<String> found) {
		for (Field field : type.getDeclaredFields()) {
			if (Drug.class.equals(field.getType())) {
				found.add(type.getSimpleName() + " field " + field.getName());
			}
		}
		for (Method method : type.getDeclaredMethods()) {
			if (Drug.class.equals(method.getReturnType())) {
				found.add(type.getSimpleName() + " method " + method.getName() + "()");
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			collectEntityCarriers(nested, found);
		}
	}

	/**
	 * The operator's remedy, which is a different question from the verdict: the standing surface's
	 * own WARN must not send an operator to grant a privilege where there is none to grant.
	 *
	 * <p>The other arm is the case below, and it is not decoration: an assertion over this arm alone
	 * is satisfied by a line that never varies, which would be the privilege sentence taken away
	 * from the cause issue #247 put it there for.
	 *
	 * <p>Before this branch the dropped-order arm logged the privilege line verbatim: an operator
	 * whose {@code /chartalerts} flipped to {@code screened: false} because one order points at an
	 * unreadable {@code drug} row was told to check a privilege the role already held, saw the flag
	 * stay false, and read the same line on the next poll — the round trip the two-branch form of
	 * this message was written for issue #247 to remove, for a cause no grant can reach.
	 */
	@Test
	public void theStandingSurfaceOffersNoPrivilegeToGrantForAnOrderItCouldNotAccountFor()
			throws Exception {
		DrugReferenceTestSupport.nameTheConcept(ORDERED_CONCEPT, "");
		pointTheOrderAtADrugRowThatIsGone(ORDER);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		try (LogCapture capture = LogCapture.on(VALIDATOR_LOGGER)) {
			assertFalse(validator.standingChartAlerts(patient).isScreened(),
					"precondition: this fixture must leave the chart unscreened, or the line under test"
							+ " is never reached");
			assertFalse(capture.hasMessageAt(Level.WARN, PRIVILEGE_REMEDY),
					"the standing surface must not tell an operator to check a privilege for an order it"
							+ " read and could not account for — the read happened and every privilege is"
							+ " held, so the grant changes nothing and the flag stays false (issue #413);"
							+ " was: " + capture.describeAll());
			assertTrue(capture.hasMessageAt(Level.WARN, "could not be accounted for"),
					"and it must still say what it found, or an empty alert list beside screened:false"
							+ " has no operator-facing explanation at all; was: " + capture.describeAll());
		}
	}

	/**
	 * The other arm of that branch, and what makes it a branch: a read core DOES gate on a privilege
	 * still names it. Without this case the message could be rewritten to state the no-privilege
	 * wording unconditionally and the case above would stay green, which would take the
	 * {@code Get Orders} remedy away from the cause issue #247 put it there for.
	 *
	 * <p>Refusing the privilege is what fails the read the way production fails it — the same
	 * arrangement {@code ChartReadFailureLoudnessContextTest} makes for the BUILDER's line, which is
	 * a different channel: that one names which read failed, this one what the surface does about it.
	 */
	@Test
	public void theStandingSurfaceStillNamesThePrivilegeForAReadCoreGatesOnOne() {
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		DrugReferenceTestSupport.refusingPrivilege(PrivilegeConstants.GET_ORDERS, () -> {
			try (LogCapture capture = LogCapture.on(VALIDATOR_LOGGER)) {
				assertFalse(validator.standingChartAlerts(patient).isScreened(),
						"precondition: refusing Get Orders must leave the chart unscreened");
				assertTrue(capture.hasMessageAt(Level.WARN, PRIVILEGE_REMEDY, "Get Orders"),
						"a read core gates on a privilege must still send an operator to grant it; was: "
								+ capture.describeAll());
				assertFalse(capture.hasMessageAt(Level.WARN, COMBINED_CAUSE),
						"and it must not also report an order left off the list, which nothing here did:"
								+ " refusing Get Orders makes core's @Authorized check throw at the"
								+ " getActiveOrders CALL, so this pass records no drop. Without this the"
								+ " suffix could be appended unconditionally and every case stay green"
								+ " (issue #421); was: " + capture.describeAll());
			}
			return null;
		});
	}

	/**
	 * The third state of that message, and not a variant of either neighbour above: a read that FAILED
	 * and, in the same pass, an order left off the list. The two causes are independent, so the line
	 * has to state both — the privilege remedy for the one a grant fixes, and the appended sentence
	 * for the one no grant reaches.
	 *
	 * <p>Before this case neither arm of that suffix was observed anywhere (issue #421). Replacing the
	 * whole conditional with the empty string left the api suite green, and so did appending the
	 * suffix unconditionally — the first is what this case reddens for, the second what the assertion
	 * added to {@link #theStandingSurfaceStillNamesThePrivilegeForAReadCoreGatesOnOne} reddens for.
	 *
	 * <p><b>Failure mode it pins.</b> Told only to grant {@code Get Allergies} and {@code Get
	 * Conditions}, an operator grants them, sees {@code screened} stay false because the dropped order
	 * is still unaccounted for, and reads the same line on the next poll. That round trip is what the
	 * two-branch form of this message was written for issue #247 to remove, and the second cause has
	 * no privilege to grant, so nothing else in the line would lead them to it.
	 *
	 * <p>The arm is reached here by refusing {@code Get Allergies} while the order read COMPLETES and
	 * drops an order. That is one route to it and the case does not claim to be the only one: a throw
	 * partway through the order loop reaches the same arm with {@code unread} naming active orders
	 * instead, since the drop is recorded inside the loop the catch wraps.
	 */
	@Test
	public void theStandingSurfaceNamesBothCausesWhereOnePassHitsBoth() throws Exception {
		DrugReferenceTestSupport.nameTheConcept(ORDERED_CONCEPT, "");
		pointTheOrderAtADrugRowThatIsGone(ORDER);
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		DrugReferenceTestSupport.refusingPrivilege(PrivilegeConstants.GET_ALLERGIES, () -> {
			try (LogCapture capture = LogCapture.on(VALIDATOR_LOGGER)) {
				assertFalse(validator.standingChartAlerts(patient).isScreened(),
						"precondition: a failed record read and a dropped order must leave the chart"
								+ " unscreened, or the line under test is never reached");
				assertTrue(capture.hasMessageAt(Level.WARN, PRIVILEGE_REMEDY, COMBINED_CAUSE),
						"one line must carry BOTH causes — the privileges to grant for the read that"
								+ " failed, and the order left off the list although its read completed,"
								+ " which no privilege fixes. An operator told only the first grants it and"
								+ " reads the same line again (issue #421); was: " + capture.describeAll());
			}
			return null;
		});
	}

	/**
	 * The stamp survives the enrichment the real pass applies, which is where this shape has cost two
	 * regressions before — {@code StandingChartAlertsTest.bothChartReadStampsSurviveTheEnrichmentThePassApplies}
	 * pins the same copy for the two stamps that predate issue #413.
	 *
	 * <p>The context is the real builder's, on the chart this class drops an order from. The entry
	 * list is resolved off the loaded dataset rather than off that chart's surviving orders, because
	 * {@code withReferenceNames} returns the context UNTOUCHED for an empty list and the curated seed
	 * names none of patient 2's remaining prescriptions — measured, which is why the first version of
	 * this case failed its own precondition. The copy is the subject here and it does not read the
	 * entries beyond their aliases, so what the list resolved FROM cannot change what is measured.
	 *
	 * <p>Dropping the new field from that copy flips the enriched context back to a complete read,
	 * silently and fail-OPEN, and every other case in this class reads the RAW context.
	 */
	@Test
	public void theDroppedOrdersMarkSurvivesTheEnrichmentThePassApplies() throws Exception {
		Patient secondPatient = Context.getPatientService().getPatient(2);
		DrugReferenceTestSupport.nameTheConcept(SECOND_PATIENT_CONCEPT, "");
		pointTheOrderAtADrugRowThatIsGone(SECOND_PATIENT_ORDER);
		DrugReferenceService service = DrugReferenceTestSupport.curatedService();

		PatientClinicalContext raw = PatientClinicalContextBuilder.build(secondPatient);
		assertFalse(raw.activeDrugOrdersRead(),
				"precondition: the raw context must already report the incomplete read");
		List<DrugReference> orderEntries = service.findByDrugName(ENRICHING_DRUG);
		assertFalse(orderEntries.isEmpty(),
				"precondition: the entry list must be non-empty, or withReferenceNames returns the "
						+ "context untouched and this case reaches no copy at all");

		PatientClinicalContext enriched = service.withReferenceNames(raw, orderEntries);

		assertFalse(enriched.activeDrugOrdersRead(),
				"the enriched copy must still carry the mark the raw context set, or a dropped order is "
						+ "forgotten between the builder and the reader (issue #413)");
		assertFalse(DrugReferenceTestSupport.validator(service).standingChartAlerts(enriched)
				.isScreened(),
				"and the one reader that asks for both stamps must still refuse to certify the screen");
	}

}
