/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.CautionLedOverWithholding;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports an answer whose CAUTION LEAD says a drug can be given beside a finding about that drug stating a
 * reason to withhold it — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>, ADR Decision 119.
 *
 * <p><b>The failure.</b> <em>"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to
 * give Amlodipine?"</em> came back from the default model as <em>"Amlodipine can be given, with one
 * caution: …"</em> while the prompt carried Amlodipine × rifampin, a Major finding against an order the
 * question's list left out, ending <em>"This finding is a reason to withhold it."</em>. Asked without the
 * list, the same model refused amlodipine on it. No key compared the lead with that finding.
 *
 * <p><b>What it reads.</b> The lead through {@link DrugSafetyValidator#cautionLead}, which is canonical
 * for the lead class and what its anchor refuses. The findings are the chart's injected
 * {@code safety_finding} records ({@link ChartSearchAiUtils#safetyFindingMappings}), cited or not: a record
 * carries the citation a client joins and the rating it states, and the records exist before the answer,
 * so the one report reaches the early {@code done} too — the chips do not, being raised after it. A record
 * is reported where {@link RecordMapping#getFindingWithholds()} is {@code TRUE} and
 * {@link DrugSafetyValidator#namesTheFindingsSubject} says the lead's drug is the substance of
 * {@link RecordMapping#getFindingSubjectRows()}. Neither is re-derived here, from the text or anywhere else.
 *
 * <p><b>What it cannot see</b>, stated so that {@code []} is not read as a certificate:
 * <ul>
 *   <li>any lead the anchor refuses — among them issue #513 item 2's recorded E2B shapes, a refusal and a
 *       "No" before a permission;</li>
 *   <li>a drug only the ANSWER put in play, which has chips and no record;</li>
 *   <li>a finding whose SUBJECT is another drug and whose partner is the lead's — a question-pair finding
 *       is raised once per pair, about one of its two drugs;</li>
 *   <li>a finding the screening arm or an order-driven INTERACTION arm raised, which states no subject
 *       rows; an order-driven contraindication does, through the step every contraindication passes.</li>
 * </ul>
 *
 * <p>It reports the citation and the rating, never a word of the answer or of a record, and never that the
 * answer is wrong. It never rewrites the answer.
 */
final class CautionLeadOverWithholdingCheck {

	private static final Logger log = LoggerFactory.getLogger(CautionLeadOverWithholdingCheck.class);

	private CautionLeadOverWithholdingCheck() {
	}

	/**
	 * @return the withholding findings about the drug {@code answer}'s caution lead gives, in chart order;
	 *         empty where the answer opens on no caution lead or the chart carries no such finding; and
	 *         {@code null} — no measurement — where no validator is wired or the check throws
	 */
	static List<CautionLedOverWithholding> report(Patient patient, String answer, List<RecordMapping> mappings,
			DrugSafetyValidator validator) {
		if (validator == null) {
			return null;
		}
		try {
			List<CautionLedOverWithholding> reported = new ArrayList<CautionLedOverWithholding>();
			List<RecordMapping> findings = new ArrayList<RecordMapping>();
			for (RecordMapping finding : ChartSearchAiUtils.safetyFindingMappings(mappings)) {
				if (Boolean.TRUE.equals(finding.getFindingWithholds())) {
					findings.add(finding);
				}
			}
			// Where no finding withholds there is nothing to report, and the lead is not read at all.
			if (findings.isEmpty()) {
				return reported;
			}
			DrugSafetyValidator.CautionLead lead = validator.cautionLead(answer);
			if (lead == null) {
				return reported;
			}
			for (RecordMapping finding : findings) {
				if (validator.namesTheFindingsSubject(lead, finding.getFindingSubjectRows())) {
					reported.add(new CautionLedOverWithholding(finding.getIndex(), finding.getFindingSeverity()));
				}
			}
			if (!reported.isEmpty()) {
				log.warn("Caution lead beside a withholding finding about the drug it gives (issue #515) "
						+ "patient={} findings={}", patient == null ? null : patient.getPatientId(), reported);
			}
			return reported;
		}
		catch (RuntimeException e) {
			log.warn("Caution-lead check failed; stating no measurement", e);
			return null;
		}
	}
}
