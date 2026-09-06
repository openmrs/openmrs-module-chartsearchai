/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The forcing function for {@link ChartSearchAiUtils#mayDescribeAMedicationOrder} (issue #377) — the
 * same one {@code ChartSearchAiReferenceGroupTest} provides for the reference-group predicates, and
 * for the same reason: a type-keyed classification with no sweep is one a later declared type falls
 * out of silently.
 *
 * <p>Here it falls out LOUDLY rather than silently — an unadmitted type is a type the check
 * REPORTS — which is the direction the ticket forced, since the two types its measurement caught
 * ({@code visit}, {@code encounter}) are querystore contract strings this module never declares and
 * a deny-list could not have named them. That is exactly why the sweep is worth having: adding a
 * declared type without deciding this question turns every active-order sentence citing one into a
 * WARN and a wire entry, and nothing else in the suite would say so.
 */
public class MedicationOrderRecordTypeTest {

	/**
	 * Whether a record of each DECLARED resource type can be a medication the patient was prescribed
	 * or given. Recorded here rather than derived, so the sweep below is a decision this file made
	 * and not a restatement of the predicate.
	 *
	 * <p>The reasons live in {@code ChartSearchAiUtils.mayDescribeAMedicationOrder}'s javadoc,
	 * including why {@code RESOURCE_TYPE_ORDER} is false and what admitting
	 * {@code RESOURCE_TYPE_MEDICATION_DISPENSE} gives up.
	 */
	private static Map<String, Boolean> recordedAdmissions() {
		Map<String, Boolean> recorded = new LinkedHashMap<String, Boolean>();
		recorded.put("RESOURCE_TYPE_OBS", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_CONDITION", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_ALLERGY", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_DIAGNOSIS", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_ORDER", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_PROGRAM", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_MEDICATION_DISPENSE", Boolean.TRUE);
		recorded.put("RESOURCE_TYPE_DRUG_ORDER", Boolean.TRUE);
		recorded.put("RESOURCE_TYPE_DRUG_REFERENCE", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_SAFETY_FINDING", Boolean.FALSE);
		recorded.put("RESOURCE_TYPE_ACTIVE_DRUG_ORDER", Boolean.TRUE);
		recorded.put("RESOURCE_TYPE_DRUG_CLASS_NOTE", Boolean.FALSE);
		return recorded;
	}

	@Test
	public void everyDeclaredResourceTypeConstant_hasADecidedAdmission() {
		Map<String, Boolean> recorded = recordedAdmissions();
		Map<String, String> declared = declaredResourceTypeConstants();

		List<String> undecided = new ArrayList<String>();
		for (Map.Entry<String, String> constant : declared.entrySet()) {
			if (!recorded.containsKey(constant.getKey())) {
				undecided.add(constant.getKey());
				continue;
			}
			assertEquals(recorded.get(constant.getKey()).booleanValue(),
					ChartSearchAiUtils.mayDescribeAMedicationOrder(constant.getValue()),
					constant.getKey() + " (\"" + constant.getValue()
							+ "\") is admitted differently than this test records");
		}
		assertTrue(undecided.isEmpty(),
				"new resource-type constant(s) " + undecided + " have no recorded admission. Decide: "
						+ "can a record of this type be a medication the patient was prescribed or "
						+ "given? Leaving it out is not neutral — a citation of one inside an "
						+ "active-order sentence is then REPORTED, at WARN and on the wire.");

		// The reverse direction, so a removed or renamed constant cannot leave a dead row here
		// quietly claiming to guard a type that no longer exists.
		List<String> stale = new ArrayList<String>(recorded.keySet());
		stale.removeAll(declared.keySet());
		assertTrue(stale.isEmpty(), "this test records admissions for constant(s) " + stale
				+ " that ChartSearchAiConstants no longer declares");
	}

	@Test
	public void aResourceTypeThisModuleNeverDeclaresIsNotAdmitted() {
		// The ticket's own two, which are querystore contract strings rather than declared constants
		// — so the sweep above cannot reach them and this is what pins the allow-list's DIRECTION.
		// A clinician following the citation behind "interacts with active order Methylprednisolone"
		// was shown a condition; behind Budesonide, this visit; behind Prednisone, this encounter.
		assertFalse(ChartSearchAiUtils.mayDescribeAMedicationOrder("visit"),
				"a visit is not a medication order");
		assertFalse(ChartSearchAiUtils.mayDescribeAMedicationOrder("encounter"),
				"an encounter is not a medication order");
		assertFalse(ChartSearchAiUtils.mayDescribeAMedicationOrder(null),
				"and a record with no type at all cannot be asserted to be one");
	}

	/** {@code ChartSearchAiReferenceGroupTest}'s own sweep, written again rather than shared: that
	 *  file's copy is private to it, and a test-support class for one reflection loop would put a
	 *  seam between two guards that must be able to redden independently. */
	private static Map<String, String> declaredResourceTypeConstants() {
		Map<String, String> constants = new LinkedHashMap<String, String>();
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			if (!field.getName().startsWith("RESOURCE_TYPE_") || field.getType() != String.class
					|| !Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				constants.put(field.getName(), (String) field.get(null));
			}
			catch (IllegalAccessException e) {
				throw new AssertionError("could not read " + field.getName(), e);
			}
		}
		return constants;
	}
}
