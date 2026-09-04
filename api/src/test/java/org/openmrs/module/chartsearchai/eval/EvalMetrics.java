/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class for computing evaluation metrics: precision, recall, F1 score,
 * and exact match. Used by all eval test suites.
 */
public final class EvalMetrics {

	private EvalMetrics() {
	}

	public static double precision(List<Integer> predicted, List<Integer> expected) {
		if (predicted.isEmpty()) {
			return expected.isEmpty() ? 1.0 : 0.0;
		}
		Set<Integer> expectedSet = new HashSet<>(expected);
		int hits = 0;
		for (Integer p : predicted) {
			if (expectedSet.contains(p)) {
				hits++;
			}
		}
		return (double) hits / predicted.size();
	}

	public static double recall(List<Integer> predicted, List<Integer> expected) {
		if (expected.isEmpty()) {
			return 1.0;
		}
		Set<Integer> predictedSet = new HashSet<>(predicted);
		int hits = 0;
		for (Integer e : expected) {
			if (predictedSet.contains(e)) {
				hits++;
			}
		}
		return (double) hits / expected.size();
	}

	public static double f1(List<Integer> predicted, List<Integer> expected) {
		double p = precision(predicted, expected);
		double r = recall(predicted, expected);
		if (p + r == 0) {
			return 0.0;
		}
		return 2 * p * r / (p + r);
	}

	public static boolean exactMatch(List<Integer> predicted, List<Integer> expected) {
		return new HashSet<>(predicted).equals(new HashSet<>(expected));
	}

	/**
	 * Summary of an eval run across multiple cases.
	 */
	public static class EvalSummary {

		private final int totalCases;

		private final int exactMatches;

		private final double avgPrecision;

		private final double avgRecall;

		private final double avgF1;

		public EvalSummary(int totalCases, int exactMatches, double avgPrecision,
				double avgRecall, double avgF1) {
			this.totalCases = totalCases;
			this.exactMatches = exactMatches;
			this.avgPrecision = avgPrecision;
			this.avgRecall = avgRecall;
			this.avgF1 = avgF1;
		}

		public int getTotalCases() {
			return totalCases;
		}

		public int getExactMatches() {
			return exactMatches;
		}

		public double getAvgPrecision() {
			return avgPrecision;
		}

		public double getAvgRecall() {
			return avgRecall;
		}

		public double getAvgF1() {
			return avgF1;
		}

		@Override
		public String toString() {
			return String.format("EvalSummary{cases=%d, exactMatch=%d/%d (%.0f%%), "
					+ "avgP=%.3f, avgR=%.3f, avgF1=%.3f}",
					totalCases, exactMatches, totalCases,
					totalCases > 0 ? 100.0 * exactMatches / totalCases : 0,
					avgPrecision, avgRecall, avgF1);
		}
	}
}
