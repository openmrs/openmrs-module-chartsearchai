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

/**
 * Builds a {@link SafetyWarning} through a factory {@code SafetyWarning} keeps package-private, for
 * an omod test that cannot reach one from {@code org.openmrs.module.chartsearchai.web.rest}.
 *
 * <p><b>Why it exists, and why it is not a widening of production API.</b> The chip-serialization
 * guards live in {@code web.rest}, and the facts a chip carries beyond its four public constructor
 * arguments are set only by {@code SafetyWarning}'s package-private factories — "a caller may set
 * only what it may read back", which that class states of each of them. Issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/347">#347</a> met exactly
 * this and answered it by making a constructor PUBLIC, recording the necessity in its javadoc ("the
 * test that pins their serialization lives in {@code web.rest} and cannot reach {@code interaction(..)}
 * below"). Issue #374 needs the same thing for
 * {@code SafetyWarning.restsOnAnUncorroboratedChartMatch()} and does not repeat that answer: this
 * class is declared in {@code SafetyWarning}'s OWN package under {@code omod/src/test}, so it reaches
 * the real factory with no production change at all. A split package across two artifacts is legal on
 * a plain classpath, which is what surefire gives these tests.
 *
 * <p><b>The point is that the chip is one PRODUCTION built.</b> Two alternatives were available — a
 * new public factory taking the flag, and an anonymous subclass overriding the accessor — and both are
 * weaker. ADR Decision 92 records the comparison and is canonical for it.
 *
 * <p>Deliberately NOT a general-purpose chip builder: it exposes the one shape a wire guard needs, so
 * it cannot become a second way to assemble the chips {@code DrugSafetyValidator} assembles.
 */
public final class SafetyWarningFixtures {

	private SafetyWarningFixtures() {
	}

	/**
	 * A contraindication chip whose chart match nothing corroborates — the shape issue #374 is about,
	 * built by {@code SafetyWarning.contraindication}, the curated-rule arm's own factory.
	 *
	 * <p>{@code aboutACurrentMedication} is false and {@code chartRecords} empty because neither is
	 * what this shape is for, and the caller is not offered them: a wire fixture states the fact under
	 * test and takes the arm's defaults for the rest.
	 */
	public static SafetyWarning uncorroboratedContraindication(String drug, String detail) {
		return SafetyWarning.contraindication(drug, detail, true, false,
			Collections.<String> emptySet());
	}
}
