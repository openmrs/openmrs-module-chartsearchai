/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.transform.stream.StreamResult;

import org.springframework.oxm.xstream.XStreamMarshaller;

/**
 * What an XML client receives of a {@code /search} payload — whether it survives the converter an XML
 * client gets, the one shared assertion for that, and the XML it marshals to — plus the field/getter
 * guard for a value class that reaches that client, which is the same client's question.
 *
 * <p><b>The measurement lives at {@code ChartSearchAiRestController.serializeSafetyWarnings}</b>,
 * beside the publish site a maintainer would be editing when they need it. In outline:
 * {@code XStreamMarshaller} is the converter openmrs-core selects for {@code Accept:
 * application/xml} on a {@code Map} body, and it refuses {@code java.util.Collections}' immutable
 * wrappers — so issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/347">#347</a> shipped a
 * key publishing one as handed and every chip-carrying XML response became a 500, the empty case
 * included. Publish a copy, not the accessor's list.
 *
 * <p><b>Why it is here rather than in each test that needs it.</b> Two files grew a byte-identical
 * copy of this assertion, message and all, which is how the {@code (issue #347)} claim inside it
 * would come to be maintained in two places and drift. {@code SseEvents}' javadoc records the same
 * lesson about a decoder two test classes had each grown their own copy of.
 */
final class XmlPayloads {

	private XmlPayloads() {
	}

	/**
	 * Fails unless {@code payload} marshals to XML.
	 *
	 * @param payload the response body a handler produced, unchanged
	 * @param what names the arrangement, so a failure says which shape of a key broke it rather than
	 *            only that some request would 500
	 */
	static void assertMarshals(Map<String, Object> payload, String what) throws Exception {
		marshal(payload, what);
	}

	/**
	 * @return {@code payload} as the XML an XML client receives, failing as {@link #assertMarshals} does
	 *         where it does not marshal
	 */
	static String marshal(Map<String, Object> payload, String what) throws Exception {
		XStreamMarshaller marshaller = new XStreamMarshaller();
		marshaller.afterPropertiesSet();
		StringWriter xml = new StringWriter();
		try {
			marshaller.marshal(payload, new StreamResult(xml));
		}
		catch (Exception e) {
			throw new AssertionError("the /search payload must marshal to XML for " + what
					+ " — an XStreamMarshaller is the converter openmrs-core selects for "
					+ "Accept: application/xml, and it cannot marshal Collections' immutable wrappers "
					+ "(issue #347). Publish a copy, not the accessor's list. Cause: " + e, e);
		}
		return xml.toString();
	}

	/**
	 * Fails unless every instance field of {@code type} has a public getter of its own name. XStream marshals
	 * a class's FIELDS and Jackson reads its GETTERS, so a field without one reaches an XML client and no
	 * JSON client (issue #347). Static and synthetic members are excluded — {@code static} because XStream
	 * does not marshal one, and synthetic because a compiler writes {@code this$0} and jacoco writes
	 * {@code $jacocoData} into classes that never declared them.
	 */
	static void assertEveryFieldHasAPublicGetterOfItsName(Class<?> type) {
		List<String> problems = new ArrayList<String>();
		for (Field f : type.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
				continue;
			}
			String getter = "get" + Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
			Method m;
			try {
				m = type.getDeclaredMethod(getter);
			}
			catch (NoSuchMethodException absent) {
				problems.add("field '" + f.getName() + "' has no " + getter + "()");
				continue;
			}
			if (!Modifier.isPublic(m.getModifiers())) {
				problems.add("field '" + f.getName() + "' has " + getter + "() but it is not public");
			}
		}
		if (!problems.isEmpty()) {
			throw new AssertionError("XStream marshals " + type.getSimpleName() + "'s FIELDS and Jackson reads "
					+ "its GETTERS, so every field needs a public getter of its own name or an XML client "
					+ "receives something a JSON client never sees (issue #347): " + problems);
		}
	}
}
