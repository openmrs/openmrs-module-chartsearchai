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

import java.util.Collections;
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

	/**
	 * @return what the validity check found while {@link #load()} resolved AND parsed its dataset — the
	 *         two kinds of rule that only this implementation can see. The configuration rules, which
	 *         only the resolution knows (see {@link DrugReferenceValidity#configuredDataFileNotRead}),
	 *         and the document rules, which only the PARSER knows because they are about the file's
	 *         shape rather than the entries (see
	 *         {@link DrugReferenceValidity#datasetMissingARequiredTable}). Empty only when the
	 *         implementation runs neither, which of the implementations that exist is the test seam alone
	 *         — every production source overrides this, and
	 *         {@code DrugReferenceSourceValidityChannelTest} is what keeps that true. Issue #266 is why
	 *         it is guarded rather than stated: this method is DEFAULTED, so a source that does not
	 *         override it reports an empty {@code findings} list for its whole format with nothing
	 *         erroring and no log line, which is what {@link AtcDrugReferenceSource} did for as long as it
	 *         resolved its own file.
	 *
	 *         <p><b>A new source format's parser reports here.</b> Worth stating plainly, because the
	 *         alternative is issue #242 one format over: a parser that returns no entries for a document
	 *         it cannot read is the whole defect that issue records, and by the time
	 *         {@link DrugReferenceService} sees the result, the document is gone and only a count of zero
	 *         is left — which cannot tell an empty file from one whose content was discarded.
	 *
	 *         <p>Read immediately after {@code load()} on the same instance and retained beside the
	 *         entries, for the same reason as {@link #lastLoadOrigin()}: the load is lazy, so a log line
	 *         cannot be trusted to describe the load that is in force. The content rules are NOT here —
	 *         they need the loaded model rather than a stream, so {@link DrugReferenceService} runs them
	 *         once for every format instead of each source running its own version.
	 */
	default List<DrugReferenceValidity.Finding> lastLoadFindings() {
		return Collections.emptyList();
	}
}
