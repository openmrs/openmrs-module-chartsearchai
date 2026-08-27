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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one dataset resolution the drug-reference data files use: prefer the file the GP points at
 * (relative to the OpenMRS application data directory), and degrade every failure to an empty list —
 * the drug-reference feature is an additive net that must never break the answer path. Shared so the
 * origin vocabulary, the logging, the exception contract and the {@link DrugReferenceValidity} rule
 * the resolution itself can raise cannot drift between these datasets.
 *
 * <p>Two entry points, differing in ONE thing — whether there is a bundled dataset to take when the
 * operator's file cannot be read. {@link #loadWithClasspathFallback} is for the datasets the module
 * ships one of ({@link JsonDrugReferenceSource}, {@link DdiDrugReferenceSource} and
 * {@link CrossReactivityGroupsLoader}); {@link #loadOperatorFile} is for the one that has none
 * ({@link AtcDrugReferenceSource} — the operator must point at an ATC export they obtained).
 *
 * <p>Until issue #266 the ATC source stayed out of this class altogether and resolved its own file,
 * which is how it came to have no validity channel at all: there was no collector for a rule to be
 * raised into, so issue #156's <em>"the file you configured was not read"</em> was unreachable on the
 * one format most dependent on the operator's own file. The FALLBACK is what it stays out of; the
 * resolution and the rule are shared. The two methods are not expressed in terms of each other on
 * purpose — {@link #loadWithClasspathFallback}'s single collector spans two read attempts and carries a
 * documented misattribution trade that a shared body would have to re-derive, while
 * {@link #loadOperatorFile} has only one attempt and so has no such trade to make.
 */
final class ReferenceDataFiles {

	private static final Logger log = LoggerFactory.getLogger(ReferenceDataFiles.class);

	/** Prefix marking a {@link Loaded#getOrigin()} that names a bundled classpath resource. */
	static final String CLASSPATH_ORIGIN_PREFIX = "classpath:";

	/**
	 * Prefix marking a {@link Loaded#getOrigin()} that names an operator file, relative to the
	 * OpenMRS application data directory — the same form the path global property holds.
	 *
	 * <p>Relative rather than absolute because the origin is reported over REST to any caller with
	 * the core {@code Get Global Properties} privilege, which the {@code Authenticated} role holds by
	 * default. Such a caller can already read the path global property itself, but not the server's
	 * absolute layout: core keeps its own disclosure of the application data directory behind
	 * {@code View Administration Functions}. Nothing is lost — {@code ChartSearchAiUtils.resolveModelPath}
	 * rejects {@code ..} and confirms the file resolves inside that directory, so this form names the
	 * file exactly, and the absolute path is still logged at INFO by
	 * {@link #loadWithClasspathFallback}. See {@code DrugReferenceLoad#getOrigin()}.
	 */
	static final String APPDATA_ORIGIN_PREFIX = "appdata:";

	/** {@link Loaded#getOrigin()} when nothing could be read at all. */
	static final String ORIGIN_NONE = "none";

	/**
	 * @param origin a {@link Loaded#getOrigin()}, or null
	 * @return whether those entries came from a dataset the MODULE ships rather than from a file the
	 *         deployment provided — the question "who can fix a defect in this data", which
	 *         {@link DrugReferenceValidity#logTo(org.slf4j.Logger, String)} reports at the level of.
	 *         Named here because this is where the origin vocabulary is defined; a caller testing the
	 *         prefix itself would be a second answer to the same question. A null or unrecognised origin
	 *         is NOT bundled: unknown provenance is reported loudly, so the fail direction is loud.
	 */
	static boolean isBundledOrigin(String origin) {
		return origin != null && origin.startsWith(CLASSPATH_ORIGIN_PREFIX);
	}

	/** Parses one dataset stream into its items. */
	@FunctionalInterface
	interface DatasetParser<T> {

		/**
		 * @param validity this load's collector, carrying what only the parser can see — what the
		 *        DOCUMENT is missing, as against what the loaded entries say (issue #242). A parser that
		 *        returns nothing should say so here: downstream all that survives is the count, and a
		 *        count of zero cannot distinguish an empty file from one whose content was discarded.
		 *        Every parser this class loads does — {@link CrossReactivityGroupsLoader} last, in issue
		 *        #266. It deliberately wrote nothing here until then, because that dataset had no
		 *        retained status object, so a finding could have reached only one of the two channels
		 *        every rule is required to reach; #266 gave it one ({@link CrossReactivityGroupsLoad})
		 *        and then reported into it, in that order.
		 */
		List<T> parse(InputStream in, DrugReferenceValidity validity) throws IOException;
	}

	/**
	 * One dataset load's outcome: the items, and <em>where they came from</em>.
	 *
	 * <p>The origin is returned rather than only logged because the log line is a historical
	 * record. The drug-reference load is lazy, so by the time anyone asks "which dataset is in
	 * force?" the most recent matching line may belong to a previous load or a previous process —
	 * which is exactly how a verification pass came to believe it had switched source when it had
	 * not (issue #149). Returning it lets {@link DrugReferenceService} retain the answer alongside
	 * the entries it describes.
	 */
	static final class Loaded<T> {

		private final List<T> items;

		private final String origin;

		private final DrugReferenceValidity validity;

		private Loaded(List<T> items, String origin, DrugReferenceValidity validity) {
			this.items = items;
			this.origin = origin;
			this.validity = validity;
		}

		static <T> Loaded<T> nothing(DrugReferenceValidity validity) {
			return new Loaded<T>(Collections.<T> emptyList(), ORIGIN_NONE, validity);
		}

		List<T> getItems() {
			return items;
		}

		/**
		 * @return the validity check this load ran — which here means the configuration rules (see
		 *         {@link DrugReferenceValidity#configuredDataFileNotRead}) and whatever the parser
		 *         reported about the DOCUMENT it was handed (see
		 *         {@link DrugReferenceValidity#datasetMissingARequiredTable}), since the content rules
		 *         need the loaded model rather than a stream and run once for every format in
		 *         {@link DrugReferenceService}. Never null.
		 */
		DrugReferenceValidity getValidity() {
			return validity;
		}

		/**
		 * @return {@link #APPDATA_ORIGIN_PREFIX} + the operator file's path within the application
		 *         data directory, or {@link #CLASSPATH_ORIGIN_PREFIX} + the bundled resource when the
		 *         fallback was used, or {@link #ORIGIN_NONE} when nothing could be read. Every form
		 *         names the space it came from, which is the distinction a reader needs.
		 */
		String getOrigin() {
			return origin;
		}
	}

	private ReferenceDataFiles() {
	}

	/**
	 * @param pathGlobalProperty GP holding the operator path (relative to the app data directory)
	 * @param declaredDefaultPath the value {@code config.xml} declares as that GP's default, which is what
	 *        separates an untouched install from an operator naming a file — see
	 *        {@link DrugReferenceValidity#configuredDataFileNotRead}
	 * @param classpathDefault absolute classpath resource of the bundled dataset
	 * @param datasetLabel human label used in log lines (e.g. "drug-reference entries")
	 * @param parser the dataset's parser
	 * @return the parsed items from the operator file, else from the bundled dataset, else empty,
	 *         with the origin that produced them and the validity check this resolution ran — never
	 *         null, never an exception
	 */
	static <T> Loaded<T> loadWithClasspathFallback(String pathGlobalProperty, String declaredDefaultPath,
			String classpathDefault, String datasetLabel, DatasetParser<T> parser) {
		// Fail-safe read returns "" when unset/blank or no context is available -> classpath default.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(pathGlobalProperty, "");
		DrugReferenceValidity validity = new DrugReferenceValidity();

		if (!configuredPath.isEmpty()) {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath, pathGlobalProperty);
				// One collector spans this attempt and the fallback below, and a parser now writes to it,
				// so a finding raised against the operator's file could in principle be carried into a
				// classpath-origin load. Every parser reaching here reports strictly after its single
				// read and then returns — the curated one, the DDInter one, and since issue #266 the
				// cross-reactivity groups one — so parse() itself cannot report-then-throw. Do not read
				// that as a list to check off: what it rests on is the SHAPE, so a parser added here has
				// to keep it. What is left is the close() of the stream below throwing after a reported
				// parse — remote for a local file, and it would misattribute rather than duplicate.
				// Since ADR Decision 36 misattribution also costs the LEVEL, because
				// DrugReferenceValidity.logTo picks it from the origin this method finally returns: such
				// a finding describes the operator's file and would be logged at INFO as though it
				// described the dataset the module ships. That last cost does not arise for the groups
				// dataset, whose caller reports through the one-argument logTo and so is loud whatever
				// the origin (ADR Decision 48); the misattribution itself still would. Still not
				// guarded, and for the unchanged reason — a fresh collector per attempt would cost
				// configuredDataFileNotRead its place in the same load's findings, which is a certainty
				// against a remote maybe.
				try (InputStream in = new FileInputStream(new File(resolved))) {
					List<T> loaded = parser.parse(in, validity);
					log.info("Loaded {} {} from {}", loaded.size(), datasetLabel, resolved);
					return new Loaded<T>(loaded, APPDATA_ORIGIN_PREFIX + configuredPath, validity);
				}
			}
			catch (IllegalStateException e) {
				// File not configured/found/path-invalid -> fall back to the bundled default.
				log.info("{} file '{}' not available ({}); using bundled default",
						datasetLabel, configuredPath, e.getMessage());
			}
			catch (IOException e) {
				log.warn("Failed to read {} file '{}'; using bundled default", datasetLabel, configuredPath, e);
			}
		}

		// Reaching here at all means the configured file was not what was read, whatever the reason —
		// which is the one thing the validity rule is about, and it is deliberately asked here rather
		// than in either catch: an unreadable file, an invalid path and a path that resolves outside the
		// application data directory are one state to an operator comparing the count with their file.
		validity.configuredDataFileNotRead(pathGlobalProperty, configuredPath, declaredDefaultPath,
				CLASSPATH_ORIGIN_PREFIX + classpathDefault);

		try (InputStream in = ReferenceDataFiles.class.getResourceAsStream(classpathDefault)) {
			if (in == null) {
				log.warn("Bundled {} dataset {} not found on classpath; running empty",
						datasetLabel, classpathDefault);
				return Loaded.nothing(validity);
			}
			List<T> loaded = parser.parse(in, validity);
			log.info("Loaded {} {} from bundled default {}", loaded.size(), datasetLabel, classpathDefault);
			return new Loaded<T>(loaded, CLASSPATH_ORIGIN_PREFIX + classpathDefault, validity);
		}
		catch (IOException e) {
			log.error("Failed to parse bundled {} dataset; running empty", datasetLabel, e);
			return Loaded.nothing(validity);
		}
	}

	/**
	 * As {@link #loadWithClasspathFallback}, for a dataset the module bundles NO copy of: the operator's
	 * file or nothing. Everything else is the same contract — the same origin vocabulary, the same
	 * fail-safe (never an exception, never a refusal), and the same
	 * {@link DrugReferenceValidity#configuredDataFileNotRead} rule when a file someone CHOSE was not the
	 * one read.
	 *
	 * <p>That rule is why this method exists rather than the caller resolving its own file, which is what
	 * {@link AtcDrugReferenceSource} did until issue #266: a source that opens its own stream has no
	 * collector, and a finding it cannot raise cannot reach either of the two channels every rule here is
	 * required to reach. The origin passed to the rule is {@link #ORIGIN_NONE} rather than a fallback's
	 * name, because there is no fallback — see that rule for why the detail differs between the two.
	 *
	 * <p>One read attempt, so the sibling's misattribution window is narrower here rather than absent —
	 * stated as what remains rather than as "none", because it is not none. There is no second dataset a
	 * reported finding could be attributed to; what is left is that a {@code close()} throwing after a
	 * reported parse falls to the {@link Loaded#nothing} return below, so the parser's finding would
	 * arrive with an origin of {@link #ORIGIN_NONE} beside a file that was in fact read. Remote for a
	 * local file, and unguarded for the sibling's reason: a fresh collector per attempt would cost
	 * {@code configuredDataFileNotRead} its place in the same load's findings.
	 *
	 * @param pathGlobalProperty GP holding the operator path (relative to the app data directory)
	 * @param declaredDefaultPath the value {@code config.xml} declares as that GP's default, which is what
	 *        separates an untouched install from an operator naming a file — see
	 *        {@link DrugReferenceValidity#configuredDataFileNotRead}
	 * @param datasetLabel human label used in log lines (e.g. "ATC drug-reference entries")
	 * @param parser the dataset's parser
	 * @return the parsed items from the operator file, else empty, with the origin that produced them and
	 *         the validity check this resolution ran — never null, never an exception
	 */
	static <T> Loaded<T> loadOperatorFile(String pathGlobalProperty, String declaredDefaultPath,
			String datasetLabel, DatasetParser<T> parser) {
		// Fail-safe read returns "" when unset/blank or no context is available -> run empty.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(pathGlobalProperty, "");
		DrugReferenceValidity validity = new DrugReferenceValidity();

		if (configuredPath.isEmpty()) {
			log.info("No dataset file is configured for {} and there is no bundled fallback; running empty",
					datasetLabel);
		}
		else {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath, pathGlobalProperty);
				try (InputStream in = new FileInputStream(new File(resolved))) {
					List<T> loaded = parser.parse(in, validity);
					log.info("Loaded {} {} from {}", loaded.size(), datasetLabel, resolved);
					return new Loaded<T>(loaded, APPDATA_ORIGIN_PREFIX + configuredPath, validity);
				}
			}
			catch (IllegalStateException e) {
				log.info("{} file '{}' not available ({}); running empty", datasetLabel, configuredPath,
						e.getMessage());
			}
			catch (IOException e) {
				log.warn("Failed to read {} file '{}'; running empty", datasetLabel, configuredPath, e);
			}
		}

		// Asked here rather than in either catch, for the reason loadWithClasspathFallback gives: an
		// unreadable file, an invalid path and a path resolving outside the application data directory
		// are one state to an operator comparing the count with their file. It stays silent on a blank or
		// untouched-default path, so the branch above needs no guard of its own.
		validity.configuredDataFileNotRead(pathGlobalProperty, configuredPath, declaredDefaultPath,
				ORIGIN_NONE);
		return Loaded.nothing(validity);
	}
}
