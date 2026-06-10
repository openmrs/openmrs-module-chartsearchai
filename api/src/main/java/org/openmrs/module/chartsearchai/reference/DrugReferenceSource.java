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

import java.util.List;

/**
 * A source of {@link DrugReference} entries. Decouples the drug-reference data
 * <em>layer</em> from any one file format so the feature can consume datasets
 * published by authoritative bodies (e.g. the WHO ATC classification) by simply
 * pointing at them, rather than hand-maintaining a chartsearchai-specific file.
 *
 * <p>Each implementation maps one external format to the internal model;
 * {@link DrugReferenceService} selects the active source by the
 * {@code chartsearchai.drugReference.sourceFormat} global property. See ADR
 * Decision 24.
 */
public interface DrugReferenceSource {

	/**
	 * @return the reference entries this source provides; never null (empty when
	 *         nothing could be loaded). Implementations must fail safe — a missing
	 *         or unreadable dataset degrades to an empty list, never an exception,
	 *         so the drug-reference feature stays an additive net that cannot break
	 *         the answer path.
	 */
	List<DrugReference> load();
}
