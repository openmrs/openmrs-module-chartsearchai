/*
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
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * on the wire it is a value a client must not interpret: it means "this citation is not about that
 * record", which only {@code group} distinguishes from a chart citation's "this claim may not be
 * supported". A client that classified by {@code resourceType} instead rendered it as
 * "Unsupported — The cited record may not support this statement", in red, on this module's own
 * deterministic Major-interaction finding — see
 * {@code ChartSearchAiRestController.groundedForWire} for which client, at which commit, and why
 * the wire was chosen over patching it.
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
 * re-hardcoding such a carve-out is invisible to a behavioural suite while the hardcoded names still
 * AGREE with the classifier. So the withholding is pinned twice, in two different ways:
 *
 * <ol>
 * <li><em>Behaviourally, off an enumeration rather than a literal.</em> The fixture cites EVERY
 * declared {@code RESOURCE_TYPE_*} constant twice — once carrying {@code TRUE}, once carrying
 * {@code FALSE} — and the expectation for each is derived by asking
 * {@link ChartSearchAiUtils#isGroundingDemoteOnly}. No type name appears in the expectation, so a
 * newly declared reference-group constant is swept automatically, and a serializer that agreed with
 * the names it happens to enumerate fails the moment one is added — measured on issue #354, which
 * added a third: the old pair reddens five of the cases below on a {@code drug_class_note} citation.
 * <p>The cost of deriving rather than enumerating, stated so it is not mistaken for coverage this
 * class does not have: both halves ask the same classifier production asks, so if
 * {@code referenceGroup} itself regressed — classifying {@code drug_reference} as chart — every
 * test here would still pass while the wire republished the verdict, which is #201 again. The
 * load-bearing anchor to real type names is {@code ChartSearchAiReferenceGroupTest}'s hand-recorded
 * group table in the api module, not this class. That is the right division (one place records the
 * decision; everything else derives from it), but it means this class is only as good as that
 * one.</li>
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
		// happens, because every other test in this class must keep running with no context at all:
		// that absence is what enforces streamAnswer's "free of Context reads" contract on the request
		// thread, the keep-alive's own thread being covered by a source scan instead (see
		// RestControllerContext's javadoc).
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
	 * {@code safety_finding} from the grounding carve-out for two releases (#122), and is the same
	 * mistake the client in #201 made.
	 *
	 * <p>This asserts the property directly, because a behavioural case cannot see a hardcode that
	 * still AGREES with the classifier — which is what a hardcoded pair does for every type it happens
	 * to enumerate. It can see one that has fallen behind, and since issue #354 added a third
	 * reference-group type the fixtures here do: mutate {@code groundedForWire} to the old pair and
	 * read which cases redden. Both halves are wanted; neither subsumes the other.
	 *
	 * <p>{@code RESOURCE_TYPE_*} are compile-time {@code String} constants, so javac
	 * inlines the VALUE into the constant pool of any class that mentions one — whether written as
	 * the constant, as a bare literal, or as a folded concatenation. Scanning every class file the
	 * controller compiles to therefore answers "does this class name a resource type".
	 *
	 * <p>Two limits of the mechanism, stated so nobody has to rediscover them. It is a raw byte
	 * scan, so a wire value that happened to be a SUBSTRING of an unrelated literal would redden
	 * this on an unrelated change — today's values are safe, because what the scan forbids is the two
	 * UNDERSCORED type names and the pool holds no literal containing either, while the bare words
	 * {@code reference} and {@code chart} it does hold ({@code "references"},
	 * {@code "chartsearchai"}, {@code "referenceCount"}, {@code "referenceSliceRecords"} … ) are not
	 * what is searched for. That is the property to check rather than a census of the pool, which
	 * this javadoc carried until #229 added two more strings to it and left the list two short. But
	 * a future single-word type name would want word-delimited matching rather than
	 * this being deleted. And a name computed at runtime would evade it; that is not a plausible
	 * accidental regression, and the shape this exists to catch — {@code type.equals(CONSTANT)} — is
	 * not evadable.
	 *
	 * <p>The controller has no other business naming one: it serializes a wire shape and asks
	 * {@code ChartSearchAiUtils} for every classification decision in it. If a future change needs a
	 * type name here, that is the conversation this test is for.
	 */
	@Test
	public void theWireSerializerMustNotNameAReferenceGroupResourceType() throws Exception {
		Map<String, byte[]> compiled = compiledControllerClasses();
		byte[] outer = compiled.get(ChartSearchAiRestController.class.getSimpleName() + ".class");

		// Positive controls first, on the class that writes the wire map: a failed or truncated read
		// would otherwise satisfy every assertion below by containing nothing at all, and report a
		// guard that guarded nothing.
		assertTrue(contains(outer, "grounded"),
				"sanity: the controller's class file must contain the wire key it writes — the read "
						+ "returned " + outer.length + " bytes and the assertions below would be vacuous");
		assertTrue(contains(outer, "withheldInteractions"),
				"sanity: the controller's class file must contain the other wire keys it writes");

		List<String> referenceGroupTypes = referenceGroupResourceTypes();
		assertFalse(referenceGroupTypes.isEmpty(),
				"sanity: no declared resource type classifies as reference material, so this guard "
						+ "would forbid nothing");
		for (Map.Entry<String, byte[]> compiledClass : compiled.entrySet()) {
			for (String type : referenceGroupTypes) {
				assertFalse(contains(compiledClass.getValue(), type),
						compiledClass.getKey() + " names the resource type \"" + type + "\". The wire's "
								+ "grounding withholding and its group discriminator must both be derived from "
								+ "ChartSearchAiUtils.isGroundingDemoteOnly / referenceGroup, never from a list "
								+ "of type names: an enumerated list is what left safety_finding out of the "
								+ "grounding carve-out for two releases (#122). A behavioural case cannot see "
								+ "a hardcode that still agrees with the classifier, which is why this scan "
								+ "exists beside them. Scanned "
								+ compiled.keySet());
			}
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

	/**
	 * EVERY class file the controller compiles to, as file name → bytes: the outer class and each
	 * nested or anonymous {@code ChartSearchAiRestController$*}. The outer class alone is not
	 * enough — {@code PatientResolution} is a real nested class today, so scanning only
	 * {@code ChartSearchAiRestController.class} would let a type name hardcoded inside a nested
	 * class through, and the guard would report a coverage it does not have.
	 *
	 * <p>Read as files rather than through {@code getResourceAsStream} so the sibling class files
	 * can be enumerated at all, and read with {@link Files#readAllBytes} so there is no hand-rolled
	 * copy loop to truncate: a loop written {@code while (read = in.read(buf) > 0)} ends early on a
	 * legal zero-length read, and a truncated buffer satisfies every "does not contain" assertion —
	 * a silent PASS, which is the exact failure this whole guard exists to prevent.
	 *
	 * <p>The file-URL assumption is asserted rather than assumed: loaded from a jar this method
	 * would see one entry and quietly stop covering the nested classes, so it fails loudly instead.
	 */
	private static Map<String, byte[]> compiledControllerClasses() throws Exception {
		String simpleName = ChartSearchAiRestController.class.getSimpleName();
		URL url = ChartSearchAiRestController.class.getResource(simpleName + ".class");
		assertNotNull(url, "could not locate " + simpleName + ".class on the classpath");
		assertEquals("file", url.getProtocol(),
				"this guard enumerates the controller's compiled class files as siblings in a "
						+ "directory; it was loaded from a \"" + url.getProtocol() + "\" URL, where that "
						+ "enumeration would silently cover only the outer class");

		File dir = new File(url.toURI()).getParentFile();
		File[] siblings = dir.listFiles();
		assertNotNull(siblings, "could not list " + dir);
		Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
		for (File file : siblings) {
			String name = file.getName();
			boolean belongs = name.equals(simpleName + ".class")
					|| (name.startsWith(simpleName + "$") && name.endsWith(".class"));
			if (belongs) {
				classes.put(name, Files.readAllBytes(file.toPath()));
			}
		}
		assertTrue(classes.containsKey(simpleName + ".class"),
				"the controller's own class file was not among " + dir + "'s entries");
		return classes;
	}

	/**
	 * Whether the class file's bytes contain {@code text} — i.e. whether the class names it.
	 *
	 * <p>Decoded as ISO-8859-1 rather than scanned by hand: that charset maps every byte 0x00–0xFF
	 * to the same code point, so the decode is lossless and a {@code String.contains} over it is
	 * exactly a byte-subsequence search. (UTF-8 would not be — an invalid sequence decodes to U+FFFD
	 * and the positions shift.) The needles here are the ASCII resource-type wire values, whose
	 * ISO-8859-1 bytes are themselves.
	 */
	private static boolean contains(byte[] compiled, String text) {
		return new String(compiled, StandardCharsets.ISO_8859_1).contains(text);
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
