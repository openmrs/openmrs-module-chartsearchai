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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Data-driven eval for the post-LLM drug-safety validator. Loads
 * {@code evals/drug-reference/drug-safety-eval.json} and runs each case through the
 * real {@link DrugSafetyValidator} over the real bundled dataset. Model-free — it
 * exercises the deterministic post-check, so it runs in CI without the LLM. Its own
 * small POJO parses the dataset, leaving the shared {@code EvalCase}/{@code EvalDataset}
 * harness untouched.
 */
public class DrugSafetyEvalTest {

	private static final String DATASET = "evals/drug-reference/drug-safety-eval.json";

	private static Dataset dataset;

	private static Dataset dataset() {
		if (dataset == null) {
			try (InputStream in = DrugSafetyEvalTest.class.getClassLoader().getResourceAsStream(DATASET)) {
				if (in == null) {
					throw new IllegalStateException("Eval dataset not found: " + DATASET);
				}
				dataset = new ObjectMapper().readValue(in, Dataset.class);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return dataset;
	}

	static Stream<Arguments> cases() {
		List<Arguments> args = new ArrayList<Arguments>();
		for (EvalCase c : dataset().cases) {
			args.add(Arguments.of(c.id, c));
		}
		return args.stream();
	}

	@Test
	public void datasetLoads() {
		assertTrue(dataset().cases != null && !dataset().cases.isEmpty(), "eval dataset should have cases");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("cases")
	public void drugSafety_perCase(String id, EvalCase c) {
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(new DrugReferenceService());

		PatientClinicalContext ctx = new PatientClinicalContext(c.ageYears,
				toSet(c.activeDrugs), Collections.<String> emptySet(),
				toSet(c.allergies), toSet(c.conditions));

		List<SafetyWarning> warnings = validator.validate(c.answer, c.question, ctx);
		Set<String> types = new HashSet<String>();
		for (SafetyWarning w : warnings) {
			types.add(w.getType());
		}

		Set<String> expected = toSet(c.expectWarningTypes);
		for (String type : expected) {
			assertTrue(types.contains(type),
					id + ": expected a '" + type + "' warning but got " + types);
		}
		if (expected.isEmpty()) {
			assertTrue(warnings.isEmpty(), id + ": expected no warnings but got " + warnings);
		}
	}

	private static Set<String> toSet(List<String> values) {
		return values == null ? Collections.<String> emptySet() : new HashSet<String>(values);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	static class Dataset {

		public List<EvalCase> cases;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	static class EvalCase {

		public String id;

		public String question;

		public String answer;

		public Integer ageYears;

		public List<String> activeDrugs;

		public List<String> allergies;

		public List<String> conditions;

		public List<String> expectWarningTypes;
	}
}
