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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * Build-time guard that fails if someone reintroduces pipeline logic
 * duplication. Scans Java source files for known anti-patterns and
 * reports violations as test failures.
 *
 * <p>This test exists because the same category of bug — tests or
 * production code reimplementing logic that belongs in a shared method —
 * has caused multiple production incidents. Visibility restrictions
 * (private methods) catch some cases, but hardcoded strings, formula
 * reimplementations, and helper duplication can only be caught by
 * scanning the source.
 */
public class ArchitectureGuardTest {

	private static final Path SRC_ROOT = ModuleSourceRoot.apiRoot();

	/** The internal name of the type whose constructions issue #378's guard is about. */
	private static final String CHART_ANSWER_TYPE =
			"org/openmrs/module/chartsearchai/api/ChartSearchService$ChartAnswer";

	/** The descriptor tail that tells RecordMapping's provenance-carrying constructor from every OTHER
	 *  one: it is the only one whose LAST parameter is a list (issue #305). Read it as exactly that —
	 *  NOT as "the widest", which it stopped being when issue #294 added a rung below it
	 *  ({@link #ORDER_NAMING_TAIL}), and NOT as "the only one that takes a provenance list", which it
	 *  also stopped being: the widest takes one too, and is guarded by the sibling case rather than
	 *  by this one. */
	private static final String DERIVED_FROM_TAIL = "Ljava/util/List;)V";

	/** The descriptor tail that tells RecordMapping's widest constructor — the only one taking the
	 *  order-naming stamp of issue #294 — from every other one. TWO types, not one: the
	 *  order-currency rung ends in the same {@code Boolean}, so a single-type tail cannot tell them
	 *  apart, and a list immediately in front of it is what makes this pair unique. WHICH list is not
	 *  part of the claim and must not become one — it was the provenance list until issue #276 put
	 *  the dosing ceilings between them. Verified against {@code javap -s}: the only other
	 *  {@code Boolean}-tailed descriptor ends {@code ILjava/lang/Boolean;)V}. */
	private static final String ORDER_NAMING_TAIL = "Ljava/util/List;Ljava/lang/Boolean;)V";

	/** The CAUSE a reader could build a chart-read verdict out of, instead of the stamp
	 *  {@code PatientClinicalContext.activeDrugOrdersRead()} derives from it (issue #421). */
	private static final String ORDER_READ_CAUSE = "activeDrugOrderReadCompleted";

	/** The class files that may name {@link #ORDER_READ_CAUSE}: the one that DECLARES it, the builder
	 *  — which names it only as a local variable and invokes it nowhere — and the one legitimate
	 *  reader, the operator MESSAGE that has to say which read failed. */
	private static final List<String> MAY_NAME_THE_ORDER_READ_CAUSE = java.util.Arrays.asList(
			"org/openmrs/module/chartsearchai/reference/PatientClinicalContext.class",
			"org/openmrs/module/chartsearchai/reference/PatientClinicalContextBuilder.class",
			"org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.class");

	/** The class file every {@code RecordMapping} constructor case walks. */
	private static final String RECORD_MAPPING_CLASS_FILE =
			"org/openmrs/module/chartsearchai/serializer/PatientChartSerializer$RecordMapping.class";

	/** The one class that may write the two {@code RecordMapping} stamps this file guards. */
	private static final String INJECTOR_CLASS_FILE =
			"org/openmrs/module/chartsearchai/reference/DrugReferenceInjector.class";

	/** The class file the issue #315 case walks, and the one class that may write its stamp. */
	private static final String SERIALIZED_RECORD_CLASS_FILE =
			"org/openmrs/module/chartsearchai/serializer/SerializedRecord.class";

	private static final String CHART_BUILDER_CLASS_FILE =
			"org/openmrs/module/chartsearchai/api/impl/QueryStoreChartBuilder.class";

	/** The descriptor tail that tells {@code SerializedRecord}'s widest constructor — the only one
	 *  taking the order stop date of issue #315 — from every other one. TWO types, not one: the
	 *  four-argument rung {@code (String,String,String,Date)} ends in the same {@code Date}, so a
	 *  single-type tail cannot tell them apart, and the {@code Boolean} immediately in front of it is
	 *  what makes this pair unique. Verified against {@code javap -s}. */
	private static final String STOP_DATE_TAIL = "Ljava/lang/Boolean;Ljava/util/Date;)V";

	/** The descriptor fragment that tells the widest constructor from every shorter one. */
	private static final String COVERAGE_TYPE =
			"Lorg/openmrs/module/chartsearchai/reference/DrugReferenceLoad$Coverage;";

	/** The safety-finding type as a token: the constant's simple name, or the literal value it holds.
	 *  The constant arm is bounded at BOTH ends, so a longer identifier that merely CONTAINS that
	 *  name is not read as it, wherever in the identifier the name sits; the literal arm is bounded by
	 *  its own quotes. ONE spelling, and both readings below are BUILT from it — a second copy is the
	 *  re-inlined-literal hazard this file exists to forbid, and it would let a third spelling added
	 *  here leave the receiver arm looking for the old two with every canary still passing. */
	private static final Pattern FINDING_TYPE_TOKEN = Pattern.compile(
			"\"safety_finding\"|\\bRESOURCE_TYPE_SAFETY_FINDING\\b");

	/** The type test with the token as the RECEIVER, which is the shape both production spellings
	 *  use. Whitespace-tolerant because one of the two wraps across a line, and a line-scoped
	 *  pattern would be blind to exactly the arrangement a re-inline is most likely to copy. */
	private static final Pattern FINDING_TYPE_TEST_RECEIVER = Pattern.compile(
			"(?:" + FINDING_TYPE_TOKEN.pattern() + ")"
			+ "\\s*\\.\\s*equals(?:IgnoreCase)?\\s*\\(");

	/** The opening of an {@code equals}/{@code equalsIgnoreCase} CALL, whose argument list is then
	 *  read whole rather than matched positionally: {@code Objects.equals(type, CONST)} and
	 *  {@code Objects.equals(CONST, type)} are the null-safe respellings a third selection site is
	 *  likeliest to reach for — this module already writes {@code Objects.equals} in
	 *  {@code api/src/main} — and a positional pattern catches at most one of the two argument
	 *  orders. What the leading {@code \b} buys is indifference to WHAT qualifies the call — one
	 *  rule covers {@code Objects.}, {@code StringUtils.} and a fully qualified
	 *  {@code java.util.Objects.} — while still refusing a longer lowercase name ending in
	 *  {@code equals}. A statically imported bare {@code equals(…)} would match too and is not a
	 *  shape to worry about: every class inherits {@code Object.equals}, which shadows the import, so
	 *  the two-argument call does not compile (measured while probing this rule). */
	private static final Pattern EQUALS_CALL = Pattern.compile("\\bequals(?:IgnoreCase)?\\s*\\(");

	/** The only two method bodies in {@code api/src/main} that may spell it, each named by its own
	 *  signature text so the allow-list cannot drift onto a neighbour. */
	private static final List<String> FINDING_TYPE_TEST_HOMES = java.util.Arrays.asList(
			"public static List<RecordMapping> safetyFindingMappings(List<RecordMapping> mappings) {",
			"public static String referenceGroup(String resourceType) {");

	/** Where the two homes live, relative to {@code api/src/main/java} — the KEY the walk files
	 *  that file under, so a class of the same simple name in another package cannot be mistaken
	 *  for it and cannot silently displace it in the map either. */
	private static final String FINDING_TYPE_TEST_HOME_FILE =
			"org/openmrs/module/chartsearchai/ChartSearchAiUtils.java";

	// --- Rules ---

	/**
	 * The constructor that can carry a provenance list is invoked from exactly one class in THIS
	 * module, which is all this walk can see (issue #305).
	 *
	 * <p><b>The api module is the scope, and it is not the whole of production</b> — said once, on
	 * {@link #assertSoleInjectorCallerOfMappingConstructor}, which is where the walk lives. The omod
	 * builds no mappings today.
	 *
	 * <p>Judgements elsewhere rest on this and none of them could see it — how many is not a count
	 * kept here, since each states its own dependence where it is written. The residue
	 * disclosure on {@code CitationGroundingVerifier.AnswerCitations.unanchored} and
	 * {@code ReferenceProseFidelityCheck}'s "needs no filter" note both argue from "an attached index
	 * is always chart-group", which holds only because the sole writer of {@code derivedFrom} is
	 * {@code DrugReferenceInjector}'s findings loop — allergy and condition uuids resolve to chart
	 * records, and the {@code safety_finding} mappings are appended after the uuid index is built, so
	 * a finding cannot attach a finding. Let another class construct a mapping with a derivation and
	 * both break silently and in opposite directions: the prose check would examine, and could
	 * publish an {@code unfaithfullyRenderedCitations} index for, a reference record the answer never
	 * cited, and {@code restsOnReferenceMaterial} would find a demote-only member in EVERY claim's
	 * rests-on set, withholding issue #284's negative for every chart citation in the answer.
	 * {@code SafetyFindingSeverityFidelityCheck} used to argue its own exemption from the last clause
	 * of that property — an attached index is never a {@code safety_finding} record, so it carries no
	 * rating for that check to require. Since issue #409 round two it takes the question from
	 * {@code SafetyFindingCitationExtentCheck.citedFindingIndexes} instead, which applies the #305
	 * filter itself, so a rated mapping built with a derivation no longer reaches that key's
	 * accusation walk. The hazard named here is the GROUP one above; that check is no longer a second
	 * one resting on this property.
	 *
	 * <p><b>Asked of the BYTECODE, and the earlier source-text form is gone rather than patched.</b>
	 * That form matched the literal {@code "new RecordMapping("} and counted commas, and review
	 * defeated it twice, measured: a fully-qualified {@code new
	 * …PatientChartSerializer.RecordMapping(} was invisible to the literal, and the
	 * comment-stripping added to fix an inline-comment miscount ate the tail of
	 * any line holding a {@code //} inside a STRING — an argument such as a URL — which dropped the
	 * counted arity and skipped the construction with no signal at all. That is the fail-open
	 * direction, and it was claimed to fail closed. What ends that sequence is a different KIND of
	 * question rather than another spelling on the list: the constant pool records the descriptor a
	 * call site actually invokes, so qualification, whitespace, comments and string literals are all
	 * out of the picture.
	 *
	 * <p><b>What it cannot answer, and what does — both halves measured.</b> The pool says which
	 * CLASS invokes the wide constructor, not which of that class's five mapping constructions passes
	 * a non-empty list. A second caller reddens THIS case; giving the injector's own
	 * {@code drug_reference} construction a one-element derivation leaves it green and reddens
	 * {@code FindingChartRecordProvenanceContextTest.aChartRecordNamesNoProvenanceOfItsOwn} instead,
	 * over a real arrangement that injects such a record. The two halves are different kinds of
	 * question on purpose; neither alone is the property those three judgements need.
	 *
	 * <p>The canaries that stop this forbidding nothing are enumerated once, on
	 * {@link #assertSoleInjectorCallerOfMappingConstructor}, which holds them. A copy of that list
	 * stood here until the two cases were unified.
	 */
	@Test
	public void theProvenanceCarryingMappingConstructorHasOneCaller() throws IOException {
		assertSoleInjectorCallerOfMappingConstructor(DERIVED_FROM_TAIL, "a provenance list", false,
				"See this test's javadoc for the checks that break silently otherwise.");
	}

	/**
	 * The shared body of both constructor cases in this file: exactly one {@code RecordMapping} constructor matches
	 * {@code tail}, and only {@code DrugReferenceInjector} invokes it.
	 *
	 * <p>One method rather than two copies because the two differ in a selector, the wording, and one
	 * flag — {@code widest}, which is not decoration: it turns the prefix canary below ON for the case
	 * whose subject IS the widest constructor and off for the other, so a third case added by copying
	 * either call site must decide it rather than inherit it — and the copy drifted the first time it
	 * was made, losing the several-arities canary
	 * below within a single commit, while its javadoc still claimed every canary fails on an empty
	 * discovery.
	 *
	 * <p>Every canary here fails on an empty discovery, because a guard that finds nothing forbids
	 * nothing: the classes directory, the mapping's own class file, more than one constructor arity,
	 * exactly one matching {@code tail}, and the caller set being the one expected rather than empty.
	 *
	 * <p><b>Its reach is the API module's classes, which is the whole of what this walk reads.</b> An
	 * omod-side caller is invisible to it, as it is to the {@code groundedForWire} guard that states
	 * the same limit for its own scope.
	 */
	private static void assertSoleInjectorCallerOfMappingConstructor(String tail, String what,
			boolean widest, String consequence) throws IOException {
		assertSoleCallerOfStampCarryingConstructor(RECORD_MAPPING_CLASS_FILE, "RecordMapping",
				"DrugReferenceInjector", INJECTOR_CLASS_FILE, tail, what, widest, consequence);
	}

	/**
	 * The shared body of all three constructor cases in this file: exactly one constructor of
	 * {@code targetClassFile} matches {@code tail}, and only {@code expectedCallerClassFile} invokes
	 * it.
	 *
	 * <p><b>Parameterized rather than copied, and the file's own history is the argument.</b> The
	 * javadoc of {@link #assertSoleInjectorCallerOfMappingConstructor} records that copying this body
	 * once already lost the several-arities canary within a single commit. Issue #315 needed the same
	 * walk over a DIFFERENT type — {@code SerializedRecord} rather than {@code RecordMapping}, and a
	 * different sole caller — and copied it a second time; that copy drifted too, in the other
	 * direction, adding a path-separator normalisation the original lacked so the two disagreed about
	 * Windows paths. Three differences were already parameters here; these three are the rest.
	 */
	private static void assertSoleCallerOfStampCarryingConstructor(String targetClassFile,
			String typeName, String expectedCallerName, String expectedCallerClassFile, String tail,
			String what, boolean widest, String consequence) throws IOException {
		Path classes = ModuleSourceRoot.apiRoot().resolve("target/classes");
		assertTrue(Files.isDirectory(classes),
				"no " + classes + "; a guard that discovers nothing forbids nothing");
		Path mapping = classes.resolve(targetClassFile);
		assertTrue(Files.exists(mapping),
				"no " + typeName + " class file at " + mapping + ", so this guard would forbid nothing");

		List<String> constructors = constructorDescriptors(mapping);
		assertTrue(constructors.size() > 1,
				"expected " + typeName + " to publish several constructor arities and found "
						+ constructors.size() + "; with one there is no narrower one for a caller that "
						+ "carries none of this to use, and this guard is vacuous");
		List<String> carrying = new ArrayList<>();
		for (String descriptor : constructors) {
			if (descriptor.endsWith(tail)) {
				carrying.add(descriptor);
			}
		}
		assertEquals(1, carrying.size(),
				"exactly one " + typeName + " constructor may END in " + what + ", which is how this "
						+ "case tells it from the others. Found " + carrying.size() + ": " + carrying);
		// A tail selects by the LAST parameter, so a rung added BELOW the widest keeps its own tail,
		// matches NEITHER selector, and is guarded by nothing — both cases stay green while a second
		// class writes through the new widest. Measured, on a mutation adding a 12th parameter plus a
		// second writer. The caller asserting `widest` is the one whose subject is the widest
		// constructor, and this is what makes a new rung redden rather than silently disarm it. It is
		// deliberately NOT asserted for the provenance case, whose subject stopped being the widest
		// when issue #294 added a rung below it and is identified by its own tail regardless.
		if (widest) {
			String guarded = parameters(carrying.get(0));
			for (String descriptor : constructors) {
				// Every other rung must be a PREFIX of the guarded one, which says two things at once
				// and exactly: the guarded constructor is the widest, and the ladder is still a prefix
				// chain of it. A length comparison would say the first only approximately — a NARROWER
				// rung taking longer type names is lexically longer, and would fire this with a message
				// about a rung "added below" that was not.
				assertTrue(guarded.startsWith(parameters(descriptor)),
						"the constructor this case guards must still be the WIDEST and every other rung "
								+ "a prefix of it, or a rung has been added that no selector reaches and "
								+ "nothing forbids a second writer of. Guarded: " + carrying.get(0)
								+ "; not a prefix of it: " + descriptor);
			}
		}

		List<String> callers = new ArrayList<>();
		try (java.util.stream.Stream<Path> tree = Files.walk(classes)) {
			for (Path file : tree.filter(f -> f.toString().endsWith(".class"))
					.filter(f -> !f.equals(mapping)).collect(java.util.stream.Collectors.toList())) {
				if (constantPoolStrings(file).contains(carrying.get(0))) {
					// Spelled with forward slashes whatever the platform separator is, because the
					// expectation below is spelled that way. The copy this method replaced normalised
					// on one of its two call paths and not the other.
					callers.add(classes.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
				}
			}
		}
		assertEquals(java.util.Collections.singletonList(expectedCallerClassFile), callers,
				"the constructor that carries " + what + " may be invoked from " + expectedCallerName
						+ " and nowhere else in the API module's classes, which is what this walk reads. "
						+ consequence + " Callers found: " + callers);
	}

	/**
	 * The stamp that says whether an injected active-order record NAMES its order's drug is written in
	 * exactly ONE place (issue #294): only {@code DrugReferenceInjector} may invoke the RecordMapping
	 * constructor that takes it.
	 *
	 * <p><b>Why a guard and not a comment.</b> The stamp's {@code FALSE} withholds a grounding verdict,
	 * so a second writer does not break anything visibly — it silently stops a class of chart citation
	 * being verified at all, which is the fail-open direction and the inverse of the issue #201 rule.
	 * The stamp is also the one answer the grounding pass cannot sanity-check, having no order to ask:
	 * it takes the mapping's word for it. Nothing behavioural can see a second writer, because a
	 * second writer would be adding an arrangement rather than changing one.
	 *
	 * <p>Modelled on {@link #theProvenanceCarryingMappingConstructorHasOneCaller} and asking the same
	 * kind of question of the constant pool, for the reasons that case's javadoc gives about the
	 * source-text form it replaced. It differs in its selector, {@link #ORDER_NAMING_TAIL} — whose
	 * javadoc says why this constructor needs a two-type tail where the provenance one needs a
	 * single-type one, and is canonical for it — and in passing {@code widest}, which the sibling does
	 * not, because the provenance constructor stopped being the widest when issue #294 added a rung
	 * below it.
	 *
	 * <p>What it cannot answer: the pool says which CLASS invokes that constructor, not WHAT it
	 * passes. The injector could stamp a record that is not an active order and this stays green.
	 * An earlier draft of this sentence named
	 * {@code CitationGroundingVerifierTest.aNamedActiveOrderRecordIsStillGradedThroughTheRealInjector}
	 * as the cover for that, and a review MEASURED it false — stamping {@code FALSE} on every
	 * injected {@code drug_reference} mapping left the whole build green, that case included, because
	 * it reads only the {@code active_drug_order} mapping. The cover is
	 * {@code DrugReferenceInjectorTest.onlyTheActiveOrderRecordCarriesTheOrderNamingStamp}, which
	 * reads the OTHER records of a real injection and states why the hole was worth closing rather
	 * than documenting.
	 */
	@Test
	public void theOrderNamingStampIsWrittenInOnePlace() throws IOException {
		assertSoleInjectorCallerOfMappingConstructor(ORDER_NAMING_TAIL,
				"the order-naming stamp of issue #294", true,
				"A second writer would withhold grounding verdicts for chart citations silently — "
						+ "see this test's javadoc.");
	}

	/**
	 * The stop date of a chart record's drug order is written in exactly ONE place (issue #315): only
	 * {@code QueryStoreChartBuilder} may invoke the {@code SerializedRecord} constructor that takes
	 * it.
	 *
	 * <p><b>Why a guard and not a comment.</b> The date reaches a clinician as a deterministic
	 * statement about a prescription — it is published on the wire as {@code orderStopDates} — and a
	 * second writer would be a second answer to "when did this order end" with nothing reconciling
	 * them. The whole point of the stamp is that the question is asked once, of {@code OrderService},
	 * against the same read that decides whether the order is in force at all; a writer somewhere
	 * else could supply a date for a record that reading calls current, or for one it could not
	 * evaluate. Nothing behavioural sees a second writer, because a second writer adds an
	 * arrangement rather than changing one — which is the same argument
	 * {@link #theOrderNamingStampIsWrittenInOnePlace} makes for its own stamp.
	 *
	 * <p>It is the sibling of that case and of
	 * {@link #theProvenanceCarryingMappingConstructorHasOneCaller}, and differs from both in its
	 * SUBJECT: those two guard a {@code RecordMapping} constructor against every caller but the
	 * injector, and this one guards a {@code SerializedRecord} constructor against every caller but
	 * the chart builder. So it does not share their helper, which names the injector.
	 *
	 * <p>What it cannot answer, and the limits are the same two its siblings state. The constant pool
	 * says which CLASS invokes that constructor, not WHAT it passes — the builder could pass a date
	 * it read anywhere and this stays green; what covers that is
	 * {@code DrugOrderCurrencyMarkTest}, whose cases assert the date against the dataset's own
	 * values rather than against the accessor production reads. And its reach is the API module's
	 * classes, so an omod-side or test-side caller is invisible to it.
	 *
	 * <p><b>A third residue is this guard's own and is not one its siblings have.</b> The tail selects
	 * the rung that takes the stop date, so a second writer of the CURRENCY mark alone — through the
	 * eight-argument rung, which takes {@code orderActive} and defaults the date — invokes a different
	 * descriptor and stays green here. That rung is invoked by no production class today and by test
	 * helpers only, and nothing pins that; the sibling stamp has no single-writer guard of its own,
	 * which is why this sentence names the hole rather than claiming the pair is covered.
	 */
	@Test
	public void theOrderStopDateStampIsWrittenInOnePlace() throws IOException {
		assertSoleCallerOfStampCarryingConstructor(SERIALIZED_RECORD_CLASS_FILE, "SerializedRecord",
				"QueryStoreChartBuilder", CHART_BUILDER_CLASS_FILE, STOP_DATE_TAIL,
				"the order stop date of issue #315", true,
				"A second writer would be a second answer to when a prescription ended, published to a "
						+ "clinician with nothing reconciling them — see this test's javadoc.");
	}

	/** The parameter section of a method descriptor — everything between the parentheses — so two
	 *  rungs of a constructor ladder can be compared as prefixes without parsing types. */
	private static String parameters(String descriptor) {
		return descriptor.substring(descriptor.indexOf('(') + 1, descriptor.lastIndexOf(')'));
	}

	/**
	 * No file outside ChartSearchAiConstants should call getEmbeddingPrefix().
	 * It is private, so the compiler enforces this for production code, but
	 * this test catches reflection hacks or accidental visibility changes.
	 */
	@Test
	public void noDirectGetEmbeddingPrefixCalls() throws IOException {
		List<String> violations = scanForPattern(
				SRC_ROOT,
				Pattern.compile("getEmbeddingPrefix\\s*\\("),
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of getEmbeddingPrefix()");
		assertNoViolations(violations);
	}

	/**
	 * No file should hardcode the embedding prefix strings that
	 * getEmbeddingPrefix() returns. If someone writes
	 * {@code "Clinical observation: " + text} they are bypassing
	 * buildPrefixedText().
	 */
	@Test
	public void noHardcodedEmbeddingPrefixes() throws IOException {
		// Match quoted prefix strings followed by concatenation or variable use.
		// Exclude ChartSearchAiConstants (where prefixes are defined),
		// TestDatasetHelper (where dataset strings naturally contain them),
		// and this test file itself.
		Pattern pattern = Pattern.compile(
				"\"(Clinical observation: |Medical condition: "
				+ "|Patient allergy: |Clinical diagnosis: "
				+ "|Medication prescription: |Lab or diagnostic test: "
				+ "|Clinical referral: |Clinical order: "
				+ "|Program enrollment: |Medication dispensed: )\"");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of hardcoded prefix strings");
		assertNoViolations(violations);
	}

	/**
	 * Every request to the spawned llama-server is built by {@link LlamaServerEndpoint}, which is
	 * the only thing that attaches this start's key (issue #445). Before that class existed the
	 * engine hand-assembled the loopback URL at five call sites and sent no credential at all, so
	 * there was nowhere authentication COULD be added once and five places to forget it. A sixth
	 * hand-rolled call site would be unauthenticated and silent — nothing about it fails a test
	 * that reads behaviour — which is why this reads the source instead.
	 *
	 * <p>Scanned tree-wide minus the legitimate homes, the shape
	 * {@link #noDirectGetEmbeddingPrefixCalls} uses. {@code RemoteLlmEngine} builds requests to the
	 * operator's OWN configured endpoint, which this rule says nothing about, and
	 * {@code LlmEndpointTestSupport} is the opt-in suites' client for a hand-started server.
	 */
	@Test
	public void everyLocalServerRequestCarriesTheModulesKey() throws IOException {
		assertNoViolations(scanForPattern(
				SRC_ROOT,
				Pattern.compile("HttpRequest\\s*\\.\\s*newBuilder\\s*\\("),
				"LlamaServerEndpoint.java|RemoteLlmEngine.java|LlmEndpointTestSupport.java"
						+ "|ArchitectureGuardTest.java",
				"Should build the request through LlamaServerEndpoint.request(), which attaches "
						+ "the per-start key, instead of a bare HttpRequest.newBuilder()"));
	}

	/**
	 * Every protection #445 adds is CALLED. The functions are pinned by
	 * {@code LocalLlmServerAuthTest}, which drives each of them directly — and that leaves the
	 * invocations unpinned: a review round measured that deleting
	 * {@code requireLoopbackPortFree(serverPort)}, the {@code requireListenerMayBeServed} gate and
	 * {@code endpoint.handOverTo(pb)} each left the FULL suite green. A dropped call is the whole
	 * defect back — an unauthenticated child, or a foreign listener adopted — and nothing
	 * behavioural can see it, because no test in this module can launch the subprocess those lines
	 * guard.
	 *
	 * <p>It reads the SOURCE rather than the class file's constant pool, and that is the second
	 * attempt: a pool check passed with the call deleted, because three of the four are methods of
	 * this same class and their names are in the pool from the DECLARATION alone. Only
	 * {@code handOverTo}, declared elsewhere, reddened. So the lookup is over code lines — comments
	 * and string literals stripped by {@link #codeLines}, which is what makes commenting a call
	 * out fail rather than pass. It cannot check the calls are in the right ORDER or on the right
	 * path; it checks they have not vanished, which is the failure that was measured to be
	 * invisible. The canary is what stops it passing vacuously on a file it never read.
	 */
	@Test
	public void theLocalServerProtectionsAreAllCalled() throws IOException {
		List<String> source = getSourceCache().get("LocalLlmEngine.java");
		assertTrue(source != null && !source.isEmpty(),
				"expected LocalLlmEngine.java in the source cache — a guard that reads nothing "
						+ "reports no violations");
		String code = String.join("\n", codeLines(source));

		assertTrue(code.contains("ensureServerRunning()"),
				"canary: this file must carry its own calls, or every assertion below passes on a "
						+ "source this test failed to read");

		List<String> violations = new ArrayList<>();
		for (String call : java.util.Arrays.asList("requireLoopbackPortFree(serverPort)",
				"requireListenerMayBeServed(endpoint", "endpoint.handOverTo(",
				"rememberServerOutput(startOutput")) {
			if (!code.contains(call)) {
				violations.add("LocalLlmEngine no longer calls " + call
						+ " — issue #445's protections are each one line at one call site, and"
						+ " deleting one restores the defect with no behavioural test able to see it");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * And nothing else spells the local server's loopback address, because a hand-built URL is how
	 * a request comes to bypass {@link LlamaServerEndpoint} without looking like it does — the
	 * form {@code slotAction} carried before #445. The endpoint class is the one home.
	 */
	@Test
	public void theLocalServerAddressIsSpelledInOnePlace() throws IOException {
		assertNoViolations(scanForPattern(
				SRC_ROOT,
				Pattern.compile("\"http://127\\.0\\.0\\.1:"),
				// No production exclusion: LlamaServerEndpoint builds its URLs from LOOPBACK_HOST
				// and spells this literal nowhere, so excluding it would only weaken the scan.
				"ArchitectureGuardTest.java",
				"Should take the URL from LlamaServerEndpoint (completionsUrl/healthUrl/"
						+ "propsUrl/slotUrl) instead of spelling the loopback address"));
	}

	/**
	 * No file should reimplement cosine similarity. The canonical
	 * implementation is in ChartSearchAiUtils.cosineSimilarity().
	 * Reimplementations typically contain {@code dot +=} and
	 * {@code na +=} or {@code normA +=} in the same method.
	 */
	@Test
	public void noReimplementedCosineSimilarity() throws IOException {
		// Detect the common reimplementation pattern: a loop body that
		// computes dot product and norms.
		Pattern pattern = Pattern.compile(
				"dot\\s*\\+=\\s*[ab]\\[");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java",
				"Should use ChartSearchAiUtils.cosineSimilarity() "
				+ "instead of reimplementing the formula");
		assertNoViolations(violations);
	}

	/**
	 * The injected-finding POPULATION is selected in one method. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a> folded
	 * two character-for-character copies of that selection into
	 * {@code ChartSearchAiUtils.safetyFindingMappings}, and until this rule existed the coupling was
	 * held by a javadoc sentence alone: a third site re-inlining the type test breaks nothing the
	 * day it is written — the spellings are equal — which is the hazard
	 * {@code ActiveOrderInteractionPhraseTest} records for
	 * {@code DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE}. The divergence arrives later, as
	 * a prompt asking for an enumeration of one population while {@code findingCitations} counts
	 * another.
	 *
	 * <p><b>Two homes, not one, and the second is a different question.</b>
	 * {@code safetyFindingMappings} SELECTS the population; {@code referenceGroup} asks which
	 * of three types is reference material, off a bare type string with no mapping in sight, and
	 * {@code ChartSearchAiReferenceGroupTest} is what binds it. Naming both is what lets this rule
	 * be spelled as "nowhere else" rather than as a file exclusion.
	 *
	 * <p><b>What it catches, measured by writing each shape into a third class in
	 * {@code api/src/main} and reading this case's own failures (2026-09-09, twelve shapes, all
	 * twelve reported):</b> the constant or its literal value {@code "safety_finding"} as the
	 * receiver of {@code equals} or {@code equalsIgnoreCase} — including the arrangement the
	 * production spellings themselves use, one of which wraps across a line — and the same token
	 * ANYWHERE in such a call's argument list. The rule keys on the METHOD NAME, so whatever
	 * qualifies it is out of the picture: the shapes measured were {@code java.util.Objects.equals}
	 * in BOTH argument orders, a {@code StringUtils.equals}-shaped two-argument helper, a fully
	 * qualified constant as the sole argument, and
	 * {@code Objects.equals(mapping.getResourceType(), CONST)} — an argument carrying parentheses of
	 * its own, which is what a positional pattern cannot read past. The
	 * null-safe respellings are the ones worth reaching, because a maintainer writing a third
	 * selection site reaches for them rather than for the constant-first receiver trick production
	 * uses, and this module already writes {@code Objects.equals} in {@code api/src/main}. It does
	 * not matter whether the comparison reads {@code getResourceType()} directly, so a re-inline
	 * through a local holding the type is caught too.
	 *
	 * <p><b>What it does NOT catch. This list is not exhaustive and the shapes on it are UNCAUGHT
	 * rather than covered elsewhere</b> — measured in the same run: {@code contains},
	 * {@code startsWith}, a reference comparison ({@code type == CONST}) against the interned
	 * constant, a {@code switch} whose {@code case} label is the constant, and a comparison moved
	 * into a helper of another name ({@code sameType(type, CONST)}) all pass this rule silently. A
	 * re-collection through {@code referenceGroup} against {@code REFERENCE_GROUP_REFERENCE} passes
	 * too, and that one is out of reach by design — it selects THREE types and is a wider
	 * population, not this one respelled.
	 *
	 * <p><b>And the scope is {@code api/src/main/java}.</b> The omod is unreached (it builds no
	 * mappings today, the same disclosure
	 * {@link #theProvenanceCarryingMappingConstructorHasOneCaller} carries). The test tree is out of
	 * scope because a copy there cannot move the coupling this rule protects — a test file selects
	 * findings for its own assertion and reaches neither the prompt nor {@code findingCitations}.
	 * <b>That is not the same as saying the tree is funnelled through one matcher: it is not.</b>
	 * {@code DrugReferenceTestSupport.injectedFindings} is the matcher the tree is MEANT to use, and
	 * several test files besides it still spell the type test themselves; its own javadoc names that
	 * residue and the different hazard it carries, in the shape
	 * {@code DrugReferenceTestSupport.injectedActiveOrders} uses for its own.
	 *
	 * <p><b>It reads COMMENTS as well as code</b>, so a javadoc that quotes the predicate outside
	 * those two bodies reddens this case. That direction is a false positive rather than a silent
	 * pass, and it is the deliberate choice: comment-stripping is what defeated the earlier
	 * source-text form of {@link #theProvenanceCarryingMappingConstructorHasOneCaller}, eating the
	 * tail of any line holding {@code //} inside a string literal.
	 *
	 * <p>Both canaries fail on an empty discovery, because a rule that finds nothing forbids
	 * nothing: the walk must have read {@code ChartSearchAiUtils.java}, both named signatures must
	 * still be declared there, and <b>each of the two bodies must itself contain a match</b> — that
	 * last is what stops a rewrite of a production spelling from disarming the rule instead of
	 * reddening it. Measured: narrowing the receiver arm off the production spelling reddens on that
	 * precondition, and dropping either name from the allow-list reddens with the named method's own
	 * spelling reported as the violation.
	 *
	 * <p><b>What the two bodies keep honest is ONE alternative of ONE shape, and that is stated at
	 * the granularity it was measured at rather than as "the receiver shape".</b> Both spell
	 * {@code ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(}, so the precondition above
	 * holds the reading to the QUALIFIED-CONSTANT receiver and to nothing else, and <b>any narrowing
	 * the two bodies still satisfy passes both preconditions</b>. Measured 2026-09-09, one mutation
	 * per run, each leaving this case GREEN: deleting the {@code "safety_finding"} literal arm from
	 * {@link #FINDING_TYPE_TOKEN}, the one home both readings are built from; replacing
	 * {@code equals(?:IgnoreCase)?} with {@code equals} in the receiver pattern and in
	 * {@link #EQUALS_CALL}, which are two copies of THAT fragment; and
	 * narrowing the receiver pattern to require the {@code ChartSearchAiConstants.} qualifier, after
	 * which the reading sees neither a static-imported
	 * {@code RESOURCE_TYPE_SAFETY_FINDING.equals(t)} nor {@code "safety_finding".equals(t)}. The
	 * literal arm is the live one of the three: the root {@code CLAUDE.md} carries a rule against
	 * testing a {@code resourceType} against a named type at all (issue #122), which exists because
	 * such tests do get written. Deleting the {@link #EQUALS_CALL} half of
	 * {@link #findingTypeTests} leaves this green too — neither production spelling puts the constant
	 * in an {@code equals} ARGUMENT list — and silently gives up every argument-side shape above, the
	 * {@code Objects.equals} ones included. <b>Trim nothing here on the strength of the build staying
	 * green after it.</b>
	 */
	@Test
	public void theFindingPopulationIsSelectedInOneMethod() throws IOException {
		Path main = SRC_ROOT.resolve("src/main/java");
		assertTrue(Files.exists(main),
				"precondition: no production sources at " + main + " — this rule would forbid nothing");
		final Path root = main;
		final java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
		Files.walkFileTree(main, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.toString().endsWith(".java")) {
					sources.put(root.relativize(file).toString().replace('\\', '/'),
							new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				}
				return FileVisitResult.CONTINUE;
			}
		});
		String utils = sources.get(FINDING_TYPE_TEST_HOME_FILE);
		assertTrue(utils != null,
				"precondition: the walk of " + main + " did not find " + FINDING_TYPE_TEST_HOME_FILE
						+ ", so it is reading the wrong tree, or the class moved package and this "
						+ "rule's allow-list no longer resolves — either way every violation below "
						+ "would be invisible");
		List<int[]> allowed = new ArrayList<>();
		for (String signature : FINDING_TYPE_TEST_HOMES) {
			int start = utils.indexOf(signature);
			assertTrue(start >= 0,
					"precondition: ChartSearchAiUtils no longer declares \"" + signature + "\" — this "
							+ "rule's allow-list is keyed on that signature, so it would report the "
							+ "method's own spelling as the violation");
			int[] region = new int[] { start, endOfBody(utils, start + signature.length() - 1) };
			assertTrue(!findingTypeTests(utils.substring(region[0], region[1])).isEmpty(),
					"precondition: \"" + signature + "\" no longer spells the type test in a shape this "
							+ "rule can see, so the rule has been disarmed rather than satisfied");
			allowed.add(region);
		}
		List<String> violations = new ArrayList<>();
		for (java.util.Map.Entry<String, String> entry : sources.entrySet()) {
			String source = entry.getValue();
			for (int[] test : findingTypeTests(source)) {
				boolean home = FINDING_TYPE_TEST_HOME_FILE.equals(entry.getKey())
						&& insideAnyOf(test[0], allowed);
				if (!home) {
					String quoted = source.substring(test[0], test[1]).replace("\n", " ");
					violations.add(entry.getKey() + ":"
							+ lineOf(source, test[0])
							+ " — the injected-finding population is selected by "
							+ "ChartSearchAiUtils.safetyFindingMappings and nowhere else (issue #397); "
							+ "call it instead of respelling the type test\n    "
							+ (quoted.length() > 160 ? quoted.substring(0, 160) + "…" : quoted));
				}
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every place {@code source} tests a resource type against the safety-finding type, as
	 * {@code {start, end}} offsets — the ONE reading of that question in this class, so a canary
	 * cannot be satisfied by a shape the rule itself does not look for.
	 *
	 * <p>Two shapes, and the second is read rather than matched. The token as the RECEIVER of
	 * {@code equals}/{@code equalsIgnoreCase} is a token sequence and stays a pattern. The token as
	 * an ARGUMENT is not: it can sit at any position of the list, behind any depth of qualifier, and
	 * beside arguments carrying parentheses of their own ({@code mapping.getResourceType()}), so the
	 * list is read to its matching close paren and searched whole. That is what reaches both
	 * argument orders of {@code Objects.equals} with one rule instead of one alternation per order.
	 *
	 * <p><b>The paren count is naive in the same way {@link #endOfBody}'s brace count is</b> — it
	 * knows nothing of strings, chars or comments — and it has THREE outcomes, of which TWO are
	 * silent. A {@code )} inside a literal ahead of the token ends the list early and truncates the
	 * span, hiding a token that sits after it. A {@code (} inside a literal runs the list past its
	 * real end, and in the ordinary case that means past the end of the FILE: {@link #endOfArguments}
	 * returns -1 and {@link #findingTypeTests} discards the call outright, which is the strongest
	 * fail-open path here — a real type test carrying one such literal leaves the rule altogether.
	 * It becomes loud only where a later net-extra {@code )} in the same file brings the depth back
	 * to zero, reporting a token that is not in the argument list at all.
	 *
	 * <p><b>Measured 2026-09-09 by putting each shape to this reading on its own: all four are
	 * MISSED</b> — {@code Objects.equals(t + ")", CONST)}, {@code Objects.equals(t + "(", CONST)},
	 * {@code Objects.equals(t.replace(')', ' '), CONST)} and
	 * {@code Objects.equals(t.substring(t.indexOf('(') + 1), CONST)} — with the loud outcome
	 * reproduced only by putting a net-extra {@code )} later in the same source, which then reported
	 * a token sitting outside the real argument list. The last two are ordinary Java and not a stray:
	 * a genuine type test whose argument carries a paren-bearing sub-expression escapes a rule whose
	 * whole purpose is to forbid it. <b>What makes that tolerable
	 * is the tree and not the parser</b> — the same run read 107 {@code equals}-shaped calls across
	 * the 68 java files of {@code api/src/main}, none of them returning -1, the longest span 93
	 * characters and none crossing more than two lines.
	 */
	private static List<int[]> findingTypeTests(String source) {
		List<int[]> found = new ArrayList<>();
		Matcher receiver = FINDING_TYPE_TEST_RECEIVER.matcher(source);
		while (receiver.find()) {
			found.add(new int[] { receiver.start(), receiver.end() });
		}
		Matcher call = EQUALS_CALL.matcher(source);
		while (call.find()) {
			int close = endOfArguments(source, call.end() - 1);
			if (close < 0) {
				continue;
			}
			if (FINDING_TYPE_TOKEN.matcher(source.substring(call.end(), close)).find()) {
				found.add(new int[] { call.start(), close + 1 });
			}
		}
		java.util.Collections.sort(found, new java.util.Comparator<int[]>() {
			@Override
			public int compare(int[] left, int[] right) {
				return Integer.compare(left[0], right[0]);
			}
		});
		return found;
	}

	/** The index of the paren closing the argument list that opens at {@code openParen}, or -1 where
	 *  the source runs out first — the caveats are {@link #findingTypeTests}'. */
	private static int endOfArguments(String source, int openParen) {
		int depth = 0;
		for (int i = openParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/** Whether {@code offset} falls inside any of {@code regions}, each a {@code {start, end}} pair
	 *  with the end EXCLUSIVE, as {@link #endOfBody} returns it. */
	private static boolean insideAnyOf(int offset, List<int[]> regions) {
		for (int[] region : regions) {
			if (offset >= region[0] && offset < region[1]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The dataset helpers (inferResourceType, stripDatasetPrefixAndDate,
	 * DATASET_PREFIXES) should only exist in TestDatasetHelper. Any other
	 * test file defining these is duplicating shared logic.
	 */
	@Test
	public void noDuplicatedDatasetHelpers() throws IOException {
		Pattern pattern = Pattern.compile(
				"(private|static).*(inferResourceType|stripDatasetPrefixAndDate"
				+ "|DATASET_PREFIXES|DATE_PREFIX_PATTERN)");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use TestDatasetHelper instead of duplicating dataset helpers");
		// Allow the thin delegates in LlmInferenceServiceTest
		List<String> filtered = new ArrayList<>();
		for (String v : violations) {
			// Thin delegate pattern: "return TestDatasetHelper.xxx"
			if (v.contains("return TestDatasetHelper.")) {
				continue;
			}
			filtered.add(v);
		}
		assertNoViolations(filtered);
	}

	/**
	 * The FULL_PATIENT_DATASET and SECOND_PATIENT_DATASET arrays should
	 * only be defined in TestDatasetHelper. Other test files should
	 * reference TestDatasetHelper's copy, not define their own.
	 */
	@Test
	public void noDuplicatedDatasetArrays() throws IOException {
		// Match array declarations containing dataset record literals
		// (lines starting with /* [ and containing "Clinical observation:"
		// or similar). A file defining 10+ such lines is duplicating the
		// dataset.
		// This rule walks its own directory instead of getSourceCache(), so the preconditions there do
		// not cover it — and a silent `return` on a missing directory is the same fail-open one rule
		// along: a wrong or moved source root leaves it reporting no violations forever. Measured
		// under a forced-wrong apiRoot(), this was the ONE rule of the five that stayed green.
		Path testDir = SRC_ROOT.resolve(
				"src/test/java/org/openmrs/module/chartsearchai");
		org.junit.jupiter.api.Assertions.assertTrue(Files.exists(testDir),
				"precondition: the test source directory was not found under " + SRC_ROOT + ", so this "
						+ "rule would scan nothing and report no violations — it fails instead");
		List<String> violations = new ArrayList<>();
		// The right-tree canary, matching the second precondition in getSourceCache(). Existence alone
		// is NOT equivalent to it: the sibling omod module carries the same package path, so a root
		// pointed there passes the existence check and this rule scans the wrong tree and reports no
		// violations — measured, that mutation reddens the four cache-reading rules and left this one
		// green.
		final List<String> scanned = new ArrayList<>();
		Files.walkFileTree(testDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
					throws IOException {
				if (!file.toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				String name = file.getFileName().toString();
				scanned.add(name);
				if ("TestDatasetHelper.java".equals(name)
						|| "ArchitectureGuardTest.java".equals(name)) {
					return FileVisitResult.CONTINUE;
				}
				List<String> lines = Files.readAllLines(file);
				int datasetLines = 0;
				for (String line : lines) {
					if (line.contains("/* [") && (
							line.contains("Clinical observation:")
							|| line.contains("Medication prescription:")
							|| line.contains("Medical condition:")
							|| line.contains("Patient allergy:")
							|| line.contains("Program enrollment:"))) {
						datasetLines++;
					}
				}
				if (datasetLines > 5) {
					violations.add(file.getFileName() + ": contains "
							+ datasetLines + " inline dataset records. "
							+ "Use TestDatasetHelper.FULL_PATIENT_DATASET instead.");
				}
				return FileVisitResult.CONTINUE;
			}
		});
		org.junit.jupiter.api.Assertions.assertTrue(scanned.contains("TestDatasetHelper.java"),
				"precondition: the scan under " + testDir + " did not see TestDatasetHelper.java, so it "
						+ "is reading the wrong tree — a wrong root that happens to carry this package "
						+ "path scans SOMETHING and this rule then reports no violations");
		assertNoViolations(violations);
	}

	/**
	 * {@code ClassCodeFidelityCheck} must reach citation markers through
	 * {@code ChartSearchAiUtils.citedIndexes} and carry no marker dialect of its own. Since issue
	 * #338 it asks whether a marker sits inside a class-code parenthetical, and CLAUDE.md's rule for
	 * that question is that {@code ChartSearchAiUtils.INLINE_CITATION} is the single parsing pattern,
	 * reached through that shared decode step; the one production site that keeps its own matcher
	 * does so because it needs each marker's text OFFSET, which this check does not.
	 *
	 * <p>Nothing behavioural can pin it: a private bracket pattern, a hand-rolled {@code charAt}
	 * walk, or {@code INLINE_CITATION.matcher(...)} used directly all answer identically on every
	 * case in {@code ClassCodeFidelityTest}, so the whole suite stays green on exactly the regression
	 * the rule exists to prevent — the same reason {@link #noDirectGetEmbeddingPrefixCalls} exists
	 * for a visibility the compiler already enforces.
	 *
	 * <p><b>Stated POSITIVELY, because forbidding spellings was measured not to work.</b> The first
	 * version asked only for one compiled {@code Pattern} and no {@code \\[} literal, and three of
	 * four ordinary relocations walked through it with the build green — a nested class assembling
	 * the brackets by concatenation, a {@code charAt}/{@code isDigit} scan with no regex at all, and
	 * {@code INLINE_CITATION.matcher} used directly (only a plain single-line
	 * {@code Pattern.compile} was caught). What closes the other three is the first assertion below:
	 * the decode step must be CALLED. The two negatives stay as defence in depth.
	 *
	 * <p>It reads SOURCE TEXT, so it asks that the call be present and not that its result be used:
	 * a dialect written BESIDE a retained {@code citedIndexes} call is out of its reach, and no
	 * behavioural case sees that either. Named rather than papered over — the residue is what a
	 * later rule would have to close.
	 *
	 * <p>It reads the file itself rather than going through {@link #scanForPattern}, which reports
	 * per-line matches across the whole tree: this rule needs a COUNT, one file, and a positive
	 * assertion, none of which that helper expresses. It strips comments through {@link #codeLines}
	 * rather than borrowing that helper's whole-line skip, so a maintainer may record the rejected
	 * alternative in this class's own javadoc — which ADR Decision 59 spells character for
	 * character — without breaking the build.
	 */
	@Test
	public void classCodeFidelityCheckReachesMarkersOnlyThroughTheSharedDecodeStep() throws IOException {
		assertMarkersReachedOnlyThroughTheSharedDecodeStep("ClassCodeFidelityCheck.java", 1,
				"the marker rule has grown a dialect of its own — a regex, a hand-rolled scan, "
						+ "or the shared pattern matched directly — and no behavioural case can see it",
				"ClassCodeFidelityCheck must compile exactly one pattern — ATC_CLASS_CODE. A second "
						+ "one is either a citation-marker dialect (use ChartSearchAiUtils.citedIndexes) "
						+ "or a second compiled reading of the code shape (reuse ATC_CLASS_CODE).");
	}

	/**
	 * The same rule over {@code SafetyFindingCitationExtentCheck}, whose marker reading is the
	 * newest — issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/409">#409</a>. Its
	 * published count is the findings the resolution admitted INTERSECTED with the markers the prose
	 * anchors, so a maintainer who drops the intersection puts the key back where #409 found it —
	 * reporting full coverage for an answer whose prose named one finding fewer — and every
	 * behavioural case that could see it would have to be written again.
	 *
	 * <p>Stated the same way as its neighbour above and for the reason that case's javadoc records:
	 * the POSITIVE assertion is what closes a relocation, the two negatives are defence in depth.
	 * The pattern count is ZERO rather than one — this class compiles none, having no shape of its
	 * own to recognise — so a first {@code Pattern.compile} here is already a dialect.
	 *
	 * <p>The same residue that case names applies unchanged: this reads SOURCE TEXT, so a
	 * dialect written BESIDE a retained {@code citedIndexes} call is out of its reach. And these two
	 * are not the whole family — other classes read the answer's markers, some of them through
	 * {@code INLINE_CITATION} directly because they need a marker's text OFFSET, which CLAUDE.md's
	 * inline-citation rule licenses and this rule would forbid. No count of the guarded ones is
	 * published here; grep {@code citedIndexes(} over {@code api/src/main} for the current set.
	 */
	@Test
	public void safetyFindingCitationExtentCheckReachesMarkersOnlyThroughTheSharedDecodeStep()
			throws IOException {
		assertMarkersReachedOnlyThroughTheSharedDecodeStep("SafetyFindingCitationExtentCheck.java", 0,
				"either the count has stopped asking what the PROSE anchored — the issue #409 "
						+ "defect — or the question has grown a dialect of its own",
				"SafetyFindingCitationExtentCheck must compile no pattern of its own; markers are "
						+ "decoded by ChartSearchAiUtils.citedIndexes.");
	}

	/**
	 * The scan both marker-consumer rules above run, in one place so their needle set cannot drift
	 * apart — a renamed decode step or a third dialect spelling fixed in one copy and not the other
	 * would leave the second blind, and both rules report success by finding nothing.
	 *
	 * <p>Every needle is put to {@link #codeLines}' reading of the line and never to the line itself,
	 * so a note in a comment form that method strips neither satisfies the required call nor trips a
	 * dialect negative; it carries which forms were measured to let a note through before the strip,
	 * and the residues that survive it. The line NUMBER a violation reports is still the raw
	 * file's, which is why the loop indexes both lists.
	 *
	 * @param fileName the source file, as {@code getSourceCache()} keys it
	 * @param expectedCompiles how many patterns the class is allowed to compile — every one of them
	 *            for a shape of its own, never for a marker
	 * @param decodeStepConsequence what a MISSING {@code citedIndexes} call means for this class,
	 *            which differs between them and is the half of the message worth writing twice
	 * @param compileMessage what the class's own patterns are for
	 */
	private void assertMarkersReachedOnlyThroughTheSharedDecodeStep(String fileName,
			int expectedCompiles, String decodeStepConsequence, String compileMessage)
			throws IOException {
		List<String> lines = getSourceCache().get(fileName);
		org.junit.jupiter.api.Assertions.assertNotNull(lines, "precondition: " + fileName
				+ " was not found by the source scan, so this rule would pass vacuously");
		int compiles = 0;
		boolean callsDecodeStep = false;
		List<String> ownDialect = new ArrayList<>();
		List<String> stripped = codeLines(lines);
		for (int i = 0; i < lines.size(); i++) {
			String code = stripped.get(i);
			if (code.trim().isEmpty()) {
				continue;
			}
			if (code.contains("ChartSearchAiUtils.citedIndexes(")) {
				callsDecodeStep = true;
			}
			if (code.contains("Pattern.compile(")) {
				compiles++;
			}
			// A bracketed-digit regex of its own, and the shared pattern read directly instead of
			// through its decode step. Both are marker dialects; neither is caught by the count.
			if (namesAMarkerDialect(code)) {
				ownDialect.add("line " + (i + 1) + ": " + lines.get(i).trim());
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(callsDecodeStep, fileName
				+ " must read citation markers through ChartSearchAiUtils.citedIndexes. If that call is "
				+ "gone, " + decodeStepConsequence + ".");
		org.junit.jupiter.api.Assertions.assertEquals(expectedCompiles, compiles, compileMessage);
		org.junit.jupiter.api.Assertions.assertTrue(ownDialect.isEmpty(), fileName
				+ " must not spell a bracketed regex of its own nor name INLINE_CITATION; markers are "
				+ "decoded by ChartSearchAiUtils.citedIndexes. Found: " + ownDialect);
	}

	/**
	 * Whether {@code code} spells a citation-marker dialect of its own — a bracketed-digit regex, or
	 * the shared pattern named directly instead of reached through its decode step. Hand it
	 * {@link #codeLines}' reading of a line and never the raw line: a note mentioning either
	 * spelling is not a dialect wherever that method strips the note, and its javadoc says which
	 * forms it strips and which residues survive.
	 *
	 * <p><b>The NEEDLES are shared; the polarity is not.</b> Both marker rules and
	 * {@link #theFindingSeverityCheckTakesItsCitedReadingFromTheExtentCheck} forbid these spellings,
	 * so a third dialect added to one copy and not the other would leave the other blind while both
	 * reported success by finding nothing — the drift
	 * {@link #assertMarkersReachedOnlyThroughTheSharedDecodeStep}'s own javadoc exists to prevent,
	 * arriving through the duplicate rather than through a parameter. What is deliberately NOT hoisted
	 * is each rule's required call: the marker rules REQUIRE {@code ChartSearchAiUtils.citedIndexes(}
	 * and the finding-severity rule FORBIDS it, so one signature over both polarities is what that
	 * javadoc rightly refuses.
	 */
	private static boolean namesAMarkerDialect(String code) {
		return code.contains("\\[") || code.contains("INLINE_CITATION");
	}

	/**
	 * The CODE in {@code lines}: comments opened by {@code //} and by {@code /*} removed, string and
	 * character literals kept, and one entry per input line so a caller can still report a line
	 * NUMBER.
	 *
	 * <p><b>The strip is load-bearing rather than tidiness.</b> A rule that reads SOURCE TEXT for a
	 * required call has that assertion satisfied by a comment naming what was just removed, and a
	 * maintainer's {@code was …} note is how the relocation these rules exist to catch actually gets
	 * written. Each arrangement below was measured on 2026-09-14 by replacing
	 * {@code SafetyFindingCitationExtentCheck.citedFindingIndexes} inside
	 * {@code SafetyFindingSeverityFidelityCheck} with a hand-rolled {@code charAt}/{@code isDigit}
	 * marker scan, which reddens
	 * {@link #theFindingSeverityCheckTakesItsCitedReadingFromTheExtentCheck} on its own, and adding a
	 * note naming the removed call:
	 * <ul>
	 * <li>a TRAILING {@code //} note on the replacing line took the whole build GREEN back when this
	 * method skipped a line whose trimmed text BEGINS a comment and scanned every other line whole.
	 * That is the dominant commenting style in the files these rules scan;</li>
	 * <li>a MID-LINE {@code /*} note, closed on the same line, took {@code ArchitectureGuardTest}
	 * green again once {@code //} alone was stripped — and the same trick on
	 * {@code ChartSearchAiUtils.citedIndexes(answer)} in {@code SafetyFindingCitationExtentCheck}
	 * silently restored a private marker dialect there, reddening
	 * {@link #safetyFindingCitationExtentCheckReachesMarkersOnlyThroughTheSharedDecodeStep} only
	 * after the fix;</li>
	 * <li>so did a THREE-LINE block comment whose middle line does not begin with {@code *}, which no
	 * per-line strip can see. That is why this walks the whole file and carries the block state
	 * across lines, and why the earlier heuristics on a line's first characters are gone: with the
	 * opener seen, a javadoc continuation line needs no heuristic to be recognised as comment.</li>
	 * </ul>
	 * Each of those reddens its rule now, re-measured after the fix.
	 *
	 * <p><b>Attacked from the other side too, because a wrong strip reddens a COMPLIANT file and that
	 * is as bad.</b> Also measured: a string literal holding an unclosed {@code /*} ahead of a real
	 * {@code citedIndexes(} call on the same line leaves both rules green, so the literal tracking
	 * keeps a needle a naive strip would have eaten; a character literal holding a double quote, and
	 * a string literal holding an escaped one, do not swallow the {@code //} that follows them on the
	 * same line, each still reddening the finding-severity rule when the shared call is gone; and a
	 * trailing note merely MENTIONING {@code INLINE_CITATION} in an otherwise compliant
	 * {@code SafetyFindingCitationExtentCheck}, which reddened before any strip existed, is green.
	 *
	 * <p><b>The residue, named rather than claimed away: a needle inside a STRING LITERAL counts as
	 * code.</b> Measured the same day, on the fixed strip: a {@code log.debug} line naming
	 * {@code citedFindingIndexes(} beside the hand-rolled scan satisfies the required-call assertion
	 * and the build stays green. Symmetrically, a literal spelling {@code INLINE_CITATION} trips a
	 * dialect negative.
	 * Literals are kept deliberately — a bracketed-digit regex IS a string literal ({@code "\\["}),
	 * so blanking them would take the dialect negatives' own evidence away, and no per-needle policy
	 * is worth the parser.
	 *
	 * <p><b>Nothing here parses Java, and what that costs is measured rather than bounded.</b> The
	 * quote state is declared inside the per-line loop, so it resets at every newline and a
	 * multi-line TEXT BLOCK is not tracked: only the opening {@code """} delimiter line is
	 * quote-scanned, and every body line is read as ordinary CODE. Measured 2026-09-14 by driving
	 * this method from a throwaway same-package test — a {@code //} in a body line was stripped, and
	 * a {@code /*} in one opened block state that blanked the {@code """} terminator line and every
	 * line after it. This project sets {@code maven.compiler.source} to 11 and text blocks are Java
	 * 15+, so nothing under the scanned tree can hold one at this source level; a source-level bump
	 * is what makes it reachable, and everything after such a body line would then be blank to these
	 * rules — reddening a required-call assertion on compliant code and blinding a dialect negative
	 * over the same region, a wrong strip failing in both directions at once. Unicode escapes are
	 * not processed either, so a needle in a comment whose opening slashes are written as unicode
	 * escapes counts as code: measured the same way, such a line comes back byte-identical, while
	 * javac translates unicode escapes before it lexes and reads the line as a comment. Separately,
	 * an unclosed block comment blanks every line after the one it opens on.
	 *
	 * <p><b>It stays APART from {@code ChartSearchAiUncorroboratedChartMatchTest.liveCode}</b>, the
	 * omod-side stripper whose javadoc records the block form as the defect stripping {@code //}
	 * exists to close — read that one before touching this. Two reasons, either sufficient: they are
	 * in different Maven modules and this class is not on omod's test classpath, so sharing means
	 * publishing an api test-jar to hold a comment stripper; and they keep different things. That one
	 * strips one extracted method BODY and counts occurrences in it, and drops literals with the
	 * fail-open consequence its javadoc names; this one keeps literals, because a dialect negative's
	 * own evidence IS a string literal, and keeps one entry per line so a violation can name one.
	 */
	private static List<String> codeLines(List<String> lines) {
		List<String> stripped = new ArrayList<>(lines.size());
		boolean inBlock = false;
		for (String line : lines) {
			StringBuilder code = new StringBuilder(line.length());
			char quote = 0;
			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);
				if (inBlock) {
					if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
						inBlock = false;
						i++;
					}
				}
				else if (quote != 0) {
					code.append(c);
					if (c == '\\' && i + 1 < line.length()) {
						code.append(line.charAt(++i));
					}
					else if (c == quote) {
						quote = 0;
					}
				}
				else if (c == '"' || c == '\'') {
					quote = c;
					code.append(c);
				}
				else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
					break;
				}
				else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
					inBlock = true;
					i++;
				}
				else {
					code.append(c);
				}
			}
			stripped.add(code.toString());
		}
		return stripped;
	}

	/**
	 * {@code SafetyFindingSeverityFidelityCheck} decides which findings the answer cited by asking
	 * {@code SafetyFindingCitationExtentCheck.citedFindingIndexes}, and never by a reading of its
	 * own — issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/409">#409</a>, round
	 * two. Both keys answer one question, and before this the accusation key answered it off
	 * {@code extractCitedReferences}' union while the count answered it off the markers, so one
	 * response could state that the answer never cited a finding AND that it cited that finding and
	 * dropped its rating.
	 *
	 * <p><b>It does NOT reuse {@link #assertMarkersReachedOnlyThroughTheSharedDecodeStep}</b>, and
	 * the reason is that helper's own javadoc: it exists in one place so its two callers' needle set
	 * cannot drift apart, and a third caller requiring a DIFFERENT needle is that drift arriving by
	 * parameter. Its name would also be false here — this class reaches no markers at all, which is
	 * the point. What the two rules do share is the comment strip, and that one IS hoisted —
	 * {@link #codeLines}, so a comment form closed in one copy cannot be left open in the other. What
	 * stays duplicated is each rule's own loop and needles, because one signature over both
	 * polarities is what would put two needle sets behind it.
	 *
	 * <p><b>Stated POSITIVELY, for the reason its neighbours record:</b> forbidding spellings alone
	 * let three of four ordinary relocations through with the build green, and what closes them is
	 * that the entry point must be CALLED. The two dialect negatives are defence in depth — a
	 * maintainer who re-derives the reading here would most likely reach for markers to do it.
	 *
	 * <p>Nothing behavioural can pin the relocation itself: a local marker scan, or a
	 * {@code citedIndexes} call spelled here, answers identically on every case in
	 * {@code SafetyFindingSeverityFidelityTest}, so the suite stays green on exactly the regression
	 * this rule exists to prevent. What the behavioural cases DO pin is the answer — drop the skip
	 * and {@code aFindingOnlyTheStructuredArrayNamesIsNotAccusedOfDroppingItsRating} reddens; this
	 * rule is about where the answer comes from.
	 *
	 * <p>Same residue as its neighbours, named rather than papered over: it reads SOURCE TEXT, so it
	 * asks that the call be present and not that its result be used, and a second reading written
	 * BESIDE a retained call is out of its reach. Three comment shapes naming the removed call were
	 * each measured to satisfy it and each is now stripped — a trailing {@code //} note, a mid-line
	 * {@code /*} one closed on the same line, and a three-line block whose middle line does not
	 * begin with {@code *}; {@link #codeLines} carries those measurements, and the residues it does
	 * NOT strip, which include a comment opener written with unicode escapes. A needle inside a STRING
	 * LITERAL satisfies it too, a log line or an assertion message naming
	 * {@code citedFindingIndexes(} being indistinguishable here from a call to it. An earlier
	 * draft of this paragraph called that the CHEAPEST edit satisfying the rule while removing the
	 * reading, and it was not: a {@code was …} note on the replacing line was cheaper and closer to
	 * how the slip that motivated this rule was actually written, which is why that note is now
	 * stripped rather than described. No superlative replaces it — what a later round should do is
	 * write the edit it has in mind and read whether this reddens.
	 */
	@Test
	public void theFindingSeverityCheckTakesItsCitedReadingFromTheExtentCheck() throws IOException {
		String fileName = "SafetyFindingSeverityFidelityCheck.java";
		List<String> lines = getSourceCache().get(fileName);
		org.junit.jupiter.api.Assertions.assertNotNull(lines, "precondition: " + fileName
				+ " was not found by the source scan, so this rule would pass vacuously");
		boolean callsTheReading = false;
		int compiles = 0;
		List<String> ownDialect = new ArrayList<>();
		List<String> stripped = codeLines(lines);
		for (int i = 0; i < lines.size(); i++) {
			String code = stripped.get(i);
			if (code.trim().isEmpty()) {
				continue;
			}
			if (code.contains("SafetyFindingCitationExtentCheck.citedFindingIndexes(")) {
				callsTheReading = true;
			}
			if (code.contains("Pattern.compile(")) {
				compiles++;
			}
			if (namesAMarkerDialect(code) || code.contains("ChartSearchAiUtils.citedIndexes(")) {
				ownDialect.add("line " + (i + 1) + ": " + lines.get(i).trim());
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(callsTheReading, fileName
				+ " must take \"which findings did the answer cite\" from "
				+ "SafetyFindingCitationExtentCheck.citedFindingIndexes. If that call is gone, this "
				+ "key has its own reading again and can accuse a finding the answer never cited — "
				+ "the issue #409 defect, which no behavioural case in this package can see.");
		org.junit.jupiter.api.Assertions.assertEquals(0, compiles, fileName
				+ " must compile no pattern of its own: it recognises no shape, and a first "
				+ "Pattern.compile here is a citation-marker dialect.");
		org.junit.jupiter.api.Assertions.assertTrue(ownDialect.isEmpty(), fileName
				+ " must not read the answer's markers itself — not a bracketed regex, not "
				+ "INLINE_CITATION, and not the shared decode step directly. The reading it needs "
				+ "already applies the issue #305 filter and the blank-answer arm; reaching for "
				+ "markers here re-derives half of it. Found: " + ownDialect);
	}

	/**
	 * Every answer this module builds carries the condition-rule coverage (issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/378">#378</a>) — asked
	 * of the CLASS FILES rather than of the source.
	 *
	 * <p><b>The source form of this guard kept being walked past, and what ended it was changing the
	 * question rather than widening the needle again.</b> Fresh reviewers got a coverage-less answer
	 * past successive versions of it with a qualified {@code new ChartSearchService.ChartAnswer(},
	 * with a COMMENT naming the construction (whose unbalanced bracket made a raw-text depth walk
	 * swallow every real construction after it), with a line wrap inside the qualifier, with a
	 * unicode escape ({@code new \u0043hartAnswer(}), and with a generic witness
	 * ({@code new <String> ChartAnswer(}). Do not read that as the list. Every one of them is a fact
	 * about TYPING, and none survives compilation: javac writes the same constructor descriptor into
	 * the calling class's constant pool for all of them, which is why the question moved here rather
	 * than the pattern getting another alternative.
	 *
	 * <p>So this reads {@code ChartAnswer}'s own constructor descriptors out of its class file, and
	 * then asserts that no other production class references any of them except the widest — the one
	 * ending in {@code DrugReferenceLoad$Coverage}. The descriptors come from the type's own METHOD
	 * TABLE, so every constructor it declares is in the forbidden set whatever its signature: a
	 * review agent added an arity opening on different parameter types and got a coverage-less answer
	 * past an earlier version of this that picked constructors out of the pool by a hardcoded
	 * descriptor PREFIX. It also closes what the source form conceded, that it could only see answers
	 * built in one FILE; this sees every class under {@code api/target/classes}.
	 *
	 * <p><b>What it actually proves is that every answer is built through the WIDEST constructor</b>,
	 * which is not the same as carrying a value: passing a literal {@code null} for that last argument
	 * satisfies this completely. A review round measured it — a fourth answer site calling the widest
	 * constructor with a {@code null} coverage leaves this green — and named it the likeliest escape
	 * of all, since {@code ChartAnswer}'s own telescoping constructors do exactly that. Closing it
	 * means reading the CALLER's bytecode for an {@code aconst_null} in that argument slot, which is a
	 * real instruction walk rather than a constant-pool read; this stops at the descriptor
	 * deliberately. So do not read a green run here as "every answer states a verdict" — it says no
	 * answer was built through a constructor that CANNOT state one.
	 *
	 * <p><b>The rest of the residue, named rather than claimed away.</b> It reads api's output only, because omod
	 * is not compiled when api's tests run — no {@code omod/src/main} class constructs an answer
	 * today, and one that did would be invisible here. It excludes {@code ChartAnswer} itself, whose
	 * telescoping constructors legitimately name every arity. And a class that builds an answer
	 * through a factory rather than a constructor is outside it, and so is one built REFLECTIVELY —
	 * {@code ChartAnswer.class.getConstructor(...).newInstance(...)} names no descriptor in the
	 * caller's pool, and a review agent confirmed it passes. No such factory or reflective
	 * construction exists.
	 *
	 * <p>And it reads BUILD OUTPUT, so it describes what was last compiled — which is why the module's
	 * own rule to measure with {@code mvn -o clean install} from the root binds this guard as much as
	 * any test. Reading compiled output is this repo's own idiom for a rule no behavioural case can
	 * see, rather than a departure from it: {@code ChartSearchAiReferenceGroundingWithholdingTest}
	 * reads every class file the controller compiles to and fails the build on a hardcoded
	 * resource-type name, and ADR Decision 40 cites it as the precedent for pinning a behaviour-neutral
	 * rule structurally. What that one needs from the pool is a NAME; what this one needs is a
	 * DESCRIPTOR. Neither needs a bytecode parser.
	 */
	@Test
	public void everyAnswerThisModuleBuildsCarriesTheConditionRuleCoverage() throws IOException {
		Path classes = ModuleSourceRoot.apiRoot().resolve("target/classes");
		org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isDirectory(classes),
				"no " + classes + "; a guard that discovers nothing forbids nothing");
		Path holder = classes.resolve(
				"org/openmrs/module/chartsearchai/api/ChartSearchService$ChartAnswer.class");
		org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(holder),
				"no ChartAnswer class file at " + holder + ", so this guard would forbid nothing");

		List<String> constructors = constructorDescriptors(holder);
		org.junit.jupiter.api.Assertions.assertTrue(constructors.size() > 1,
				"expected ChartAnswer to publish several constructor arities and found "
						+ constructors.size() + "; with one there is nothing for a coverage-less answer "
						+ "to be built through and this guard is vacuous");
		List<String> widest = new ArrayList<>();
		for (String descriptor : constructors) {
			if (descriptor.contains(COVERAGE_TYPE)) {
				widest.add(descriptor);
			}
		}
		org.junit.jupiter.api.Assertions.assertEquals(1, widest.size(),
				"exactly one ChartAnswer constructor may take the condition-rule coverage — it is the "
						+ "widest, and every shorter one states null. Found " + widest.size() + ".");

		List<String> violations = new ArrayList<>();
		int callers = 0;
		try (java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(classes)) {
			for (Path file : tree.filter(f -> f.toString().endsWith(".class"))
					.filter(f -> !f.equals(holder)).collect(java.util.stream.Collectors.toList())) {
				List<String> pool = constantPoolStrings(file);
				if (!pool.contains(CHART_ANSWER_TYPE)) {
					continue;
				}
				callers++;
				for (String entry : pool) {
					if (constructors.contains(entry) && !entry.contains(COVERAGE_TYPE)) {
						violations.add(classes.relativize(file) + " builds " + entry);
					}
				}
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(callers > 0,
				"no production class outside ChartAnswer even NAMES it, so this guard just passed by "
						+ "finding nothing to check — read " + classes + " before trusting it");
		org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty(),
				"every answer this module builds must carry the condition-rule coverage (issue #378); "
						+ "one that does not states null on a key the README documents as always "
						+ "present, and the ungrounded answer is the one a streaming user sees. "
						+ "Found: " + violations);
	}

	/**
	 * {@code DrugReferenceInjector.inject} has exactly ONE arity, and that is what keeps the
	 * chart-read verdict reaching the answer (issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a>).
	 *
	 * <p>The sink carrying that verdict was added by WIDENING the signature rather than by adding an
	 * overload beside it, so every stale test double became a compile error instead of a green test
	 * that stubs nothing. Add a narrower arity back — for a caller that "does not need the sink",
	 * which is the natural next edit — and the two coexist quietly: production keeps calling the wide
	 * one, doubles keep overriding whichever they were written against, and the ones that picked the
	 * narrow one go inert without failing. That is not hypothetical on this codebase;
	 * {@code DrugSafetyValidator.validate}'s javadoc records it happening, and records that a review
	 * of the commit that added the overload did not find it.
	 *
	 * <p>Structural rather than behavioural because nothing observable separates the two worlds on
	 * the day the overload is added: the spellings are equal and the suite stays green. What changes
	 * is only what a LATER edit can do silently.
	 *
	 * <p>Reflection rather than the class-file walk beside it, which is the idiom this tree already
	 * uses for "what does this class declare" and needs no descriptors. It counts DECLARED methods
	 * of that name, so a private helper called {@code inject} trips it too. Deliberate: the rule is
	 * that this name is one entry point.
	 */
	@Test
	public void theInjectorExposesExactlyOneInjectArity() {
		List<String> arities = new ArrayList<>();
		for (java.lang.reflect.Method method : org.openmrs.module.chartsearchai.reference
				.DrugReferenceInjector.class.getDeclaredMethods()) {
			if ("inject".equals(method.getName())) {
				arities.add(java.util.Arrays.toString(method.getParameterTypes()));
			}
		}
		org.junit.jupiter.api.Assertions.assertFalse(arities.isEmpty(),
				"no method named inject was found on DrugReferenceInjector at all, so this guard "
						+ "just passed by discovering nothing");
		org.junit.jupiter.api.Assertions.assertEquals(1, arities.size(),
				"DrugReferenceInjector.inject must have exactly one arity, so a test double written "
						+ "against a stale signature fails to compile rather than going silently inert on "
						+ "the production path (issue #247). Found: " + arities);
	}

	/**
	 * @return the descriptor of every constructor {@code classFile} DECLARES, read from its method
	 *         table rather than picked out of the constant pool by shape.
	 *
	 *         <p>The distinction is the guard's whole correctness. Selecting pool strings by a
	 *         descriptor prefix hardcodes the leading parameter types, so a constructor added with a
	 *         different signature never joins the forbidden set and its callers are never checked —
	 *         measured by a review agent, which added such an arity plus a caller and left the guard
	 *         green. The method table has no such blind spot, and it costs one more walk: past the
	 *         pool, the access flags, this/super, the interfaces and the fields, skipping each
	 *         attribute by its own declared length.
	 */
	private static List<String> constructorDescriptors(Path classFile) throws IOException {
		java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(classFile));
		List<String> pool = readConstantPool(in);
		in.position(in.position() + 6);
		// Read the count into a local FIRST: getShort() advances the buffer, and the argument to
		// position(...) evaluates in.position() before it does — so the inline form silently loses the
		// two bytes the count itself occupies. That is what this walk got wrong on its first run, and
		// it surfaced as an attribute length read out of the middle of a method body.
		int interfaces = in.getShort() & 0xFFFF;
		in.position(in.position() + 2 * interfaces);
		skipFields(in);
		List<String> descriptors = new ArrayList<>();
		int methods = in.getShort() & 0xFFFF;
		for (int i = 0; i < methods; i++) {
			in.getShort();
			String name = pool.get(in.getShort() & 0xFFFF);
			String descriptor = pool.get(in.getShort() & 0xFFFF);
			skipAttributes(in);
			if ("<init>".equals(name)) {
				descriptors.add(descriptor);
			}
		}
		return descriptors;
	}

	/** Skips the field table, whose entries have a method's shape: access, name, descriptor, attributes. */
	private static void skipFields(java.nio.ByteBuffer in) {
		int fields = in.getShort() & 0xFFFF;
		for (int i = 0; i < fields; i++) {
			in.position(in.position() + 6);
			skipAttributes(in);
		}
	}

	/** Skips an attribute table by each attribute's own declared length. */
	private static void skipAttributes(java.nio.ByteBuffer in) {
		int attributes = in.getShort() & 0xFFFF;
		for (int i = 0; i < attributes; i++) {
			in.getShort();
			int length = in.getInt();
			in.position(in.position() + length);
		}
	}

	/**
	 * @return every {@code CONSTANT_Utf8} entry in {@code classFile}'s constant pool, walked by the
	 *         class-file format's own lengths rather than scanned for. A regex over the raw bytes runs
	 *         past the end of a descriptor into whatever follows it in the pool — measured while
	 *         writing this, where it turned three unrelated method descriptors into one 629-character
	 *         string. Long-and-double entries take two pool slots, which is the one thing a walk like
	 *         this gets wrong if it does not know it.
	 */
	private static List<String> constantPoolStrings(Path classFile) throws IOException {
		List<String> slots = readConstantPool(
				java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(classFile)));
		List<String> present = new ArrayList<>();
		for (String slot : slots) {
			if (slot != null) {
				present.add(slot);
			}
		}
		return present;
	}

	/**
	 * Reads the constant pool and leaves {@code in} positioned immediately after it, so a caller that
	 * needs the method table can carry on from there.
	 *
	 * @return the pool BY SLOT, with a null wherever the entry is not a {@code CONSTANT_Utf8}. Slot
	 *         indexes are 1-based in the class file and 0-based here, so slot {@code n} is element
	 *         {@code n - 1}. Returning only the strings, in encounter order, would be the obvious
	 *         shape and is wrong for the method table, whose name and descriptor indexes are SLOT
	 *         numbers — every non-Utf8 entry between them would shift the answer.
	 */
	private static List<String> readConstantPool(java.nio.ByteBuffer in) {
		in.position(8);
		int count = in.getShort() & 0xFFFF;
		List<String> slots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			slots.add(null);
		}
		for (int slot = 1; slot < count; slot++) {
			int tag = in.get() & 0xFF;
			switch (tag) {
				case 1:
					byte[] utf = new byte[in.getShort() & 0xFFFF];
					in.get(utf);
					slots.set(slot, new String(utf, java.nio.charset.StandardCharsets.UTF_8));
					break;
				case 7: case 8: case 16: case 19: case 20:
					in.position(in.position() + 2);
					break;
				case 15:
					in.position(in.position() + 3);
					break;
				case 5: case 6:
					in.position(in.position() + 8);
					slot++;
					break;
				default:
					in.position(in.position() + 4);
					break;
			}
		}
		return slots;
	}

	// --- Infrastructure ---

	/** Cache of file name → lines, populated once by {@link #loadAllSources}. */
	private static java.util.Map<String, List<String>> sourceCache;

	/**
	 * Most rules in this class scan this map, so an EMPTY or WRONG map made all of those
	 * pass vacuously — a structural guard that reads nothing reports no violations. That was not
	 * hypothetical: forcing {@link ModuleSourceRoot#apiRoot()} to an unrelated directory USED TO
	 * leave this class entirely green. It no longer does; the cache asserts its own sanity before
	 * any rule reads it, and the same mutation now reddens the rules that read it.
	 *
	 * <p><b>A rule that reads its own tree rather than this cache owes itself both assertions
	 * inline</b>, and needs both: existence alone is not enough, because the sibling {@code omod}
	 * module has the same package path, so a root pointed there exists and reads the wrong tree. No
	 * count of such rules is published here and none should be — look for the pair in any rule that
	 * reads its own tree, rather than for a list of which ones do. The two that do today read
	 * {@code target/classes}, and each carries the pair as a canary on what it FOUND rather than on
	 * the root merely existing, which is the stronger form: a wrong root reads something.
	 */
	private static java.util.Map<String, List<String>> getSourceCache()
			throws IOException {
		if (sourceCache == null) {
			sourceCache = new java.util.LinkedHashMap<>();
			Files.walkFileTree(SRC_ROOT, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file,
						BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".java")) {
						sourceCache.put(file.getFileName().toString(),
								Files.readAllLines(file));
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		org.junit.jupiter.api.Assertions.assertFalse(sourceCache.isEmpty(),
				"precondition: the source scan found no .java files under " + SRC_ROOT + " — every rule "
						+ "in this class would pass vacuously, so this fails instead of reporting no "
						+ "violations");
		org.junit.jupiter.api.Assertions.assertTrue(sourceCache.containsKey("LlmProvider.java"),
				"precondition: the source scan did not find LlmProvider.java under " + SRC_ROOT + ", so "
						+ "it is reading the wrong tree — a wrong root scans SOMETHING and every rule "
						+ "then passes on files these rules were never written about");
		return sourceCache;
	}

	/**
	 * The index one past the brace closing the block that opens at {@code openBrace}. Naive by
	 * design: it counts braces and knows nothing of strings, chars or comments, which is why its
	 * caller names the two signatures it may be asked about rather than scanning for methods.
	 */
	private static int endOfBody(String source, int openBrace) {
		int depth = 0;
		for (int i = openBrace; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
		}
		return source.length();
	}

	/** The 1-based line number of {@code offset} in {@code source}, for a violation a reader has to
	 *  be able to find. */
	private static int lineOf(String source, int offset) {
		int line = 1;
		for (int i = 0; i < offset; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static List<String> scanForPattern(Path root, Pattern pattern,
			String excludeFiles, String message) throws IOException {
		List<String> violations = new ArrayList<>();
		Pattern excludePattern = Pattern.compile(excludeFiles);

		for (java.util.Map.Entry<String, List<String>> entry
				: getSourceCache().entrySet()) {
			String fileName = entry.getKey();
			if (excludePattern.matcher(fileName).find()) {
				continue;
			}
			List<String> lines = entry.getValue();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				// Skip comments and Javadoc
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*")
						|| trimmed.startsWith("/*")) {
					continue;
				}
				if (pattern.matcher(line).find()) {
					violations.add(fileName + ":" + (i + 1)
							+ " — " + message + "\n    " + trimmed);
				}
			}
		}
		return violations;
	}

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(violations.size())
					.append(" architecture violation(s) found:\n\n");
			for (String v : violations) {
				sb.append("  - ").append(v).append("\n");
			}
			sb.append("\nSee the 'API surface rules' of CLAUDE.md, and of "
					+ "api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md for the "
					+ "drug-safety ones, for the correct methods to use.");
			fail(sb.toString());
		}
	}

	/**
	 * {@code PatientClinicalContext.activeDrugOrderReadCompleted()} is a CAUSE, and no second reader
	 * may build a verdict out of it — {@code chartReadForSafety()} is the conjunction of the two
	 * STAMPS and reaches this one through {@code activeDrugOrdersRead()}, which subtracts the order
	 * that was dropped.
	 *
	 * <p><b>Asked of the class files, because the realistic second reader is in another class.</b>
	 * Measured: changing {@code context.activeDrugOrdersRead()} to
	 * {@code context.activeDrugOrderReadCompleted()} at the one place {@code DrugReferenceInjector}
	 * gates its interaction-screen silence note left the whole build green; this case is what reddens
	 * on it now. That note then states
	 * that the reference data relates none of the patient's medications — a negative claim, in
	 * prompt-facing citable evidence, about a list a dropped order is missing from, which is the
	 * "never render silence as denial" rule of the reference package's own instructions reached
	 * fail-open. To reproduce it, note that the gate also requires
	 * {@code screenedSubstances.size() >= 2}, so the chart needs two orders that resolve BESIDE the
	 * dropped one; on a one-order chart the mutation is silent and this case would look vacuous. {@link #scanForPattern}, this class's whole-tree SOURCE rule, cannot express the
	 * question: its exclusions are keyed on file name and would excuse all of
	 * {@code DrugSafetyValidator}, where the allow-list below keys on the class file's PATH, so every
	 * class nested inside that one stays guarded.
	 *
	 * <p><b>What it does not reach.</b> The question is per class FILE, so a SECOND reader inside
	 * {@code DrugSafetyValidator} itself is invisible to it, and so is one inside
	 * {@code PatientClinicalContextBuilder} — which is on the list because the compiler records the
	 * accessor's name in its {@code LocalVariableTable}, not because it calls it, and which therefore
	 * drops off the list entirely under {@code -g:none} (the list being permissive, that is safe).
	 * Test classes are outside the walk, which reads {@code target/classes} only. All are named
	 * rather than guarded; an instruction walk is out of this class's scope for the reason its
	 * neighbours record.
	 */
	@Test
	public void noSecondClassNamesTheOrderReadCause() throws IOException {
		Path classes = ModuleSourceRoot.apiRoot().resolve("target/classes");
		assertTrue(Files.isDirectory(classes),
				"no " + classes + "; a guard that discovers nothing forbids nothing");

		List<String> naming = new ArrayList<>();
		try (java.util.stream.Stream<Path> tree = Files.walk(classes)) {
			for (Path file : tree.filter(f -> f.toString().endsWith(".class"))
					.collect(java.util.stream.Collectors.toList())) {
				if (constantPoolStrings(file).contains(ORDER_READ_CAUSE)) {
					// The relative PATH, as both sibling walks in this class collect it: a bare simple
					// name would excuse a class of that name in any package.
					naming.add(classes.relativize(file).toString());
				}
			}
		}
		assertTrue(naming.contains(
				"org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.class"),
				"the standing surface's operator message is the one legitimate reader of "
						+ ORDER_READ_CAUSE + " and no class file names it, so this guard is comparing "
						+ "nothing against nothing. Found: " + naming);

		List<String> unexpected = new ArrayList<>(naming);
		unexpected.removeAll(MAY_NAME_THE_ORDER_READ_CAUSE);
		assertEquals(new ArrayList<String>(), unexpected,
				unexpected + " names " + ORDER_READ_CAUSE + ", which is a CAUSE and not a stamp. Which "
						+ "of two things to do depends on what you are writing. Building a VERDICT out "
						+ "of it reads TRUE on a pass that completed the order read and then dropped an "
						+ "order from the list, so it certifies a medication list a prescription is "
						+ "missing from — ask activeDrugOrdersRead() instead, which subtracts that "
						+ "case. Writing an operator MESSAGE that has to say WHICH read failed is the "
						+ "legitimate reason to name it, and the stamp cannot answer that, so add the "
						+ "class file to MAY_NAME_THE_ORDER_READ_CAUSE and say here why (issue #421).");
	}
}
