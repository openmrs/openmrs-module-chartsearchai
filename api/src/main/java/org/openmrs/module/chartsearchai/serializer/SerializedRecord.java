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
	 * Whether the {@code Order} this record was serialized from is in force right now —
	 * {@code TRUE} when it is in the patient's active-order set, {@code FALSE} when the module read
	 * that set and this record's order was not in it, and {@code null} when the module cannot say.
	 *
	 * <p>{@code null} is the answer for three different situations and they are deliberately not
	 * distinguished here, because a consumer must treat all three alike: the record is not a drug
	 * order, the order read failed, or the record's order could not be attributed to this patient at
	 * all. What they have in common is the only thing that matters — nothing is known, so nothing
	 * may be asserted. A chart the module could not read is not a chart of stopped prescriptions.
	 *
	 * <p>Set only by {@code QueryStoreChartBuilder.toSerializedRecords}, which is the single funnel
	 * every chart passes through and the only place the authoritative read happens (issue #317).
	 */
	private final Boolean orderActive;

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date) {
		this(resourceType, resourceUuid, text, date, Collections.<String>emptyList());
	}

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints) {
		this(resourceType, resourceUuid, text, date, categoryHints, null, null);
	}

	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName) {
		this(resourceType, resourceUuid, text, date, categoryHints, obsGroupUuid, obsGroupConceptName, null);
	}

	/**
	 * Full constructor, including the order-currency answer. The shorter constructors default it to
	 * {@code null} — "the module cannot say" — which is the right default for every record that is
	 * not a drug order and for every caller that has not read the patient's orders.
	 */
	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName,
			Boolean orderActive) {
		this.resourceType = resourceType;
		this.resourceUuid = resourceUuid;
		this.text = text;
		this.date = date;
		this.categoryHints = categoryHints != null
				? categoryHints : Collections.<String>emptyList();
		this.obsGroupUuid = obsGroupUuid;
		this.obsGroupConceptName = obsGroupConceptName;
		this.orderActive = orderActive;
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
	 * @return {@code TRUE} when {@code Order.isActive()} holds for this record's order, {@code FALSE}
	 *         when the module read that order and it does not, {@code null} when the module cannot
	 *         say. See {@link #orderActive} for why the {@code null} cases are one answer.
	 */
	public Boolean getOrderActive() {
		return orderActive;
	}
}
