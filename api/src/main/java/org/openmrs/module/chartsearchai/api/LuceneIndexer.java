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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Indexes patient clinical records into an Apache Lucene index for BM25-based
 * retrieval. Alternative to the embedding-based pipeline — selected via the
 * {@code chartsearchai.retrieval.pipeline} global property.
 *
 * <p>Uses a single shared index directory with a {@code patient_id} field for
 * per-patient filtering. Each document stores the resource type, resource ID,
 * and the prefixed text (same content the embedding pipeline indexes).
 */
@Component
public class LuceneIndexer implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);

	static final String FIELD_PATIENT_ID = "patient_id";

	static final String FIELD_RESOURCE_TYPE = "resource_type";

	static final String FIELD_RESOURCE_UUID = "resource_uuid";

	static final String FIELD_TEXT = "text";

	@Autowired
	private PatientRecordLoader recordLoader;

	private volatile Directory directory;

	private volatile IndexWriter writer;

	private final Object lock = new Object();

	/**
	 * Returns the index directory, creating it on first access.
	 */
	Directory getDirectory() throws IOException {
		if (directory == null) {
			synchronized (lock) {
				if (directory == null) {
					File indexDir = new File(
							OpenmrsUtil.getApplicationDataDirectory(),
							"chartsearchai" + File.separator + "lucene-index");
					if (!indexDir.exists()) {
						indexDir.mkdirs();
					}
					directory = FSDirectory.open(indexDir.toPath());
				}
			}
		}
		return directory;
	}

	/**
	 * Allows tests to inject an in-memory directory.
	 */
	void setDirectory(Directory directory) {
		this.directory = directory;
	}

	private IndexWriter getWriter() throws IOException {
		if (writer == null || !writer.isOpen()) {
			synchronized (lock) {
				if (writer == null || !writer.isOpen()) {
					IndexWriterConfig config = new IndexWriterConfig(newAnalyzer());
					config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
					writer = new IndexWriter(getDirectory(), config);
				}
			}
		}
		return writer;
	}

	/**
	 * Full index of a patient's chart. Deletes existing documents for the
	 * patient and re-indexes all clinical data.
	 */
	public void indexPatient(Patient patient) {
		log.info("Lucene: indexing patient [id={}]", patient.getPatientId());
		List<SerializedRecord> records = recordLoader.loadAll(patient);
		synchronized (lock) {
			try {
				IndexWriter w = getWriter();
				// Delete existing documents for this patient
				w.deleteDocuments(IntPoint.newExactQuery(
						FIELD_PATIENT_ID, patient.getPatientId()));

				for (SerializedRecord record : records) {
					Document doc = buildDocument(patient, record);
					w.addDocument(doc);
				}
				w.commit();
				log.info("Lucene: indexed {} records for patient [id={}]",
						records.size(), patient.getPatientId());
			}
			catch (IOException e) {
				log.error("Lucene: failed to index patient [id={}]",
						patient.getPatientId(), e);
				// Roll back uncommitted deletes and partial adds so that a
				// subsequent commit (from another indexPatient call) does not
				// apply this patient's partial state.
				try {
					if (writer != null && writer.isOpen()) {
						writer.rollback();
					}
					writer = null;
				}
				catch (IOException rollbackEx) {
					log.error("Lucene: rollback also failed for patient [id={}]",
							patient.getPatientId(), rollbackEx);
				}
			}
		}
	}

	/**
	 * Deletes all Lucene documents for a patient.
	 */
	public void deletePatientIndex(Patient patient) {
		try {
			IndexWriter w = getWriter();
			w.deleteDocuments(IntPoint.newExactQuery(
					FIELD_PATIENT_ID, patient.getPatientId()));
			w.commit();
		}
		catch (IOException e) {
			log.error("Lucene: failed to delete index for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	/**
	 * Searches the Lucene index for records matching the query, filtered to
	 * a specific patient. Returns results ordered by BM25 relevance score.
	 *
	 * @param patient the patient to filter by
	 * @param queryText the user's question (will be parsed by Lucene)
	 * @param maxResults maximum number of results to return
	 * @return matching results with resource type, ID, and BM25 score
	 */
	public List<LuceneSearchResult> search(Patient patient, String queryText,
			int maxResults) {
		List<LuceneSearchResult> results = new ArrayList<LuceneSearchResult>();
		try {
			Directory dir = getDirectory();
			if (!DirectoryReader.indexExists(dir)) {
				return results;
			}
			DirectoryReader reader = DirectoryReader.open(dir);
			try {
				IndexSearcher searcher = new IndexSearcher(reader);
				Analyzer analyzer = newAnalyzer();
				try {
					QueryParser parser = new QueryParser(FIELD_TEXT, analyzer);
					parser.setDefaultOperator(QueryParser.Operator.OR);
					parser.setAllowLeadingWildcard(false);

					// Sanitize the query text: remove Lucene special characters
					// but keep words intact so the analyzer can tokenize them.
					String sanitized = queryText.replaceAll("[+\\-!(){}\\[\\]^\"~*?:\\\\/]", " ")
							.trim();
					if (sanitized.isEmpty()) {
						return results;
					}
					Query textQuery = parser.parse(sanitized);
					Query patientFilter = IntPoint.newExactQuery(
							FIELD_PATIENT_ID, patient.getPatientId());

					BooleanQuery combined = new BooleanQuery.Builder()
							.add(patientFilter, BooleanClause.Occur.FILTER)
							.add(textQuery, BooleanClause.Occur.MUST)
							.build();

					TopDocs topDocs = searcher.search(combined, maxResults);
					for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
						Document doc = searcher.doc(scoreDoc.doc);
						results.add(new LuceneSearchResult(
								doc.get(FIELD_RESOURCE_TYPE),
								doc.get(FIELD_RESOURCE_UUID),
								scoreDoc.score));
					}
				}
				finally {
					analyzer.close();
				}
			}
			finally {
				reader.close();
			}
		}
		catch (Exception e) {
			log.error("Lucene: search failed for patient [id={}] query '{}'",
					patient.getPatientId(), queryText, e);
		}
		return results;
	}

	/**
	 * Returns true if the Lucene index has any documents for the given patient.
	 */
	public boolean hasIndex(Patient patient) {
		try {
			Directory dir = getDirectory();
			if (!DirectoryReader.indexExists(dir)) {
				return false;
			}
			DirectoryReader reader = DirectoryReader.open(dir);
			try {
				IndexSearcher searcher = new IndexSearcher(reader);
				TopDocs topDocs = searcher.search(IntPoint.newExactQuery(
						FIELD_PATIENT_ID, patient.getPatientId()), 1);
				return topDocs.totalHits.value > 0;
			}
			finally {
				reader.close();
			}
		}
		catch (IOException e) {
			log.error("Lucene: failed to check index for patient [id={}]",
					patient.getPatientId(), e);
			return false;
		}
	}

	/**
	 * Re-indexes the patient if the current retrieval pipeline uses a Lucene
	 * index and the patient already has indexed data. Called by AOP advice
	 * classes after patient data changes.
	 */
	public void reindexIfActive(Patient patient) {
		if (patient == null) {
			return;
		}
		String pipeline = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE, "");
		if (!ChartSearchAiUtils.usesLuceneIndex(pipeline)) {
			return;
		}
		if (hasIndex(patient)) {
			indexPatient(patient);
		}
	}

	/**
	 * Deletes the patient's Lucene index if the current retrieval pipeline
	 * uses a Lucene index. Called by AOP advice after patient merges.
	 */
	public void deleteIfActive(Patient patient) {
		if (patient == null) {
			return;
		}
		String pipeline = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE, "");
		if (!ChartSearchAiUtils.usesLuceneIndex(pipeline)) {
			return;
		}
		deletePatientIndex(patient);
	}

	@Override
	public void close() throws IOException {
		synchronized (lock) {
			if (writer != null && writer.isOpen()) {
				writer.close();
				writer = null;
			}
			if (directory != null) {
				directory.close();
				directory = null;
			}
		}
	}

	/**
	 * Creates the analyzer used for both indexing and searching. Uses
	 * {@link EnglishAnalyzer} which includes Porter stemming so that
	 * "conditions" matches "condition", "allergies" matches "allergy", etc.
	 */
	static Analyzer newAnalyzer() {
		return new EnglishAnalyzer();
	}

	private Document buildDocument(Patient patient, SerializedRecord record) {
		Document doc = new Document();
		doc.add(new IntPoint(FIELD_PATIENT_ID, patient.getPatientId()));
		doc.add(new StringField(FIELD_RESOURCE_TYPE,
				record.getResourceType(), Field.Store.YES));
		doc.add(new StringField(FIELD_RESOURCE_UUID,
				record.getResourceUuid(), Field.Store.YES));
		// Index the prefixed text so Lucene gets the same type signals
		// as the embedding pipeline (e.g. "Medical condition: Condition: ...")
		// Includes any category hints (e.g. concept-set names) so keyword
		// queries like "vital signs" can match records whose serialized text
		// does not contain the category word literally.
		String prefixedText = ChartSearchAiUtils.buildPrefixedText(
				record.getResourceType(), record.getText(),
				record.getCategoryHints());
		doc.add(new TextField(FIELD_TEXT, prefixedText, Field.Store.NO));
		return doc;
	}

	/**
	 * A single Lucene search result with resource metadata and BM25 score.
	 */
	public static class LuceneSearchResult {

		private final String resourceType;

		private final String resourceUuid;

		private final float score;

		public LuceneSearchResult(String resourceType, String resourceUuid, float score) {
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.score = score;
		}

		public String getResourceType() {
			return resourceType;
		}

		public String getResourceUuid() {
			return resourceUuid;
		}

		public float getScore() {
			return score;
		}
	}
}
