/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Build-time guard on the ONE write path for {@code OrderPartner.namingOrder} and
 * {@code OrderPartner.namesADrug} (issue #298): both fields are assigned by
 * {@code OrderPartner.recordNameSource} and by nothing else, and the order is admitted there only where
 * the flag says the label is a name.
 *
 * <p><b>Why a structural guard and not a behavioural one.</b> The two fields carry one fact — that a
 * partner's label is a drug NAME, and which active order supplied it. Written independently they could
 * come apart: before #298 the ladder's order rung handed the constructor an order unconditionally while
 * passing {@code displayNamesADrug(order)} as the flag, so a {@code namedByCodesOnly} or blank-display
 * order produced a non-null {@code namingOrder} beside a label that is a bare ATC code or an
 * {@code [ATC …]} stand-in. Nothing observable went wrong, because
 * {@code DrugSafetyValidator.foldedPartnerLabel} asks {@code !namesADrug} first and so never reaches the
 * order branch for such a partner — and that branch order is deliberately KEPT (see that method and ADR
 * Decision 40), which is exactly why the single write path has nothing a behavioural assertion can see.
 * Measured while implementing #298: the write path alone changes no test expectation in the api suite.
 * A rule that is behaviour-neutral by construction is either pinned structurally or not pinned at all,
 * and this repo already takes that route once — {@code ChartSearchAiReferenceGroundingWithholdingTest}
 * scans the controller's compiled class files for a hardcoded resource-type name for the same reason.
 *
 * <p><b>Mechanism: the Java SOURCE, not the class files.</b> The precedent reads bytecode because its
 * needle is a string CONSTANT, which javac inlines into the constant pool. The needle here is the
 * LOCATION of an assignment — which method it sits in — and a class file answers that only through a
 * bytecode parser (a {@code putfield} inside one method's {@code Code} attribute rather than another's);
 * this module has no bytecode library on the test classpath and adding one to ask a question the source
 * answers directly would be the heavier choice. {@code ArchitectureGuardTest} in the api module already
 * establishes source scanning as this repo's second structural mechanism.
 *
 * <p><b>What it does NOT cover.</b> Assignment by reflection, and an assignment written in a form this
 * pattern does not describe (it matches {@code <name> =} and the compound operators, so it sees
 * {@code this.namingOrder = …}, {@code partner.namingOrder = …} and a bare {@code namingOrder = …}; a
 * value smuggled in some other way it would not). It scans one file, which is the whole scope the
 * compiler leaves open: both fields are private members of a private nested class, so only
 * {@code DrugSafetyValidator.java} can compile a write to either, and the compiler covers every other
 * file. It says nothing about whether the gate is the RIGHT way round — {@code namesADrug ? null : order}
 * still names the flag and would pass here, and is caught behaviourally instead, by every case that
 * reads a folded chip's partner name (measured: an inverted gate passes here and reddens two of them).
 * And it is a statement about the source as WRITTEN, so a refactor
 * that renames either field must update the needles; that failure is loud rather than silent, which is
 * the next paragraph.
 *
 * <p><b>Why it cannot pass vacuously</b>, which is the failure the cited precedent exists to prevent —
 * a guard that finds nothing and reports success. Every assertion about the assignments is preceded by
 * one that the scan located what it is talking about: the {@code OrderPartner} class body, the
 * {@code recordNameSource} body inside it, and BOTH field declarations by the exact names the assignment
 * pattern searches for. So a rename, a moved nested class, an unreadable file or a brace-matching failure
 * fails this class loudly instead of quietly forbidding nothing. The number of assignments is asserted
 * too, so a write ADDED beside the two expected ones fails even if it happens to sit inside
 * {@code recordNameSource}.
 */
public class OrderPartnerNameSourceWritePathTest {

	/** The two fields that carry one fact, and the method that is allowed to write them. */
	private static final String NAMING_ORDER = "namingOrder";

	private static final String NAMES_A_DRUG = "namesADrug";

	private static final String WRITER = "recordNameSource";

	/**
	 * An assignment to either field, however qualified: {@code this.namingOrder =},
	 * {@code partner.namesADrug =}, a bare {@code namingOrder =}, and the compound operators for
	 * completeness. {@code =} not followed by {@code =} so that {@code namingOrder != null} and
	 * {@code namesADrug == true} are reads rather than writes.
	 */
	private static final Pattern ASSIGNMENT = Pattern.compile(
			"\\b(" + NAMING_ORDER + "|" + NAMES_A_DRUG + ")\\s*(?:[-+*/%&|^]|<<|>>>?)?=(?!=)");

	private static final String RELATIVE_SOURCE =
			"src/main/java/org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.java";

	@Test
	public void bothNameSourceFieldsAreAssignedOnlyInsideRecordNameSource() throws IOException {
		String source = strippedSource();
		Region partner = bodyOf(source, classDeclaration(source), "the OrderPartner class body");
		Region writer = bodyOf(source, writerDeclaration(source, partner), "recordNameSource's body");
		assertTrue(partner.contains(writer.start),
			WRITER + " was found outside the OrderPartner class body, so this guard is not looking at the"
					+ " method it means to");
		assertDeclarationsFound(source, partner);

		List<Assignment> assignments = assignmentsIn(source);
		assertEquals(2, assignments.size(),
			"expected exactly two assignments in the whole file — one per field, both inside " + WRITER
					+ " — and found " + assignments
					+ ". A THIRD is a second write path even if it sits inside " + WRITER + ": the two"
					+ " fields carry one fact and " + WRITER + " must remain the whole of how it is"
					+ " recorded (issue #298).");
		for (Assignment assignment : assignments) {
			assertTrue(writer.contains(assignment.at),
				"line " + assignment.line + " assigns " + assignment.field + " outside " + WRITER
						+ ": \"" + assignment.statement + "\". Both fields must be written there and only"
						+ " there, so that a non-null " + NAMING_ORDER + " always means the partner's label"
						+ " IS that order's name. A direct write can leave the pair inconsistent, and the"
						+ " fold then hands a bare ATC code to both sentences of one chip — issue #155's"
						+ " defect, doubled by issue #88's fold, and it reaches the prompt as citable"
						+ " safety_finding text through DrugReferenceInjector.renderFinding. Write it"
						+ " through " + WRITER + " instead.");
		}
		assertEquals(2, distinctFieldsIn(assignments),
			"both fields must be assigned, or one of them is being left to a default and this guard is"
					+ " watching a pair that no longer exists: " + assignments);
	}

	/**
	 * The second half of the same rule, asserted separately because it fails for a different reason and a
	 * shared message would describe neither: the write path may exist and still record an order that
	 * supplied no name.
	 *
	 * <p>{@code recordNameSource} must derive {@link #NAMING_ORDER} FROM {@link #NAMES_A_DRUG} rather
	 * than storing the order unconditionally. It is the gate, not the single write path, that makes "a
	 * non-null naming order means the label is that order's name" true — the write path only makes it
	 * true everywhere.
	 */
	@Test
	public void theRecordedOrderIsGatedOnTheFlag() throws IOException {
		String source = strippedSource();
		Region partner = bodyOf(source, classDeclaration(source), "the OrderPartner class body");
		Region writer = bodyOf(source, writerDeclaration(source, partner), "recordNameSource's body");
		assertDeclarationsFound(source, partner);

		int gatesSeen = 0;
		for (Assignment assignment : assignmentsIn(source)) {
			if (!NAMING_ORDER.equals(assignment.field) || !writer.contains(assignment.at)) {
				continue;
			}
			gatesSeen++;
			assertTrue(assignment.rightHandSide().contains(NAMES_A_DRUG),
				"the order recorded by " + WRITER + " must be derived from the " + NAMES_A_DRUG + " flag,"
						+ " and \"" + assignment.statement + "\" does not name it. Storing the order"
						+ " unconditionally is exactly the pre-issue-#298 state: the ladder's order rung then"
						+ " records an order beside a label that is a bare ATC code or an [ATC …] stand-in,"
						+ " and every reader of " + NAMING_ORDER + " has to know foldedPartnerLabel's branch"
						+ " order to stay safe. (This assertion checks that the flag is CONSULTED, not that"
						+ " the gate is the right way round; an inverted gate is caught behaviourally, by the"
						+ " folded-chip cases.)");
		}
		assertEquals(1, gatesSeen,
			"exactly one assignment to " + NAMING_ORDER + " must sit inside " + WRITER + ", and " + gatesSeen
					+ " were found. None means there is no gate here to check, which is a failure and not a"
					+ " pass; more than one means the field is decided in two places inside the one method"
					+ " that is allowed to decide it.");
	}

	/**
	 * Both fields must be DECLARED, by the exact names {@link #ASSIGNMENT} searches for. Without this the
	 * guard's own needles are unpinned: rename a field and the scan finds zero assignments outside the
	 * writer and reports success while enforcing nothing.
	 *
	 * <p>Matched as a member declaration rather than by looking for the name followed by a semicolon,
	 * which {@code this.namesADrug = namesADrug;} also satisfies — the parameter keeps its name when the
	 * field is renamed, so the looser needle would survive exactly the rename it is here to catch.
	 */
	private static void assertDeclarationsFound(String source, Region partner) {
		for (String field : new String[] { NAMING_ORDER, NAMES_A_DRUG }) {
			Matcher declaration = Pattern
					.compile("(?m)^\\s*private\\s+[\\w.<>\\[\\]]+\\s+" + field + "\\s*;")
					.matcher(source);
			assertTrue(declaration.find() && partner.contains(declaration.start()),
				"no field declared \"" + field + "\" was found in the OrderPartner class body. This guard"
						+ " searches for that name, so a rename makes it forbid nothing — update the"
						+ " needles in this class along with the field (issue #298).");
		}
	}

	/** Every assignment to either field in the file, in source order. */
	private static List<Assignment> assignmentsIn(String source) {
		List<Assignment> found = new ArrayList<Assignment>();
		Matcher matcher = ASSIGNMENT.matcher(source);
		while (matcher.find()) {
			found.add(new Assignment(source, matcher.start(), matcher.group(1)));
		}
		return found;
	}

	private static int distinctFieldsIn(List<Assignment> assignments) {
		int naming = 0;
		int flag = 0;
		for (Assignment assignment : assignments) {
			if (NAMING_ORDER.equals(assignment.field)) {
				naming++;
			}
			else {
				flag++;
			}
		}
		return (naming > 0 ? 1 : 0) + (flag > 0 ? 1 : 0);
	}

	/** The offset of {@code OrderPartner}'s declaration, asserted rather than assumed. */
	private static int classDeclaration(String source) {
		int at = source.indexOf("class OrderPartner");
		assertTrue(at >= 0,
			"no \"class OrderPartner\" declaration was found in " + RELATIVE_SOURCE + " (" + source.length()
					+ " chars read). Everything this class asserts would be vacuous.");
		return at;
	}

	/**
	 * The offset of {@code recordNameSource}'s DECLARATION inside {@code partner}. Found by its return
	 * type and not by its name alone: both constructors call it above the point it is declared, so the
	 * bare name would find a call site and the brace matching would then delimit somebody else's body.
	 */
	private static int writerDeclaration(String source, Region partner) {
		int at = source.indexOf("void " + WRITER + "(", partner.start);
		assertTrue(at >= 0 && partner.contains(at),
			"no declaration of " + WRITER + " was found in the OrderPartner class body, so this guard has"
					+ " no permitted write site to compare against");
		return at;
	}

	/**
	 * The brace-delimited body that FOLLOWS {@code from}, by counting braces. Comments and string
	 * literals are already gone (see {@link #strippedSource()}), which is what makes counting sound —
	 * a {@code '{'} inside a comment or a literal would otherwise unbalance it.
	 */
	private static Region bodyOf(String source, int from, String what) {
		int open = source.indexOf('{', from);
		assertTrue(open >= 0, "no opening brace after the declaration of " + what);
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return new Region(open, i);
				}
			}
		}
		throw new AssertionError("braces never balanced while reading " + what + "; the scan cannot say"
				+ " where anything lives, so it must fail rather than guess");
	}

	/**
	 * {@code DrugSafetyValidator.java} with every comment and string literal blanked out to spaces, so
	 * offsets and line numbers still line up with the file on disk.
	 *
	 * <p>Blanked and not deleted because the assertions report line numbers, and because a
	 * {@code namingOrder = } written inside a javadoc or a failure message — this file's own messages
	 * included, were it ever scanned — is prose and not a write. String literals go too: they are the
	 * other place a brace or a quote can unbalance the scan.
	 */
	private static String strippedSource() throws IOException {
		Path file = sourceFile();
		char[] text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toCharArray();
		assertTrue(text.length > 10000,
			"only " + text.length + " chars were read from " + file + "; a truncated read satisfies every"
					+ " \"is not assigned here\" assertion by containing nothing");
		int i = 0;
		while (i < text.length) {
			char c = text[i];
			if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
				while (i < text.length && text[i] != '\n') {
					text[i++] = ' ';
				}
			}
			else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
				text[i++] = ' ';
				text[i++] = ' ';
				while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) {
					if (text[i] != '\n') {
						text[i] = ' ';
					}
					i++;
				}
				if (i < text.length) {
					text[i++] = ' ';
					text[i++] = ' ';
				}
			}
			else if (c == '"' || c == '\'') {
				text[i++] = ' ';
				while (i < text.length && text[i] != c) {
					boolean escape = text[i] == '\\';
					text[i] = ' ';
					i++;
					if (escape && i < text.length) {
						text[i++] = ' ';
					}
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else {
				i++;
			}
		}
		return new String(text);
	}

	/**
	 * {@code DrugSafetyValidator.java}, found by walking up from the working directory — surefire sets it
	 * to the module directory, so the first candidate normally hits. Missing is a hard failure, never a
	 * skip.
	 */
	private static Path sourceFile() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			Path direct = current.resolve(RELATIVE_SOURCE);
			if (Files.exists(direct)) {
				return direct;
			}
			Path api = current.resolve("api").resolve(RELATIVE_SOURCE);
			if (Files.exists(api)) {
				return api;
			}
			current = current.getParent();
		}
		throw new AssertionError("could not locate " + RELATIVE_SOURCE + " from "
				+ Paths.get("").toAbsolutePath() + "; this guard must fail rather than pass on a file it"
				+ " never read");
	}

	/** A half-open source range, by character offset. */
	private static final class Region {

		private final int start;

		private final int end;

		private Region(int start, int end) {
			this.start = start;
			this.end = end;
		}

		private boolean contains(int offset) {
			return offset >= start && offset <= end;
		}
	}

	/** One assignment to one of the two fields, with enough context to report and to read its gate. */
	private static final class Assignment {

		private final int at;

		private final int line;

		private final String field;

		private final String statement;

		private Assignment(String source, int at, String field) {
			this.at = at;
			this.field = field;
			int lineNumber = 1;
			for (int i = 0; i < at; i++) {
				if (source.charAt(i) == '\n') {
					lineNumber++;
				}
			}
			this.line = lineNumber;
			int semicolon = source.indexOf(';', at);
			int begin = source.lastIndexOf('\n', at) + 1;
			this.statement = source.substring(begin, semicolon < 0 ? source.length() : semicolon + 1).trim();
		}

		/** Everything between the {@code =} and the statement's {@code ;} — the gate, where there is one. */
		private String rightHandSide() {
			int equals = statement.indexOf('=');
			return equals < 0 ? "" : statement.substring(equals + 1);
		}

		@Override
		public String toString() {
			return "line " + line + ": " + statement;
		}
	}
}
