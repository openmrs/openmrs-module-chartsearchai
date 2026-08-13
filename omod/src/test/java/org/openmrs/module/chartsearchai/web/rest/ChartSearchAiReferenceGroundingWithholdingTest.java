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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire contract for the grounding verdict of a {@code reference}-group citation (issue #201):
 * the {@code grounded} key is always present and always {@code null}, at every emission site,
 * whatever verdict the pipeline attached internally.
 *
 * <p><strong>Why the value is withheld rather than published.</strong> Grounding treats
 * module-supplied material as demote-only — a pass renders {@code null}, a Tier-1 off-topic
 * citation still renders {@code false} (#106, #122). That {@code false} is reachable by design, and
 * on the wire it is a value a client must not interpret: the reference frontend classifies
 * citations by {@code resourceType} rather than by {@code group}, so a {@code safety_finding}
 * falls through to its grounding branch and renders "Unsupported — The cited record may not
 * support this statement", in red, on this module's own deterministic Major-interaction finding.
 * A field that must not be interpreted is a trap, so the wire stops offering one. The off-topic
 * signal is not lost to the module — the verifier still computes it and
 * {@code CitationGroundingVerifierTest.safetyFinding_offTopicCitationIsStillFlagged} still pins it —
 * it stops being published to clients that have no correct reading of it.
 *
 * <p>{@code null} rather than an absent key, because {@code null} is already this field's documented
 * value for "grounding disabled or could not run", clients are already told to render it as
 * unverified and never as verified, and the key being unconditionally present is a property clients
 * rely on. Dropping the key would invent a third state for no gain.
 *
 * <p><strong>How this test resists the regression it exists to prevent.</strong> The forbidden shape
 * is a hardcoded list of type names — testing {@code resourceType} against {@code drug_reference} is
 * exactly how {@code safety_finding} was graded as chart evidence for two releases (#122), and
 * re-hardcoding such a carve-out is invisible to a behavioural suite until a third
 * {@code reference}-group type exists. So the withholding is pinned twice, in two different ways:
 *
 * <ol>
 * <li><em>Behaviourally, off an enumeration rather than a literal.</em> The fixture cites EVERY
 * declared {@code RESOURCE_TYPE_*} constant twice — once carrying {@code TRUE}, once carrying
 * {@code FALSE} — and the expectation for each is derived by asking
 * {@link ChartSearchAiUtils#isGroundingDemoteOnly}. No type name appears in the expectation, so a
 * newly declared reference-group constant is swept automatically, and a serializer that agreed with
 * today's two names would fail the moment one is added. This is the same forcing function
 * {@code ChartSearchAiReferenceGroupTest} applies to the classification itself.</li>
 * <li><em>Structurally, so the hardcode fails TODAY.</em>
 * {@link #theWireSerializerMustNotNameAReferenceGroupResourceType()} reads the controller's own
 * compiled class file and asserts it contains no reference-group type name. Those constants are
 * compile-time {@code String} constants, so javac inlines them into the constant pool of any class
 * that uses one — which makes "this class does not name a resource type" a checkable property, and
 * makes it fail on the mutation rather than three releases after it.</li>
 * </ol>
 *
 * <p>The chart-group half is asserted just as tightly, and is what proves the change NARROWED the
 * wire rather than blanking it: every chart citation's verdict must still arrive, with the value the
 * service attached.
 *
 * <p>Driven through the real controller and its real serialization over real
 * {@code RecordReference} objects, at all four {@code serializeReferences} call sites — the blocking
 * {@code /search} response, the early {@code references} event, {@code done}, and the trailing
 * {@code grounded} event of the async path. They share one serializer today; each is asserted
 * separately anyway, because "they share it" is the thing a client is entitled to have checked.
 */
public class ChartSearchAiReferenceGroundingWithholdingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Every declared {@code RESOURCE_TYPE_*} constant, in declaration order. Read by reflection so a
	 * type added later is swept without anyone remembering this file.
	 */
	private static final List<String> RESOURCE_TYPES = declaredResourceTypes();

	/** The verdict the stub service attached to each citation index. Never null — see the fixture. */
	private static final Map<Integer, Boolean> ATTACHED_VERDICTS = new LinkedHashMap<Integer, Boolean>();

	/** The fixture's citations: each declared resource type twice, at TRUE and at FALSE. */
	private static final List<ChartSearchService.RecordReference> CITATIONS = buildCitations();

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new EveryResourceTypeStubService());
		// resolvePatient consults this on the blocking path; production autowires it. Allow the
		// access so that handler reaches the serialization step under test.
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
	}

	@Test
	public void searchResponse_withholdsTheVerdictFromEveryReferenceGroupCitation() {
		// The OpenMRS static context is installed HERE rather than in setUp, and torn down whatever
		// happens, because the three SSE assertions below must keep running with no context at all:
		// that absence is the only thing enforcing streamAnswer's "free of Context reads" contract
		// (see RestControllerContext's javadoc).
		openmrsContext.install();
		try {
			Map<String, String> body = new HashMap<String, String>();
			body.put("patient", RestControllerContext.PATIENT_UUID);
			body.put("question", "is it safe to give her clarithromycin?");

			ResponseEntity<Object> response = controller.search(body);
			assertEquals(HttpStatus.OK, response.getStatusCode(),
					"the handler must have reached serialization");
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = (Map<String, Object>) response.getBody();
			assertNotNull(payload, "no response body");
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> refs = (List<Map<String, Object>>) payload.get("references");
			assertNotNull(refs, "the /search response carried no references array");

			assertEquals(CITATIONS.size(), refs.size(), "every fixture citation must survive serialization");
			for (Map<String, Object> ref : refs) {
				String resourceType = (String) ref.get("resourceType");
				int index = ((Integer) ref.get("index")).intValue();
				assertTrue(ref.containsKey("grounded"),
						"the grounded key must be present on every citation, null included: " + ref);
				assertEquals(expectedWireVerdict(resourceType, index), ref.get("grounded"),
						wireVerdictMessage("/search", resourceType, index));
			}
		}
		finally {
			openmrsContext.restore();
		}
	}

	@Test
	public void referencesEvent_withholdsTheVerdictFromEveryReferenceGroupCitation() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(),
				"is it safe to give her clarithromycin?", RestControllerContext.user(), false);

		assertVerdictsOf("references");
	}

	@Test
	public void doneEvent_withholdsTheVerdictFromEveryReferenceGroupCitation() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(),
				"is it safe to give her clarithromycin?", RestControllerContext.user(), false);

		assertVerdictsOf("done");
	}

	@Test
	public void groundedEvent_withholdsTheVerdictFromEveryReferenceGroupCitation() throws Exception {
		// Async mode emits done before the verdicts exist and delivers them on a trailing grounded
		// event. That event is the ONLY one a client consuming verdicts has to read, so it is the one
		// site where a leaked verdict reaches the badge the issue is about.
		controller.streamAnswer(out, RestControllerContext.patient(),
				"is it safe to give her clarithromycin?", RestControllerContext.user(), true);

		assertVerdictsOf("grounded");
	}

	/**
	 * The client-facing statement of the same rule, made against the wire's OWN discriminator rather
	 * than against a predicate the client cannot call: a client that buckets by {@code group} finds
	 * no verdict anywhere in the {@code reference} bucket, and every verdict it does find in the
	 * {@code chart} bucket. The biconditional is meaningful because every fixture citation carries a
	 * non-null verdict, so a null on the wire can only have been withheld.
	 */
	@Test
	public void everyWithheldVerdictIsExactlyAReferenceGroupCitation() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(),
				"is it safe to give her clarithromycin?", RestControllerContext.user(), false);

		for (JsonNode ref : referencesOf("done")) {
			boolean referenceGroup = ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE
					.equals(ref.get("group").asText());
			assertEquals(referenceGroup, ref.get("grounded").isNull(),
					"citation [" + ref.get("index") + "] is in group \"" + ref.get("group").asText()
							+ "\" and " + (ref.get("grounded").isNull() ? "carries no verdict" : "carries one")
							+ ". A client buckets by group: the reference bucket must hold no verdict at all, "
							+ "and nothing else may have lost the one the pipeline attached.");
		}
	}

	/**
	 * The structural half. The withholding must be derived from the reference GROUP — through
	 * {@link ChartSearchAiUtils#isGroundingDemoteOnly} or {@link ChartSearchAiUtils#referenceGroup} —
	 * and never from a list of type names, because a name list is what silently excluded
	 * {@code safety_finding} from the grounding carve-out for two releases (#122) and what the
	 * frontend of #201 is doing right now.
	 *
	 * <p>No behavioural test can catch that regression today: with exactly two reference-group types,
	 * a hardcoded pair agrees with the classifier on every input that exists. So this asserts the
	 * property directly. {@code RESOURCE_TYPE_*} are compile-time {@code String} constants, so javac
	 * inlines the VALUE into the constant pool of any class that mentions one — by the constant or by
	 * a bare literal, either way. The controller therefore names a resource type if and only if these
	 * bytes contain its wire value.
	 *
	 * <p>The controller has no other business naming one: it serializes a wire shape and asks
	 * {@code ChartSearchAiUtils} for every classification decision in it. If a future change needs a
	 * type name here, that is the conversation this test is for.
	 */
	@Test
	public void theWireSerializerMustNotNameAReferenceGroupResourceType() throws Exception {
		byte[] compiled = compiledControllerClass();

		// Positive controls first: a failed or empty read would otherwise satisfy every assertion
		// below by containing nothing at all, and report a guard that guarded nothing.
		assertTrue(contains(compiled, "grounded"),
				"sanity: the controller's class file must contain the wire key it writes — the read "
						+ "returned " + compiled.length + " bytes and the assertions below would be vacuous");
		assertTrue(contains(compiled, "withheldInteractions"),
				"sanity: the controller's class file must contain the other wire keys it writes");

		List<String> referenceGroupTypes = referenceGroupResourceTypes();
		assertFalse(referenceGroupTypes.isEmpty(),
				"sanity: no declared resource type classifies as reference material, so this guard "
						+ "would forbid nothing");
		for (String type : referenceGroupTypes) {
			assertFalse(contains(compiled, type),
					"ChartSearchAiRestController names the resource type \"" + type + "\". The wire's "
							+ "grounding withholding and its group discriminator must both be derived from "
							+ "ChartSearchAiUtils.isGroundingDemoteOnly / referenceGroup, never from a list "
							+ "of type names: an enumerated list is what left safety_finding out of the "
							+ "grounding carve-out for two releases (#122), and the suite cannot see that "
							+ "mistake behaviourally until a third reference-group type exists.");
		}
	}

	/** Asserts every citation of the named SSE event carries the verdict the wire owes it. */
	private void assertVerdictsOf(String eventType) throws Exception {
		List<JsonNode> refs = referencesOf(eventType);
		for (JsonNode ref : refs) {
			String resourceType = ref.get("resourceType").asText();
			int index = ref.get("index").asInt();
			assertTrue(ref.has("grounded"),
					"the grounded key must be present on every citation, null included: " + ref);
			JsonNode grounded = ref.get("grounded");
			Boolean actual = grounded.isNull() ? null : Boolean.valueOf(grounded.booleanValue());
			assertEquals(expectedWireVerdict(resourceType, index), actual,
					wireVerdictMessage(eventType, resourceType, index));
		}
	}

	/**
	 * What the wire owes a citation: nothing for reference material, and for chart evidence exactly
	 * the verdict the pipeline attached. Derived by asking
	 * {@link ChartSearchAiUtils#isGroundingDemoteOnly}, so this expectation names no resource type
	 * and a new one is classified rather than forgotten.
	 */
	private static Boolean expectedWireVerdict(String resourceType, int index) {
		if (ChartSearchAiUtils.isGroundingDemoteOnly(resourceType)) {
			return null;
		}
		Boolean attached = ATTACHED_VERDICTS.get(Integer.valueOf(index));
		assertNotNull(attached, "citation [" + index + "] is not one this fixture emitted");
		return attached;
	}

	private static String wireVerdictMessage(String site, String resourceType, int index) {
		return ChartSearchAiUtils.isGroundingDemoteOnly(resourceType)
				? site + ": citation [" + index + "] is a \"" + resourceType + "\" record, which is "
						+ "module-supplied reference material. Its grounding verdict must not reach the "
						+ "wire at all — a client with no correct reading of it renders a false verdict "
						+ "as \"Unsupported\" on the module's own deterministic finding (#201)."
				: site + ": citation [" + index + "] is a \"" + resourceType + "\" record — chart "
						+ "evidence, whose verdict a client is entitled to. Withholding it would blank "
						+ "the grounding feature instead of narrowing it to reference material.";
	}

	/** The {@code references} array of the named SSE event, in emitted order. */
	private List<JsonNode> referencesOf(String eventType) throws Exception {
		SseEvent event = SseEvents.ofType(out, eventType);
		assertNotNull(event, "no '" + eventType + "' event was emitted");
		JsonNode refs = MAPPER.readTree(event.data).get("references");
		assertNotNull(refs, "'" + eventType + "' event carried no references array");
		List<JsonNode> list = new ArrayList<JsonNode>();
		for (JsonNode ref : refs) {
			list.add(ref);
		}
		// Every fixture citation, or a serializer that dropped the reference-group ones entirely would
		// satisfy every per-citation assertion by having nothing to assert about.
		assertEquals(CITATIONS.size(), list.size(),
				"'" + eventType + "' must carry every fixture citation");
		return list;
	}

	/**
	 * One citation per declared resource type at {@code TRUE} and one at {@code FALSE}. Both verdicts
	 * are exercised on purpose: {@code FALSE} is the live case #201 is about (Tier-1 still flags an
	 * off-topic reference citation), and {@code TRUE} pins the wire against a future path that
	 * produces one — the withholding is of the FIELD, not of one of its values.
	 */
	private static List<ChartSearchService.RecordReference> buildCitations() {
		List<ChartSearchService.RecordReference> citations =
				new ArrayList<ChartSearchService.RecordReference>();
		int index = 1;
		for (String resourceType : RESOURCE_TYPES) {
			for (Boolean verdict : new Boolean[] { Boolean.TRUE, Boolean.FALSE }) {
				ATTACHED_VERDICTS.put(Integer.valueOf(index), verdict);
				citations.add(new ChartSearchService.RecordReference(index, resourceType,
						"uuid-" + index, null, verdict));
				index++;
			}
		}
		return Collections.unmodifiableList(citations);
	}

	/**
	 * Every declared {@code RESOURCE_TYPE_*} constant as its wire value, in declaration order. The
	 * sweep must not go blind on a constant it cannot read, so an unreadable one fails loudly rather
	 * than being skipped.
	 */
	private static List<String> declaredResourceTypes() {
		List<String> types = new ArrayList<String>();
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			if (!field.getName().startsWith("RESOURCE_TYPE_") || field.getType() != String.class
					|| !Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				field.setAccessible(true);
				types.add((String) field.get(null));
			}
			catch (Exception e) {
				throw new AssertionError("could not read ChartSearchAiConstants." + field.getName()
						+ ", so this sweep would silently skip it", e);
			}
		}
		if (types.isEmpty()) {
			throw new AssertionError("no RESOURCE_TYPE_* constants were discovered; this whole class "
					+ "would assert nothing");
		}
		return Collections.unmodifiableList(types);
	}

	/** The declared resource types that classify as module-supplied reference material. */
	private static List<String> referenceGroupResourceTypes() {
		List<String> types = new ArrayList<String>();
		for (String type : RESOURCE_TYPES) {
			if (ChartSearchAiUtils.isGroundingDemoteOnly(type)) {
				types.add(type);
			}
		}
		return types;
	}

	/** The controller's own compiled class file, read off the classpath it was loaded from. */
	private static byte[] compiledControllerClass() throws IOException {
		String name = ChartSearchAiRestController.class.getSimpleName() + ".class";
		InputStream in = ChartSearchAiRestController.class.getResourceAsStream(name);
		assertNotNull(in, "could not open " + name + " on the classpath");
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = in.read(chunk)) > 0) {
				bytes.write(chunk, 0, read);
			}
			return bytes.toByteArray();
		}
		finally {
			in.close();
		}
	}

	/** Whether the class file's bytes contain {@code text} — i.e. whether the class names it. */
	private static boolean contains(byte[] compiled, String text) {
		byte[] needle = text.getBytes(StandardCharsets.UTF_8);
		outer: for (int start = 0; start <= compiled.length - needle.length; start++) {
			for (int i = 0; i < needle.length; i++) {
				if (compiled[start + i] != needle[i]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	/** Returns the fixture answer: one sentence, and a citation of every declared resource type. */
	private static ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(
				"Clarithromycin interacts with the patient's simvastatin [1].", CITATIONS);
	}

	private static class EveryResourceTypeStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept("Clarithromycin interacts with the patient's simvastatin [1].");
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
