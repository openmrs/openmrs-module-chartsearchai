/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pins the contract that the grounding feature's three global properties are
 * registered in the module's {@code config.xml} AND that their declared
 * {@code defaultValue}s stay in sync with the code-side defaults in
 * {@link ChartSearchAiConstants}.
 *
 * <p>This matters because a registered global property's stored row wins over
 * the code default at runtime: once OpenMRS seeds the row from config.xml, the
 * production readers in {@code ChartSearchAiUtils} return that row's value, not
 * the constant. The {@code minCosine} Javadoc states the floor must be retuned
 * per embedding model, so the constant is a likely future edit — if it changes
 * without config.xml following, every installed deployment silently keeps the
 * old floor (no exception, just wrong grounding), which this test prevents.
 */
public class ConfigXmlGroundingPropertiesTest {

	private static Map<String, String> defaultsByProperty;

	@BeforeAll
	public static void parseConfigXml() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		// config.xml carries no DOCTYPE; harden against any external entity fetch.
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		DocumentBuilder builder = factory.newDocumentBuilder();

		defaultsByProperty = new HashMap<>();
		try (InputStream in = ConfigXmlGroundingPropertiesTest.class.getResourceAsStream("/config.xml")) {
			assertNotNull(in, "config.xml must be on the classpath (built into the module)");
			NodeList gps = builder.parse(in).getElementsByTagName("globalProperty");
			for (int i = 0; i < gps.getLength(); i++) {
				Element gp = (Element) gps.item(i);
				String property = textOfChild(gp, "property");
				String defaultValue = textOfChild(gp, "defaultValue");
				if (property != null) {
					defaultsByProperty.put(property, defaultValue);
				}
			}
		}
	}

	private static String textOfChild(Element parent, String tag) {
		NodeList children = parent.getElementsByTagName(tag);
		if (children.getLength() == 0) {
			return null;
		}
		Node node = children.item(0);
		String text = node.getTextContent();
		return text == null ? "" : text.trim();
	}

	@Test
	public void configXml_registersGroundingEnabledWithMatchingDefault() {
		assertTrue(defaultsByProperty.containsKey(ChartSearchAiConstants.GP_GROUNDING_ENABLED),
				ChartSearchAiConstants.GP_GROUNDING_ENABLED + " must be registered in config.xml");
		assertEquals(String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ENABLED),
				defaultsByProperty.get(ChartSearchAiConstants.GP_GROUNDING_ENABLED),
				"config.xml defaultValue must match DEFAULT_GROUNDING_ENABLED");
	}

	@Test
	public void configXml_registersGroundingEntailmentEnabledWithMatchingDefault() {
		assertTrue(defaultsByProperty.containsKey(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED),
				ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED + " must be registered in config.xml");
		assertEquals(String.valueOf(ChartSearchAiConstants.DEFAULT_GROUNDING_ENTAILMENT_ENABLED),
				defaultsByProperty.get(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED),
				"config.xml defaultValue must match DEFAULT_GROUNDING_ENTAILMENT_ENABLED");
	}

	@Test
	public void configXml_registersGroundingMinCosineWithMatchingDefault() {
		assertTrue(defaultsByProperty.containsKey(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE),
				ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE + " must be registered in config.xml");
		String declared = defaultsByProperty.get(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE);
		assertNotNull(declared, "minCosine defaultValue must be present");
		// Parse exactly as production does (ChartSearchAiUtils.getGroundingMinCosine).
		assertEquals(ChartSearchAiConstants.DEFAULT_GROUNDING_MIN_COSINE, Double.parseDouble(declared), 1e-9,
				"config.xml defaultValue must match DEFAULT_GROUNDING_MIN_COSINE");
	}
}
