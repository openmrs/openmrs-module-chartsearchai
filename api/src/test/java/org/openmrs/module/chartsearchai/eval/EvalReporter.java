/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.eval;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.Map;

/**
 * Appends per-case and summary metrics to {@code target/eval-results.csv} so that eval runs
 * produce a machine-readable artifact alongside the normal test output.
 * <p>
 * <b>This is a report, not a gate, and it has no consumer.</b> Nothing reads the CSV — no
 * workflow uploads it, no script parses it, and {@link #appendResult} deliberately swallows its
 * own {@link IOException} rather than failing the test that called it. So a row appearing here is
 * not evidence a case passed, and a suite that never calls this class is not thereby less gated:
 * what gates all four {@code *EvalTest} suites is their own JUnit assertions.
 * <p>
 * That distinction is the whole of #179's second item, which read the two non-reporting suites
 * ({@code AbsentDataEvalTest}, {@code DrugSafetyEvalTest}) as able to "pass or fail without
 * appearing in the report a reviewer reads". There is no such report. Wiring them in would add
 * rows to a file with no reader; the CSV says as much in its own first line so the inference is
 * harder to draw a second time.
 */
public final class EvalReporter {

	private static final String CSV_PATH = "target/eval-results.csv";

	/**
	 * Written above the header, because a file that looks like a results artifact gets cited like
	 * one. Nothing parses this file, so the extra line costs nothing. ASCII only: the writes below
	 * go through {@link FileWriter}, which encodes with the platform default charset.
	 */
	private static final String NOT_A_GATE_NOTE = "# informational only - nothing reads this file; "
			+ "a row here is not a pass. The gate is the suite's JUnit assertions (#179).";

	private static final String HEADER = "timestamp,suite,caseId,metric_name,metric_value";

	private EvalReporter() {
	}

	/**
	 * Appends one line per metric entry for the given eval case.
	 *
	 * @param suite   the eval suite name (e.g. "citation", "retrieval")
	 * @param caseId  the individual case identifier
	 * @param metrics map of metric name to metric value
	 */
	public static void appendResult(String suite, String caseId, Map<String, Object> metrics) {
		ensureFileWithHeader();
		String timestamp = Instant.now().toString();
		try (PrintWriter pw = new PrintWriter(new FileWriter(CSV_PATH, true))) {
			for (Map.Entry<String, Object> entry : metrics.entrySet()) {
				pw.println(escapeCsv(timestamp) + ","
						+ escapeCsv(suite) + ","
						+ escapeCsv(caseId) + ","
						+ escapeCsv(entry.getKey()) + ","
						+ escapeCsv(String.valueOf(entry.getValue())));
			}
		}
		catch (IOException e) {
			// Best-effort reporting; do not fail the test
			System.err.println("EvalReporter: failed to write result: " + e.getMessage());
		}
	}

	/**
	 * Appends summary-level metrics for the given suite. The caseId column
	 * is set to {@code _summary}.
	 *
	 * @param suite          the eval suite name
	 * @param summaryMetrics map of metric name to metric value
	 */
	public static void appendSummary(String suite, Map<String, Object> summaryMetrics) {
		appendResult(suite, "_summary", summaryMetrics);
	}

	private static void ensureFileWithHeader() {
		File file = new File(CSV_PATH);
		if (!file.exists()) {
			file.getParentFile().mkdirs();
			try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
				pw.println(NOT_A_GATE_NOTE);
				pw.println(HEADER);
			}
			catch (IOException e) {
				System.err.println("EvalReporter: failed to create CSV file: " + e.getMessage());
			}
		}
	}

	private static String escapeCsv(String value) {
		if (value == null) {
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}
}
