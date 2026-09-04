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
import java.util.Map;

import javax.xml.transform.stream.StreamResult;

import org.springframework.oxm.xstream.XStreamMarshaller;

/**
 * Whether a {@code /search} payload survives the converter an XML client gets — the one shared
 * assertion for it.
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
		XStreamMarshaller marshaller = new XStreamMarshaller();
		marshaller.afterPropertiesSet();
		try {
			marshaller.marshal(payload, new StreamResult(new StringWriter()));
		}
		catch (Exception e) {
			throw new AssertionError("the /search payload must marshal to XML for " + what
					+ " — an XStreamMarshaller is the converter openmrs-core selects for "
					+ "Accept: application/xml, and it cannot marshal Collections' immutable wrappers "
					+ "(issue #347). Publish a copy, not the accessor's list. Cause: " + e, e);
		}
	}
}
