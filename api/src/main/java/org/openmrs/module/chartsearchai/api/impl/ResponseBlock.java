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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured "block" the LLM can emit alongside its prose answer — currently
 * just tables. The schema is defined in {@link ChartAnswerResponseFormat};
 * the parser ({@link LlmAnswerExtractor}) reads these out of the JSON
 * response into {@link LlmProvider.LlmResponse#getBlocks()}.
 *
 * <p>Single concrete type today is {@code table}; the {@code kind}
 * discriminator gives us room to add {@code list}, {@code timeline},
 * {@code code} etc. without breaking the wire format.
 */
public final class ResponseBlock {

	public static final String KIND_TABLE = "table";

	private final String kind;

	private final String title;

	private final List<Column> columns;

	private final List<Row> rows;

	public ResponseBlock(String kind, String title, List<Column> columns, List<Row> rows) {
		this.kind = kind;
		this.title = title;
		this.columns = columns == null ? Collections.emptyList() : Collections.unmodifiableList(columns);
		this.rows = rows == null ? Collections.emptyList() : Collections.unmodifiableList(rows);
	}

	public String getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public List<Column> getColumns() {
		return columns;
	}

	public List<Row> getRows() {
		return rows;
	}

	public static final class Column {

		private final String key;

		private final String label;

		public Column(String key, String label) {
			this.key = key;
			this.label = label;
		}

		public String getKey() {
			return key;
		}

		public String getLabel() {
			return label;
		}
	}

	public static final class Row {

		private final Map<String, Cell> cells;

		public Row(Map<String, Cell> cells) {
			this.cells = cells == null
					? Collections.emptyMap()
					: Collections.unmodifiableMap(new LinkedHashMap<>(cells));
		}

		public Map<String, Cell> getCells() {
			return cells;
		}
	}

	public static final class Cell {

		private final String text;

		private final List<Integer> refs;

		public Cell(String text, List<Integer> refs) {
			this.text = text;
			this.refs = refs == null ? Collections.emptyList() : Collections.unmodifiableList(refs);
		}

		public String getText() {
			return text;
		}

		public List<Integer> getRefs() {
			return refs;
		}
	}
}
