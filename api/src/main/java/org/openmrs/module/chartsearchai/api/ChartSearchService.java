/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.Date;
import java.util.List;

/**
 * Namespace for {@link ChartAnswer} and {@link RecordReference} — the wire-shape data types
 * chartsearchai's chat persistence layer ({@code ChatServiceImpl}) and REST layer share. Chat
 * itself is a thin relay to the hub, which owns retrieval and answer generation; these types
 * just carry the hub's answer back through the module's persistence and JSON surfaces.
 */
public interface ChartSearchService {

	/**
	 * An answer to a chart search question with source citations.
	 */
	class ChartAnswer {

		private final String answer;

		private final List<RecordReference> references;

		private final List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> blocks;

		/**
		 * Per-section validator confidence ({@code {answer:{level,note}, in_depth:{level,note}}})
		 * the med-agent-hub emits; opaque pass-through metadata the SPA renders as a tag.
		 * {@code null} for backends that don't emit it.
		 */
		private final java.util.Map<String, Object> confidence;

		/**
		 * Staged answer review lifecycle metadata ({@code {status,label,summary,issues,...}})
		 * emitted by the med-agent-hub answer-review leg; opaque pass-through metadata the SPA
		 * renders as the clinician-facing check badge. {@code null} for legacy/synchronous rows.
		 */
		private final java.util.Map<String, Object> answerValidation;

		private final int inputTokens;

		private final int outputTokens;

		private final int cachedTokens;

		private final List<java.util.Map<String, Object>> safetyWarnings;

		public ChartAnswer(String answer, List<RecordReference> references) {
			this(answer, references, java.util.Collections.emptyList(), 0, 0, 0);
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				int inputTokens, int outputTokens) {
			this(answer, references, java.util.Collections.emptyList(), inputTokens, outputTokens, 0);
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				int inputTokens, int outputTokens, int cachedTokens) {
			this(answer, references, inputTokens, outputTokens, cachedTokens,
					java.util.Collections.<java.util.Map<String, Object>> emptyList());
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				int inputTokens, int outputTokens, int cachedTokens,
				List<java.util.Map<String, Object>> safetyWarnings) {
			this(answer, references, java.util.Collections.emptyList(), null, null,
					inputTokens, outputTokens, cachedTokens, safetyWarnings);
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> blocks,
				int inputTokens, int outputTokens, int cachedTokens) {
			this(answer, references, blocks, null, inputTokens, outputTokens, cachedTokens);
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> blocks,
				java.util.Map<String, Object> confidence,
				int inputTokens, int outputTokens, int cachedTokens) {
			this(answer, references, blocks, confidence, null,
					inputTokens, outputTokens, cachedTokens);
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> blocks,
				java.util.Map<String, Object> confidence,
				java.util.Map<String, Object> answerValidation,
				int inputTokens, int outputTokens, int cachedTokens) {
			this(answer, references, blocks, confidence, answerValidation,
					inputTokens, outputTokens, cachedTokens,
					java.util.Collections.<java.util.Map<String, Object>> emptyList());
		}

		public ChartAnswer(String answer, List<RecordReference> references,
				List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> blocks,
				java.util.Map<String, Object> confidence,
				java.util.Map<String, Object> answerValidation,
				int inputTokens, int outputTokens, int cachedTokens,
				List<java.util.Map<String, Object>> safetyWarnings) {
			this.answer = answer;
			this.references = java.util.Collections.unmodifiableList(
					new java.util.ArrayList<>(references));
			this.blocks = blocks == null
					? java.util.Collections.emptyList()
					: java.util.Collections.unmodifiableList(new java.util.ArrayList<>(blocks));
			this.confidence = confidence;
			this.answerValidation = answerValidation;
			this.inputTokens = inputTokens;
			this.outputTokens = outputTokens;
			this.cachedTokens = cachedTokens;
			this.safetyWarnings = safetyWarnings == null
					? java.util.Collections.emptyList()
					: java.util.Collections.unmodifiableList(new java.util.ArrayList<>(safetyWarnings));
		}

		/**
		 * The response text, which may contain numbered citation labels in brackets
		 * (e.g. [1], [3]).
		 */
		public String getAnswer() {
			return answer;
		}

		/**
		 * The ordered list of record references cited in the answer.
		 */
		public List<RecordReference> getReferences() {
			return references;
		}

		/**
		 * Structured non-prose blocks (tables today; lists/timelines/etc. later)
		 * the LLM chose to emit. Empty when the answer is prose-only.
		 */
		public List<org.openmrs.module.chartsearchai.api.impl.ResponseBlock> getBlocks() {
			return blocks;
		}

		/**
		 * Per-section validator confidence ({@code {answer:{level,note}, in_depth:{level,note}}}),
		 * or {@code null} when the backend didn't emit it.
		 */
		public java.util.Map<String, Object> getConfidence() {
			return confidence;
		}

		/**
		 * Answer review lifecycle metadata, or {@code null} when no staged review has run.
		 */
		public java.util.Map<String, Object> getAnswerValidation() {
			return answerValidation;
		}

		/**
		 * The number of input (prompt) tokens used.
		 */
		public int getInputTokens() {
			return inputTokens;
		}

		/**
		 * The number of output (completion) tokens used.
		 */
		public int getOutputTokens() {
			return outputTokens;
		}

		/**
		 * The number of input tokens that the configured serving backend reports as
		 * cached instead of processed fresh.
		 */
		public int getCachedTokens() {
			return cachedTokens;
		}

		/**
		 * Non-blocking drug-safety advisories (overdose / interaction / contraindication) the hub
		 * raises via its own deterministic post-answer check, passed through verbatim as
		 * {@code {type, drug, detail}} maps. Empty when the level's drug-safety check is off or
		 * nothing was flagged. These annotate the answer; they never alter it.
		 */
		public List<java.util.Map<String, Object>> getSafetyWarnings() {
			return safetyWarnings;
		}
	}

	/**
	 * Identifies a source record in OpenMRS cited by the LLM answer.
	 */
	class RecordReference {

		private final int index;

		private final String resourceType;

		private final String resourceUuid;

		private final Date date;

		private final Boolean grounded;

		public RecordReference(int index, String resourceType, String resourceUuid, Date date) {
			this(index, resourceType, resourceUuid, date, null);
		}

		public RecordReference(int index, String resourceType, String resourceUuid, Date date, Boolean grounded) {
			this.index = index;
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.date = date;
			this.grounded = grounded;
		}

		public int getIndex() {
			return index;
		}

		public String getResourceType() {
			return resourceType;
		}

		public String getResourceUuid() {
			return resourceUuid;
		}

		public Date getDate() {
			return date;
		}

		/**
		 * Whether the cited record was found to actually support the answer
		 * sentence(s) that cite it. {@code TRUE}/{@code FALSE} when the configured
		 * hub/model endpoint returned a grounding verdict; {@code null} when no
		 * verdict was returned or the check could not run. A {@code null} verdict
		 * must be rendered as "unverified", never as "verified".
		 */
		public Boolean getGrounded() {
			return grounded;
		}

		/**
		 * @return a copy of this reference carrying the given grounding verdict
		 */
		public RecordReference withGrounded(Boolean verdict) {
			return new RecordReference(index, resourceType, resourceUuid, date, verdict);
		}
	}
}
