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

	/**
	 * @return where the entries {@link #load()} last returned were actually read from, marked with the
	 *         space it came from: {@code appdata:<path within the application data directory>} for an
	 *         operator file, {@code classpath:<resource>} for the bundled fallback, or {@code none}
	 *         when nothing could be read. {@code null} when the implementation does not track it (the
	 *         test seam).
	 *
	 *         <p>An implementation must NOT return the absolute path: this value is served over REST
	 *         to any caller holding the core {@code Get Global Properties} privilege, which the
	 *         {@code Authenticated} role holds by default, while core keeps its own disclosure of the
	 *         application data directory behind {@code View Administration Functions}. See
	 *         {@link DrugReferenceLoad#getOrigin()}, which is where this value surfaces.
	 *
	 *         <p>Part of the load's outcome rather than of the log, because the load is lazy: a
	 *         reader who consults the log for "which dataset is in force?" can be handed a line
	 *         from a previous load or a previous process, which is how a source switch came to be
	 *         believed that had not happened (issue #149). {@link DrugReferenceService} reads this
	 *         immediately after {@code load()}, on the same instance, and retains it — see
	 *         {@link DrugReferenceService#getLoadStatus()}.
	 */
	default String lastLoadOrigin() {
		return null;
	}
}
