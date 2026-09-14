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
	 * <p>{@code null} is the answer for four different situations and they are deliberately not
	 * distinguished here, because a consumer must treat them alike: the record is not a drug order;
	 * the order read failed; the record's order could not be attributed to this patient at all; or
	 * that one order could not be evaluated, because {@code Order.isActive()} throws on a row whose
	 * stop date is after its auto-expire date. What they have in common is the only thing that
	 * matters — nothing is known, so nothing may be asserted. A chart the module could not read is not
	 * a chart of stopped prescriptions, and neither is one order it could not evaluate a stopped
	 * prescription.
	 *
	 * <p>Set only by {@code QueryStoreChartBuilder.toSerializedRecords}, which is the single funnel
	 * every chart passes through and the only place the authoritative read happens (issue #317).
	 */
	private final Boolean orderActive;

	/**
	 * When the {@code Order} this record was serialized from stopped being in force, or {@code null}
	 * where the module states no such date (issue #315).
	 *
	 * <p>It is core's own {@code Order.getEffectiveStopDate()} and nothing derived beyond it — the
	 * order's {@code dateStopped} where it has one, else its {@code autoExpireDate}. Never read off
	 * {@link #getText()}: that is {@link #orderActive}'s rule (issue #317) and it binds here for the
	 * same reason, sharpened by the fact that querystore renders no {@code auto_expire_date} into a
	 * record's TEXT — it carries it in the document's metadata — so a duration-lapsed prescription's
	 * end is in no rendered text to read.
	 *
	 * <p><strong>Non-null implies {@link #orderActive} is {@code FALSE}. {@code FALSE} does NOT
	 * imply non-null, and that asymmetry is the contract rather than a gap.</strong>
	 * {@code Order.isActive()} answers false for a voided order and for one whose action is
	 * {@code DISCONTINUE} before it consults any date, so a record can be marked not-in-force while
	 * this field states nothing. {@code null} here therefore never means "still in force" —
	 * {@link #orderActive} is the only thing that answers that question.
	 *
	 * <p><strong>Which real orders that reaches is a question two successive attempts to characterise
	 * got wrong, so what is recorded here is the measurement and not a rule about the population.</strong>
	 * Driving {@code OrderService.discontinueOrder} leaves BOTH records of the discontinuation
	 * carrying an effective stop date: the prescription through {@code dateStopped}, and the
	 * {@code DISCONTINUE} record core creates beside it through {@code autoExpireDate}. So an ordinary
	 * discontinuation is served rather than skipped — and that is asserted rather than remembered, by
	 * {@code DrugOrderCurrencyMarkTest.aRealDiscontinuationLeavesThePrescriptionCarryingItsStopDate},
	 * which drives the real service and checks both halves.
	 * {@code DrugOrder.cloneForDiscontinuing()} on its own sets neither date, and a row in that state
	 * states no date here — {@code DrugOrderCurrencyTestData.xml}'s order 9320 is one, written
	 * directly. Through {@code OrderService} it is not: `saveOrder` stamps
	 * {@code autoExpireDate = dateActivated} on every {@code DISCONTINUE} order it saves. The
	 * API-reachable instance of this cell is the VOIDED order named above.
	 *
	 * <p><strong>That paragraph is the third attempt at describing this population and the first two
	 * were measured false, so it is deliberately a measurement and a mechanism rather than a rule
	 * about which flows produce what.</strong> This is its ONE home: {@code docs/adr.md} Decision 97,
	 * {@code README.md} and the tests point here rather than restating it, because a claim that has
	 * needed correcting twice is the last one that should exist in five files.
	 *
	 * <p>Where an order does carry no date of its own, the module does not go looking for one on
	 * {@code getPreviousOrder()}: that would be a second implementation of core's discontinuation
	 * semantics, which is the re-derivation {@link #orderActive} exists to avoid.
	 *
	 * <p>Set only by {@code QueryStoreChartBuilder.toSerializedRecords}, beside
	 * {@link #orderActive} and off the same one authoritative order read.
	 */
	private final Date orderStopDate;

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
	 * The order-currency rung. Defaults {@link #orderStopDate} to {@code null} — the module states no
	 * stop date — which is the right default for every record that is not a drug order and for every
	 * caller that has not read the patient's orders.
	 */
	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName,
			Boolean orderActive) {
		this(resourceType, resourceUuid, text, date, categoryHints, obsGroupUuid, obsGroupConceptName,
				orderActive, null);
	}

	/**
	 * Full constructor, including both halves of the order read. The shorter constructors default
	 * them to {@code null} — "the module cannot say" and "the module states no stop date" — which is
	 * the right default for every record that is not a drug order and for every caller that has not
	 * read the patient's orders.
	 */
	public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
			List<String> categoryHints, String obsGroupUuid, String obsGroupConceptName,
			Boolean orderActive, Date orderStopDate) {
		this.resourceType = resourceType;
		this.resourceUuid = resourceUuid;
		this.text = text;
		this.date = date;
		this.categoryHints = categoryHints != null
				? categoryHints : Collections.<String>emptyList();
		this.obsGroupUuid = obsGroupUuid;
		this.obsGroupConceptName = obsGroupConceptName;
		this.orderActive = orderActive;
		this.orderStopDate = orderStopDate;
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

	/**
	 * @return when this record's order stopped being in force, or {@code null} where the module
	 *         states no such date. See {@link #orderStopDate} for why {@code null} is not a claim
	 *         that the order is still in force, and why the module does not derive a date it was not
	 *         given.
	 */
	public Date getOrderStopDate() {
		return orderStopDate;
	}
}
