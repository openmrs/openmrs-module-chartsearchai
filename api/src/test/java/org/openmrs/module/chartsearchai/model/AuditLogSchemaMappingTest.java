/*
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
 * Every column {@code ChartSearchAuditLog.hbm.xml} maps is a column {@code liquibase.xml} declares.
 *
 * <p><b>Why this exists.</b> Nothing in either suite read {@code liquibase.xml} before this — the
 * schema the api tests exercise is the one Hibernate derives from the mapping, so deleting the whole
 * {@code chartsearchai-009} changeset leaves {@code HibernateChartSearchAiDAOTest} green.
 * That test pins the ENTITY against the MAPPING; this one pins the mapping against the CHANGELOG.
 * Without the second, a column name that disagrees between the two ships as a column Hibernate
 * declares and the database lacks: every audit insert then fails,
 * {@code ChartSearchAiRestController.saveAuditLog} catches and logs it, and auditing stops with
 * nothing red anywhere. That is the failure {@code chartsearchai-009}'s own comment describes, and
 * the durable channel issue #229's observable travels on.
 *
 * <p><b>What it does NOT reach</b>, named rather than left to be discovered. It compares NAMES, so a
 * column declared with the wrong type still passes. It reads the changelog as text and never runs it,
 * so it says nothing about whether a changeset EXECUTES on a given instance — an id colliding with an
 * orphan {@code liquibasechangelog} row is invisible here, and the file's header carries that rule
 * for a reader instead. And it is one-directional by design: the changelog may declare columns the
 * mapping does not use, which is not a defect.
 *
 * <p>Both sides must be non-empty, because a parse that silently found nothing would satisfy a
 * subset assertion by containing nothing — the shape where a walking guard passes having scanned no
 * files at all.
 */
public class AuditLogSchemaMappingTest {

	private static final String TABLE = "chartsearchai_audit_log";

	@Test
	public void everyColumnTheMappingDeclaresIsCreatedByTheChangelog() throws Exception {
		Set<String> mapped = mappedColumns();
		Set<String> declared = declaredColumns();

		assertFalse(mapped.isEmpty(), "read no column mappings for " + TABLE
				+ " out of ChartSearchAuditLog.hbm.xml; a guard that reads nothing passes vacuously");
		assertFalse(declared.isEmpty(), "read no column declarations for " + TABLE
				+ " out of liquibase.xml; a guard that reads nothing passes vacuously");

		Set<String> missing = new TreeSet<String>(mapped);
		missing.removeAll(declared);
		assertTrue(missing.isEmpty(), "ChartSearchAuditLog.hbm.xml maps " + missing + " on " + TABLE
				+ ", which no changeset in liquibase.xml creates. Hibernate declares the column, the "
				+ "database lacks it, every audit insert fails and the controller swallows it. Add a NEW "
				+ "changeset — liquibase.xml's header says why editing an existing one does not work. "
				+ "Mapped: " + new TreeSet<String>(mapped) + "; declared: " + new TreeSet<String>(declared));
	}

	/** Column names the Hibernate mapping binds for {@link #TABLE} — the id, the properties and the
	 *  many-to-one foreign keys alike, since each names a column the schema must have. */
	private Set<String> mappedColumns() throws Exception {
		Element clazz = null;
		NodeList classes = parse("ChartSearchAuditLog.hbm.xml").getElementsByTagName("class");
		for (int i = 0; i < classes.getLength(); i++) {
			Element candidate = (Element) classes.item(i);
			if (TABLE.equals(candidate.getAttribute("table"))) {
				clazz = candidate;
			}
		}
		assertNotNull(clazz, "no <class table=\"" + TABLE + "\"> in ChartSearchAuditLog.hbm.xml");

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

	/** Column names any changeset creates or adds on {@link #TABLE}. Both element shapes count — 002
	 *  creates the table and 009 adds to it, and what a reader of the schema needs is only that the
	 *  column ends up there. */
	private Set<String> declaredColumns() throws Exception {
		Set<String> columns = new LinkedHashSet<String>();
		NodeList all = parse("liquibase.xml").getElementsByTagName("*");
		for (int i = 0; i < all.getLength(); i++) {
			Element element = (Element) all.item(i);
			String name = element.getTagName();
			if (!("createTable".equals(name) || "addColumn".equals(name))
					|| !TABLE.equals(element.getAttribute("tableName"))) {
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
