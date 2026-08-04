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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The one set of drug-reference test helpers, shared by the reference test classes (and, via
 * the one public accessor, the grounding tests in {@code api.impl}) so the
 * arrangement/matcher bodies cannot drift between files (the same rule CLAUDE.md states for
 * {@code TestDatasetHelper}). Everything here constructs REAL production objects and calls
 * real production paths — no mocks, no pipeline reimplementation; the individual test files
 * keep thin, file-shaped delegates so their call sites read naturally.
 */
public final class DrugReferenceTestSupport {

	/**
	 * The real rendered text of the drug-reference record the REAL injector injects for
	 * {@code question} (bundled DDInter sample, real load → parse → injectRecords → render
	 * chain). The one cross-package accessor for tests that need genuine injected record text
	 * without reimplementing the renderer.
	 */
	public static String injectedDdinterReferenceText(String question) {
		PatientChart chart = injector(ddinterService()).injectRecords(oneRecordChart(),
				ctx(60, null, null, null, null, null), question);
		return chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(m.getResourceType()))
				.map(RecordMapping::getText).findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"no drug-reference record was injected for question: " + question));
	}

	/**
	 * The real record mappings the REAL injector produces for {@code question} (bundled DDInter
	 * sample, real load → parse → injectRecords chain), for tests outside this package that need
	 * genuine injected mappings — including their citation metadata (source, withheld count) —
	 * rather than hand-built stand-ins.
	 */
	public static List<RecordMapping> injectedDdinterMappings(String question) {
		return injector(ddinterService()).injectRecords(oneRecordChart(),
				ctx(60, null, null, null, null, null), question).getMappings();
	}

	/** The real WHO ATC sample fixture (parsed by the real {@link AtcDrugReferenceSource#parse}). */
	static final String ATC_SAMPLE = "atc/atc-sample.tsv";

	private DrugReferenceTestSupport() {
	}

	static Set<String> set(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** Canonical context builder: any null set means empty; weight null means unknown. */
	static PatientClinicalContext ctx(Integer age, Double weightKg, Set<String> drugs, Set<String> atc,
			Set<String> allergies, Set<String> conditions) {
		return new PatientClinicalContext(age, weightKg,
				drugs == null ? Collections.<String> emptySet() : drugs,
				atc == null ? Collections.<String> emptySet() : atc,
				allergies == null ? Collections.<String> emptySet() : allergies,
				conditions == null ? Collections.<String> emptySet() : conditions);
	}

	/** A service over the real bundled datasets (classpath fallback — the production default path). */
	static DrugReferenceService bundledService() {
		return new DrugReferenceService();
	}

	/** A service over the real bundled DDInter sample, parsed by the real {@link DdiDrugReferenceSource}. */
	static DrugReferenceService ddinterService() {
		return serviceWith(new DdiDrugReferenceSource().load());
	}

	/** A service pinned to the given entries (groups pinned empty by the {@code setEntries} seam). */
	static DrugReferenceService serviceWith(List<DrugReference> entries) {
		DrugReferenceService svc = new DrugReferenceService();
		svc.setEntries(entries);
		return svc;
	}

	/**
	 * A service over the real WHO ATC sample (parsed by the real source), optionally with the
	 * real bundled cross-reactivity groups; without them the dataset is hermetically
	 * classification-only (what the ADR-24 boundary tests assert).
	 */
	static DrugReferenceService atcService(boolean withGroups) throws IOException {
		DrugReferenceService svc = new DrugReferenceService();
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader().getResourceAsStream(ATC_SAMPLE)) {
			assertNotNull(in, "ATC sample resource should be on the test classpath");
			svc.setEntries(AtcDrugReferenceSource.parse(in));
		}
		if (withGroups) {
			svc.setCrossReactivityGroups(bundledGroups());
		}
		return svc;
	}

	/** The real bundled cross-reactivity groups via the production loader (classpath fallback). */
	static List<CrossReactivityGroup> bundledGroups() {
		return new CrossReactivityGroupsLoader().load();
	}

	/** Entries parsed from a test-classpath dataset by the real production parser. */
	static List<DrugReference> fixtureEntries(String classpathResource) throws IOException {
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the test classpath");
			return JsonDrugReferenceSource.parse(in);
		}
	}

	static DrugSafetyValidator validator(DrugReferenceService service) {
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);
		return validator;
	}

	static DrugReferenceInjector injector(DrugReferenceService service) {
		DrugReferenceInjector injector = new DrugReferenceInjector();
		injector.setDrugReferenceService(service);
		return injector;
	}

	/** A one-record chart to inject into; the injected reference must append as record [2]. */
	static PatientChart oneRecordChart() {
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();
		mappings.add(new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
				"obs-uuid-1", null, "BP 120/80"));
		return new PatientChart("Patient\n\n[1] BP 120/80\n",
				Collections.unmodifiableList(mappings), Collections.<Integer> emptyList());
	}

	static boolean has(List<SafetyWarning> warnings, String type, String drugContains) {
		for (SafetyWarning w : warnings) {
			if (w.getType().equals(type) && w.getDrug().toLowerCase().contains(drugContains.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	static boolean detailContains(List<SafetyWarning> warnings, String type, String drug, String... needles) {
		for (SafetyWarning w : warnings) {
			if (!w.getType().equals(type) || !w.getDrug().equalsIgnoreCase(drug)) {
				continue;
			}
			boolean all = true;
			for (String needle : needles) {
				if (!w.getDetail().toLowerCase().contains(needle.toLowerCase())) {
					all = false;
					break;
				}
			}
			if (all) {
				return true;
			}
		}
		return false;
	}

	static long overdoseCount(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_OVERDOSE)
						&& w.getDrug().equalsIgnoreCase(drug))
				.count();
	}

	static String overdoseDetail(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_OVERDOSE)
						&& w.getDrug().equalsIgnoreCase(drug))
				.map(SafetyWarning::getDetail).findFirst().orElse("");
	}
}
