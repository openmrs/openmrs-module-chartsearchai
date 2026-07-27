/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A serialized clinical record — its resource type, UUID, rendered text, and date.
 * The chart-record value type passed across the consumer layer
 * ({@link PatientChartSerializer}, {@code QueryStoreChartBuilder}). Lifted to a
 * top-level type in the querystore migration (#51) when its former host,
 * {@code PatientRecordLoader}, was removed.
 */
public class SerializedRecord {

	private final String resourceType;

	private final String resourceUuid;

	private final String text;

	private final Date date;

	private final List<String> categoryHints;

	/**
	 * The UUID of the obs group this record belongs to, or {@code null} if it is not a
	 * group-obs member. querystore indexes each group-obs member as an atomic document and
	 * carries the parent's UUID in metadata (ADR Decision 6: the group name is never in the
	 * stored text; consumers cluster atomic hits by this UUID). Carried here so the consumer
	 * layer can surface panel membership to the LLM.
	 */
	private final String obsGroupUuid;

	/**
	 * The preferred concept name of the obs group (e.g. {@code "Basic metabolic panel"}), or
	 * {@code null} when this record is not a group member or the parent concept has no
	 * preferred name. Used as the human-readable panel label rendered for the LLM.
	 */
	private final String obsGroupConceptName;

	/**
	 * The UUID of the concept this record codes, or {@code null} for a non-coded record (and for
	 * types querystore does not attach a concept to). It is the record's clinical IDENTITY,
	 * independent of which table it was recorded in — which is what lets the citation layer
	 * recognise that a {@code conditions} row and an {@code encounter_diagnosis} row are the same
	 * assertion recorded twice. querystore carries it in document metadata
	 * ({@code QueryStoreConstants.FIELD_CONCEPT_UUID}), never in the stored text.
	 */
	private final String conceptUuid;

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date) {
		this(resourceType, resourceUuid, text, date, Collections.<String>emptyList());
	}

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints) {
		this(resourceType, resourceUuid, text, date, categoryHints, null, null);
	}

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName) {
		this(resourceType, resourceUuid, text, date, categoryHints, obsGroupUuid,
				obsGroupConceptName, null);
	}

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName,
			String conceptUuid) {
		this.resourceType = resourceType;
		this.resourceUuid = resourceUuid;
		this.text = text;
		this.date = date;
		this.categoryHints = categoryHints != null
				? categoryHints : Collections.<String>emptyList();
		this.obsGroupUuid = obsGroupUuid;
		this.obsGroupConceptName = obsGroupConceptName;
		this.conceptUuid = conceptUuid;
	}

	public String getResourceType() {
		return resourceType;
	}

	public String getResourceUuid() {
		return resourceUuid;
	}

	public String getText() {
		return text;
	}

	public Date getDate() {
		return date;
	}

	/**
	 * @return concept-set names (or other category metadata) attached to the
	 *         record. Empty when the source concept has no containing sets, or
	 *         the record type does not support hints.
	 */
	public List<String> getCategoryHints() {
		return categoryHints;
	}

	/**
	 * @return the UUID of the obs group this record belongs to, or {@code null} if it is not a
	 *         group-obs member. This is the authoritative panel-membership flag.
	 */
	public String getObsGroupUuid() {
		return obsGroupUuid;
	}

	/**
	 * @return the preferred concept name of the obs group (the panel label), or {@code null}
	 *         when this record is not a group member or the parent concept has no preferred name.
	 */
	public String getObsGroupConceptName() {
		return obsGroupConceptName;
	}

	/**
	 * @return the UUID of the concept this record codes, or {@code null} when the record is
	 *         non-coded or its type carries no concept. The clinical identity used to recognise
	 *         the same assertion recorded in two tables.
	 */
	public String getConceptUuid() {
		return conceptUuid;
	}
}
