/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Every audit and conversation column Hibernate maps must be declared in the packaged changelog.
 * The persistence suite uses Hibernate-created H2 tables, so it cannot detect a missing migration
 * column. This check compares column names only; it does not execute migrations or verify column
 * types, constraints, or changeset checksums. Unmapped legacy columns are allowed.
 */
public class AuditLogSchemaMappingTest {

	@Test
	public void everyAuditColumnIsDeclared() throws Exception {
		assertColumnsDeclared("chartsearchai_audit_log", "ChartSearchAuditLog.hbm.xml");
	}

	@Test
	public void everyConversationColumnIsDeclared() throws Exception {
		assertColumnsDeclared("chartsearchai_conversation", "ClinicalConversation.hbm.xml");
	}

	@Test
	public void everyTurnColumnIsDeclared() throws Exception {
		assertColumnsDeclared("chartsearchai_conversation_turn", "ClinicalConversationTurn.hbm.xml");
	}

	private void assertColumnsDeclared(String table, String mapping) throws Exception {
		Set<String> mapped = mappedColumns(table, mapping);
		Set<String> declared = declaredColumns(table);

		assertFalse(mapped.isEmpty(), "read no mappings for " + table + " from " + mapping);
		assertFalse(declared.isEmpty(), "read no changelog columns for " + table);
		Set<String> missing = new TreeSet<String>(mapped);
		missing.removeAll(declared);
		assertTrue(missing.isEmpty(), mapping + " maps undeclared columns on " + table + ": " + missing
				+ ". Add a new changeset; do not change an already applied migration.");
	}

	/** Column names the Hibernate mapping binds — the id, the properties and the
	 *  many-to-one foreign keys alike, since each names a column the schema must have. */
	private Set<String> mappedColumns(String table, String mapping) throws Exception {
		Element clazz = null;
		NodeList classes = parse(mapping).getElementsByTagName("class");
		for (int i = 0; i < classes.getLength(); i++) {
			Element candidate = (Element) classes.item(i);
			if (table.equals(candidate.getAttribute("table"))) {
				clazz = candidate;
			}
		}
		assertNotNull(clazz, "no <class table=\"" + table + "\"> in " + mapping);

		Set<String> columns = new LinkedHashSet<String>();
		for (String tag : new String[] { "id", "property", "many-to-one" }) {
			NodeList nodes = clazz.getElementsByTagName(tag);
			for (int i = 0; i < nodes.getLength(); i++) {
				String column = ((Element) nodes.item(i)).getAttribute("column");
				if (!column.isEmpty()) {
					columns.add(column);
				}
			}
		}
		return columns;
	}

	/** Column names created or added to this table by any packaged changeset. */
	private Set<String> declaredColumns(String table) throws Exception {
		Set<String> columns = new LinkedHashSet<String>();
		NodeList all = parse("liquibase.xml").getElementsByTagName("*");
		for (int i = 0; i < all.getLength(); i++) {
			Element element = (Element) all.item(i);
			String name = element.getTagName();
			if (!("createTable".equals(name) || "addColumn".equals(name))
					|| !table.equals(element.getAttribute("tableName"))) {
				continue;
			}
			NodeList declarations = element.getElementsByTagName("column");
			for (int j = 0; j < declarations.getLength(); j++) {
				String column = ((Element) declarations.item(j)).getAttribute("name");
				if (!column.isEmpty()) {
					columns.add(column);
				}
			}
		}
		return columns;
	}

	/**
	 * Both files off the CLASSPATH rather than off a source path, so this reads what is packaged.
	 * External DTD resolution is disabled: the hbm declares the hibernate.org DOCTYPE, and a test that
	 * reaches the network to parse it fails offline for a reason that has nothing to do with schema.
	 */
	private Document parse(String resource) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(false);
		factory.setValidating(false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		builder.setEntityResolver((publicId, systemId) ->
				new InputSource(new ByteArrayInputStream(new byte[0])));
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is not on the test classpath");
			return builder.parse(in);
		}
	}
}
