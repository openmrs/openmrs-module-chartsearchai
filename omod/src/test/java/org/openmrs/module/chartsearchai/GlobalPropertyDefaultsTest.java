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

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The global-property defaults a constant asserts and {@code config.xml} declares have to be the same
 * string, because the load-time validity check decides whether to be LOUD by comparing the configured
 * value with the declared default: an untouched default is the normal state of every install and must be
 * silent, while an operator naming a file that was then not read is issue #156's defect and must not be.
 *
 * <p>Two paths, one string each, and the module never creates either file — so a drift between the two
 * declarations makes every install loud (if the constant goes stale) or every misconfiguration silent (if
 * {@code config.xml} does), and both failures are invisible: the load still works, and the count still
 * looks healthy. That is the same class of silent-and-plausible failure the check exists to find, one
 * level up, which is why it is pinned rather than left to review.
 *
 * <p>{@code config.xml} is read as the omod's own resource, so this reads what ships.
 */
public class GlobalPropertyDefaultsTest {

	@Test
	public void everyDefaultTheValidityCheckComparesAgainstIsTheOneConfigXmlDeclares() throws Exception {
		Map<String, String> declared = declaredDefaults();

		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH,
				declared.get(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH),
				"DrugReferenceValidity treats this value as 'the operator changed nothing', so a drift "
						+ "here makes every install warn about its own default");
		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
				declared.get(ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH),
				"and the groups dataset is the case that was confirmed live on issue #156: the module "
						+ "never creates this file, so every untouched install falls back");
		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				declared.get(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT),
				"the format default is the same comparison, and is also what an unrecognised value "
						+ "falls back to");
	}

	/**
	 * A BEHAVIOUR flag whose two declarations must agree, which is a different reason from the three
	 * above rather than a fourth instance of theirs. Those are compared by the validity check to
	 * decide whether to be loud; this one decides what an install RENDERS. The constant is what
	 * {@code getBooleanGlobalProperty} falls back to where no property is set — every contextless test
	 * in this build — while {@code config.xml}'s value is what OpenMRS writes into a real install. Let
	 * them drift and the api suite pins one rendering while every install ships the other, with
	 * nothing failing: exactly the silent-and-plausible shape this class exists for.
	 *
	 * <p>Written as a comparison of the STRINGS both sides publish rather than of a parsed boolean, so
	 * a {@code config.xml} value that is neither {@code true} nor {@code false} fails here rather than
	 * being quietly read as off.
	 */
	@Test
	public void theRecordNumberFlagShipsTheDefaultItsConstantAsserts() throws Exception {
		assertEquals(String.valueOf(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_CITE_ORDER_RECORDS),
				declaredDefaults().get(ChartSearchAiConstants.GP_DRUG_SAFETY_CITE_ORDER_RECORDS),
				"issue #379's record number is off until an install asks for it, and the constant every "
						+ "contextless test falls back to has to say the same thing config.xml ships");
	}

	/** @return every {@code <property>} in {@code config.xml} that declares a {@code <defaultValue>}. */
	private static Map<String, String> declaredDefaults() throws Exception {
		Map<String, String> defaults = new LinkedHashMap<String, String>();
		try (InputStream in = GlobalPropertyDefaultsTest.class.getResourceAsStream("/config.xml")) {
			assertNotNull(in, "config.xml should be on the omod classpath");
			NodeList properties = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in)
					.getElementsByTagName("globalProperty");
			for (int i = 0; i < properties.getLength(); i++) {
				Element property = (Element) properties.item(i);
				NodeList name = property.getElementsByTagName("property");
				NodeList value = property.getElementsByTagName("defaultValue");
				if (name.getLength() > 0 && value.getLength() > 0) {
					defaults.put(name.item(0).getTextContent().trim(),
							value.item(0).getTextContent().trim());
				}
			}
		}
		return defaults;
	}
}
