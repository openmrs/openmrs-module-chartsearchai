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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled task that backfills embedding indexes for patients who have not
 * yet been indexed. This handles the initial population when the module is
 * installed on a system with existing patient data. Once a patient is indexed,
 * incremental updates are handled by the {@link EncounterIndexingAdvice} AOP
 * listener on encounter saves.
 *
 * <p>Skips patients that already have embeddings. Processes in batches of 50
 * and flushes the Hibernate session between batches to limit memory usage.
 * Automatically disables itself once all patients are indexed.</p>
 */
public class EmbeddingIndexTask extends AbstractTask {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexTask.class);

	private static final int BATCH_SIZE = 50;

	private static final AtomicBoolean running = new AtomicBoolean(false);

	@Override
	public void execute() {
		if (!running.compareAndSet(false, true)) {
			log.info("Embedding backfill already running, skipping this execution");
			return;
		}
		try {
			doExecute();
		}
		finally {
			running.set(false);
		}
	}

	private void doExecute() {
		String searchMode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE, ChartSearchAiConstants.SEARCH_MODE_LLM);

		if (!ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equals(searchMode)) {
			log.info("Search mode is '{}', skipping embedding backfill", searchMode);
			return;
		}

		ChartSearchAiDAO dao = Context.getRegisteredComponent("hibernateChartSearchAiDAO", ChartSearchAiDAO.class);
		EmbeddingIndexer indexer = Context.getRegisteredComponent("embeddingIndexer", EmbeddingIndexer.class);

		Set<Integer> alreadyIndexed = new HashSet<Integer>(dao.getIndexedPatientIds());
		log.info("Starting embedding backfill ({} patients already indexed)", alreadyIndexed.size());

		int totalIndexed = 0;
		int totalSkipped = 0;
		int totalFailed = 0;
		int offset = 0;

		List<Patient> batch = Context.getPatientService().getPatients("", offset, BATCH_SIZE);
		while (!batch.isEmpty()) {
			for (Patient patient : batch) {
				if (!isExecuting()) {
					log.info("Embedding backfill stopped: {} indexed, {} skipped", totalIndexed, totalSkipped);
					return;
				}

				if (alreadyIndexed.contains(patient.getPatientId())) {
					totalSkipped++;
					continue;
				}

				try {
					indexer.indexPatient(patient);
					totalIndexed++;
				}
				catch (Exception e) {
					totalFailed++;
					log.error("Failed to index patient [id={}]: {}", patient.getPatientId(), e.getMessage());
				}

				if (totalIndexed % 100 == 0 && totalIndexed > 0) {
					log.info("Embedding backfill progress: {} indexed, {} skipped", totalIndexed, totalSkipped);
				}
			}

			Context.flushSession();
			Context.clearSession();

			offset += BATCH_SIZE;
			batch = Context.getPatientService().getPatients("", offset, BATCH_SIZE);
		}

		log.info("Embedding backfill completed: {} indexed, {} skipped, {} failed",
				totalIndexed, totalSkipped, totalFailed);
	}
}
