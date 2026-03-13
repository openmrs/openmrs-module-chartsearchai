/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

/**
 * Converts an OpenMRS clinical record into a concise, human-readable text string suitable for
 * vector embedding. Each implementation handles a specific resource type (Obs, Condition,
 * Allergy, etc.) and produces text that captures the clinically meaningful content while
 * discarding structural metadata that adds no semantic value.
 *
 * @param <T> the OpenMRS domain type to serialize
 */
public interface ClinicalTextSerializer<T> {

	/**
	 * Convert a clinical record to a text representation for embedding.
	 *
	 * @param record the clinical record to serialize
	 * @return a concise text string capturing the clinical meaning of the record
	 */
	String toText(T record);
}
