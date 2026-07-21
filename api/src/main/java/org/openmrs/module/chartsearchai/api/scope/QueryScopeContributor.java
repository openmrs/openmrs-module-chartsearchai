/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.scope;

import java.util.Set;

/**
 * SPI by which any OpenMRS module extends query-scope routing for the query-scoped chart mode
 * ({@code chartsearchai.chartMode=queryScoped}). A contributor claims the querystore
 * {@code QueryDocument.resourceType} values that must appear <em>complete</em> in the scoped slice
 * for questions it recognizes — the same "complete by construction" guarantee the built-in intents
 * (medications, allergies, programs, conditions, visits, orders) give, but for a domain the core
 * router does not know (e.g. billing, appointments).
 *
 * <h3>How it is wired</h3>
 * Implement this as a Spring bean in your module's {@code moduleApplicationContext.xml}. OpenMRS
 * modules share one application context, so chartsearchai collects every {@code QueryScopeContributor}
 * bean automatically — no code change in chartsearchai, and no hard dependency: declare chartsearchai
 * as an {@code aware_of_module} (soft) dependency and compile against {@code chartsearchai-api}.
 * chartsearchai works with zero contributors (the built-in behaviour is unchanged); each contributor's
 * claim is <strong>unioned</strong> on top of the built-in typed scope, so a contributor can only add
 * its own domain's records to the slice — it can never perturb another domain's routing.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li><b>Return an empty set when the question is not in your domain</b> — the common case. Match on
 *       conservative, unambiguous cues (word boundaries, not substrings). A wrong claim biases the
 *       slice with irrelevant records; a missed claim only falls back to similarity-only retrieval.
 *       <b>Prefer to under-claim.</b></li>
 *   <li>The returned strings MUST be resourceType values that <b>querystore actually indexes</b> for
 *       your domain. querystore owns retrieval and indexing; a type it does not index contributes
 *       nothing to the slice, so first-class support for a new domain requires both a querystore
 *       indexing extension <em>and</em> a contributor here.</li>
 *   <li>Must not throw for expected input; chartsearchai defends against it (a throwing contributor is
 *       skipped and logged, never breaking the answer path), but a throwing contributor silently
 *       forfeits its scope.</li>
 *   <li>A contributor changes slice composition, which is gated in this project on BOTH the scope
 *       eval and the temporal probe (they pull in opposite directions — see ADR Decision 28). Ship
 *       your contributor with its own adjudicated eval cells; an unvalidated contributor can silently
 *       regress its domain's answer quality.</li>
 *   <li><b>Called on the hot path, concurrently.</b> {@link #scopedResourceTypes} runs synchronously
 *       during chart assembly on every scoped query, from multiple request threads at once. Make it
 *       thread-safe (ideally a pure function of the question — a word-boundary keyword match) and
 *       cheap: do no I/O, database, or network calls here, or every scoped answer pays the cost.</li>
 * </ul>
 */
public interface QueryScopeContributor {

	/**
	 * The querystore resourceTypes to include complete in the scoped slice for {@code question}, or
	 * an empty set (never {@code null}) when the question is outside this contributor's domain.
	 *
	 * @param question the raw clinician question (may be null or blank; return empty for those)
	 * @return an immutable set of querystore resourceType values, empty when not applicable
	 */
	Set<String> scopedResourceTypes(String question);

	/**
	 * A short domain label for logging/telemetry (e.g. {@code "billing"}). Defaults to the
	 * implementing class's simple name.
	 */
	default String getDomainName() {
		return getClass().getSimpleName();
	}
}
