/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.db;

import java.util.List;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;

public interface ChartSearchAiDAO {

	ChartEmbedding saveChartEmbedding(ChartEmbedding chartEmbedding);

	ChartEmbedding getByResource(String resourceType, Integer resourceId);

	List<ChartEmbedding> getByPatient(Patient patient);

	void deleteByPatient(Patient patient);
}
