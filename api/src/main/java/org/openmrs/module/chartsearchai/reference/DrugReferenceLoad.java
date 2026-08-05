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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of the drug-reference dataset load that is <em>in force</em>: which source format was
 * selected, which file the entries were actually read from, and how many there are. Immutable, and
 * built at the moment the load populates {@link DrugReferenceService}'s cache, so it can never
 * describe a different dataset than the one the safety layer is using.
 *
 * <p>Why this is a retained value and not just a log line (issue #149): the load is lazy, so a
 * reader who greps the log for "which dataset is in force?" can be handed the line from a previous
 * load or a previous process. That is not hypothetical — a verification pass switched
 * {@code sourceFormat} to {@code json}, failed to restart the module, read a stale
 * {@code "Loaded 2283 …"} line and concluded the switch had taken effect. Exposed through
 * {@link DrugReferenceService#getLoadStatus()} and the module's
 * {@code GET /chartsearchai/drugreferencestatus} endpoint, both of which report this object rather
 * than re-deriving it from the global properties, so what it says is what is loaded. (The endpoint
 * adds one field of its own, {@code enabled}, which IS a live read — the master switch can be
 * flipped after a load, and then it is meant to disagree with {@link #isLoaded()}.)
 *
 * <p>{@link #isInert()} is the single verdict that distinguishes the two states an empty dataset can
 * be in, and it drives BOTH the WARN at load time and the reported status, so the two cannot drift:
 *
 * <ul>
 * <li><b>Not loaded</b> ({@link #notLoaded()}) — nothing has been loaded, because nothing asked:
 * {@code chartsearchai.drugReference.enabled} is off, so neither {@code DrugSafetyValidator} nor
 * {@code DrugReferenceInjector} reaches the service. A legitimate state (it is the default), so it
 * is silent — warning here would spam every install that does not use the feature.</li>
 * <li><b>Inert</b> — a source WAS selected and loading it produced zero entries. The drug-safety
 * feature is then off while looking healthy: no interaction, allergy or contraindication warning can
 * be raised, and every safety question answers as though there were nothing to find. That is the
 * defect, and it is loud.</li>
 * </ul>
 */
public final class DrugReferenceLoad {

	private final boolean loaded;

	private final String sourceFormat;

	private final String configuredSourceFormat;

	private final String configuredDataFilePath;

	private final String origin;

	private final int entryCount;

	DrugReferenceLoad(String sourceFormat, String configuredSourceFormat, String configuredDataFilePath,
			String origin, int entryCount) {
		this.loaded = true;
		this.sourceFormat = sourceFormat;
		this.configuredSourceFormat = configuredSourceFormat;
		this.configuredDataFilePath = configuredDataFilePath;
		this.origin = origin == null ? ReferenceDataFiles.ORIGIN_NONE : origin;
		this.entryCount = entryCount;
	}

	private DrugReferenceLoad() {
		this.loaded = false;
		this.sourceFormat = null;
		this.configuredSourceFormat = null;
		this.configuredDataFilePath = null;
		this.origin = null;
		this.entryCount = 0;
	}

	/** @return the outcome for "no load has happened", which is not a failure — see the class javadoc. */
	static DrugReferenceLoad notLoaded() {
		return new DrugReferenceLoad();
	}

	/** @return whether the dataset has been loaded at all. */
	public boolean isLoaded() {
		return loaded;
	}

	/**
	 * @return true when a source was selected and loading it produced NO entries, so drug-safety
	 *         checking is inert. False both for a healthy load and for "not loaded at all".
	 */
	public boolean isInert() {
		return loaded && entryCount == 0;
	}

	/**
	 * @return the source format actually used ({@code json}, {@code atc} or {@code ddinter}); null
	 *         when not loaded. Differs from {@link #getConfiguredSourceFormat()} when the configured
	 *         value matches no adapter and the curated default was used — a typo there is itself a
	 *         way to end up inert.
	 */
	public String getSourceFormat() {
		return sourceFormat;
	}

	/** @return the raw {@code chartsearchai.drugReference.sourceFormat} value; null when not loaded. */
	public String getConfiguredSourceFormat() {
		return configuredSourceFormat;
	}

	/**
	 * @return the raw {@code chartsearchai.drugReference.dataFilePath} value ({@code ""} when unset);
	 *         null when not loaded. What was ASKED for — compare with {@link #getOrigin()}, which is
	 *         what was read.
	 */
	public String getConfiguredDataFilePath() {
		return configuredDataFilePath;
	}

	/**
	 * @return where the entries were read from, each form naming the space it came from:
	 *         {@code appdata:<path within the application data directory>} for an operator file,
	 *         {@code classpath:<resource>} for the bundled dataset, or {@code none}. Null when not
	 *         loaded.
	 *
	 *         <p>Reported separately from {@link #getConfiguredDataFilePath()} because a configured
	 *         path that cannot be read falls back to the bundled dataset and yields a perfectly
	 *         plausible entry count — the state in which "the count is non-zero, so my file loaded"
	 *         is false. So a configured file loaded exactly when this reads {@code appdata:} + that
	 *         path.
	 *
	 *         <p>Deliberately not the absolute path: this is served to any caller holding the core
	 *         {@code Get Global Properties} privilege, which the {@code Authenticated} role holds by
	 *         default, and core keeps its own disclosure of the application data directory behind
	 *         {@code View Administration Functions}. The absolute path is still logged at INFO, where
	 *         the audience is already an administrator.
	 */
	public String getOrigin() {
		return origin;
	}

	/** @return the number of reference entries in force; 0 when not loaded. */
	public int getEntryCount() {
		return entryCount;
	}

	/** @return this outcome as a JSON-serializable map, for the REST status endpoint. */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("loaded", loaded);
		map.put("inert", isInert());
		map.put("entryCount", entryCount);
		map.put("sourceFormat", sourceFormat);
		map.put("configuredSourceFormat", configuredSourceFormat);
		map.put("configuredDataFilePath", configuredDataFilePath);
		map.put("origin", origin);
		return map;
	}

	@Override
	public String toString() {
		return toMap().toString();
	}
}
