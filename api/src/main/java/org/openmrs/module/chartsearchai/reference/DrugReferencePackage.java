/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable identity and review state for the drug-reference data in force. */
public final class DrugReferencePackage {

	public static final String REVIEW_PROPOSED = "proposed";

	public static final String REVIEW_EVIDENCE_CURATED = "evidence_curated";

	public static final String REVIEW_CLINICALLY_APPROVED = "clinically_approved";

	public static final String REVIEW_RETIRED = "retired";

	private final String id;

	private final String sourceFormat;

	private final String version;

	private final Map<String, Object> provenance;

	private final String reviewState;

	private final List<String> issues;

	public DrugReferencePackage(String id, String sourceFormat, String version,
			Map<String, Object> provenance, String reviewState) {
		this(id, sourceFormat, version, provenance, reviewState,
				Collections.<String> emptyList());
	}

	public DrugReferencePackage(String id, String sourceFormat, String version,
			Map<String, Object> provenance, String reviewState, List<String> issues) {
		this.id = id;
		this.sourceFormat = sourceFormat;
		this.version = version;
		this.provenance = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(
				provenance == null ? Collections.<String, Object> emptyMap() : provenance));
		this.reviewState = normalizeReviewState(reviewState);
		this.issues = Collections.unmodifiableList(new ArrayList<String>(
				issues == null ? Collections.<String> emptyList() : issues));
	}

	static DrugReferencePackage proposed(String sourceFormat, String origin) {
		Map<String, Object> provenance = new LinkedHashMap<String, Object>();
		provenance.put("origin", origin);
		String packageId;
		if ("atc".equals(sourceFormat)) {
			packageId = "configured-atc-classification";
		}
		else if ("ddinter".equals(sourceFormat)) {
			packageId = "openmrs-ddi-knowledge-base-unreviewed";
		}
		else {
			packageId = "chartsearchai-research-seed-v1";
		}
		return new DrugReferencePackage(packageId, sourceFormat, null, provenance, REVIEW_PROPOSED);
	}

	static DrugReferencePackage notLoaded() {
		return new DrugReferencePackage(null, null, null,
				Collections.<String, Object> emptyMap(), REVIEW_PROPOSED);
	}

	private static String normalizeReviewState(String value) {
		if (REVIEW_EVIDENCE_CURATED.equals(value) || REVIEW_CLINICALLY_APPROVED.equals(value)
				|| REVIEW_RETIRED.equals(value)) {
			return value;
		}
		return REVIEW_PROPOSED;
	}

	public String getReviewState() {
		return reviewState;
	}

	public boolean isClinicallyApproved() {
		return REVIEW_CLINICALLY_APPROVED.equals(reviewState);
	}

	public boolean isRetired() {
		return REVIEW_RETIRED.equals(reviewState);
	}

	public List<String> getIssues() {
		return issues;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("id", id);
		map.put("source_format", sourceFormat);
		map.put("version", version);
		map.put("provenance", provenance);
		map.put("review_state", reviewState);
		map.put("issues", issues);
		return map;
	}
}
