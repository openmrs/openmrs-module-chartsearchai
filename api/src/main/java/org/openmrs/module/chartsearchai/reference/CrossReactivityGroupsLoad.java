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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one curated cross-reactivity groups load: which file was read, how many groups it
 * produced, and what the validity check found — reported by
 * {@link DrugReferenceService#getCrossReactivityLoadStatus()} and serialized under
 * {@code crossReactivity} on {@code GET /chartsearchai/drugreferencestatus}.
 *
 * <p>Issue #266. Before it, this load's findings reached only the log: the resolution really did raise
 * issue #156's {@code configured-data-file-not-read} for the groups file, and
 * {@link CrossReactivityGroupsLoader} dropped it into {@code logTo} because there was nothing retaining
 * it — which its own javadoc called "a gap rather than a design". The log cannot answer after a lazy
 * load (issue #154) and an operator cannot be expected to poll it at the moment a module starts, so a
 * finding wired to that channel alone is the muted silence {@code CLAUDE.md} forbids.
 *
 * <p><b>A section of its own rather than rows in {@link DrugReferenceLoad#getFindings()}</b>, and that
 * is the decision rather than an accident of layering (ADR Decision 48). The groups file has its own
 * global property, its own lazy load and its own origin, so a finding merged into the entry load's list
 * would name a rule and a remedy with nothing saying WHICH file it is about — and
 * {@code configured-data-file-not-read} is precisely a rule about a named file.
 *
 * <p>Deliberately narrower than {@link DrugReferenceLoad}: there is no {@code inert} verdict here,
 * because carrying no cross-reactivity groups is a legitimate configuration — the class arms still
 * reason from ATC subgroups — so there is nothing for such a verdict to assert. Immutable.
 */
public final class CrossReactivityGroupsLoad {

	private final boolean loaded;

	private final String configuredFilePath;

	private final String origin;

	private final int groupCount;

	private final List<DrugReferenceValidity.Finding> findings;

	CrossReactivityGroupsLoad(String configuredFilePath, String origin, int groupCount,
			List<DrugReferenceValidity.Finding> findings) {
		this.loaded = true;
		this.configuredFilePath = configuredFilePath == null ? "" : configuredFilePath;
		this.origin = origin == null ? ReferenceDataFiles.ORIGIN_NONE : origin;
		this.groupCount = groupCount;
		this.findings = Collections.unmodifiableList(findings == null
				? new ArrayList<DrugReferenceValidity.Finding>()
				: new ArrayList<DrugReferenceValidity.Finding>(findings));
	}

	private CrossReactivityGroupsLoad() {
		this.loaded = false;
		this.configuredFilePath = "";
		this.origin = ReferenceDataFiles.ORIGIN_NONE;
		this.groupCount = 0;
		this.findings = Collections.emptyList();
	}

	/**
	 * @return the outcome to report where no groups load has happened and none may be triggered — the
	 *         feature is switched off. Reading a status endpoint must not be what performs a parse on an
	 *         install that does not use the feature, which is the same reason
	 *         {@link DrugReferenceLoad#notLoaded()} exists, and the same reason the entry-side test seams
	 *         pair injected data with it.
	 *
	 *         <p>Package-private, like {@link DrugReferenceLoad#notLoaded()}: the only caller is
	 *         {@link DrugReferenceService}, and this state is something the service DECIDES rather than
	 *         something a consumer of the status constructs.
	 */
	static CrossReactivityGroupsLoad notLoaded() {
		return new CrossReactivityGroupsLoad();
	}

	/** @return whether a groups load actually happened; false means nothing was read and nothing tried. */
	public boolean isLoaded() {
		return loaded;
	}

	/**
	 * @return the value of {@code chartsearchai.drugReference.crossReactivityGroupsFilePath} at the
	 *         moment of the load, or {@code ""}. Reported beside {@link #getOrigin()} because a
	 *         {@code configured-data-file-not-read} finding is only actionable next to both — the whole
	 *         diagnosis is that these two disagree.
	 */
	public String getConfiguredFilePath() {
		return configuredFilePath;
	}

	/**
	 * @return where the groups actually came from, in the vocabulary
	 *         {@link ReferenceDataFiles#APPDATA_ORIGIN_PREFIX} /
	 *         {@link ReferenceDataFiles#CLASSPATH_ORIGIN_PREFIX} / {@link ReferenceDataFiles#ORIGIN_NONE}
	 *         defines. Relative rather than absolute for the reason that prefix's javadoc gives: this
	 *         value is served over REST to any caller holding core's {@code Get Global Properties}
	 *         privilege, which the {@code Authenticated} role holds by default, while core keeps its own
	 *         disclosure of the application data directory behind {@code View Administration Functions}.
	 */
	public String getOrigin() {
		return origin;
	}

	/** @return how many usable groups the load produced. Zero is a legitimate state, not a defect. */
	public int getGroupCount() {
		return groupCount;
	}

	/**
	 * @return what the validity check found while this load resolved and parsed the groups file; never
	 *         null. Carried identically whichever dataset was read, and so is the LOG for this dataset:
	 *         {@link CrossReactivityGroupsLoader#load()} reports through the one-argument
	 *         {@link DrugReferenceValidity#logTo(org.slf4j.Logger)}, which is unconditionally WARN, so the
	 *         origin-keyed softening ADR Decision 36 gives the ENTRY dataset does not apply here — ADR
	 *         Decision 48 left this channel loud rather than re-registering it. Do not scope either one.
	 */
	public List<DrugReferenceValidity.Finding> getFindings() {
		return findings;
	}

	/**
	 * @return this outcome as a JSON-serializable map, for the REST status endpoint. Insertion-ordered,
	 *         and a new key is always APPENDED — the same rule {@link DrugReferenceLoad#toMap()} states,
	 *         so the endpoint's frozen key list stays an ordered assertion.
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("loaded", loaded);
		map.put("groupCount", groupCount);
		map.put("configuredFilePath", configuredFilePath);
		map.put("origin", origin);
		map.put("findings", DrugReferenceValidity.toMaps(findings));
		return map;
	}

	@Override
	public String toString() {
		return findings.isEmpty() ? toMap().toString() : toMap() + " findings=" + findings;
	}
}
