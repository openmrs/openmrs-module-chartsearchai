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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * Build-time guard on the ONE resolution of "which row does this response call this substance by"
 * (issue #236), in two needles. {@code interactionSubject} is called from
 * {@code SubstanceSubjects.subjectOf}, from its own two-arity overload, and from
 * {@code addPartnersForUnmappedOrders} — and from nowhere else in {@code DrugSafetyValidator}. And
 * {@code SubstanceSubjects} is CONSTRUCTED once, inside {@code validate}, so that the arms reading it
 * are reading one instance rather than one class.
 *
 * <p><b>What it forbids and why.</b> Five arms of that class name a chip's subject. Issue #206 gave
 * three of them one memoised per-{@code validate} lookup and left the two pairwise arms folding their
 * own narrower row group through {@code canonicalSubjects}; issue #236 deleted that method and pointed
 * the last two at the same lookup. The rule that survives the deletion is a rule about CALLERS —
 * {@code interactionSubject}'s own javadoc states it ("a chip arm looks its subject up through
 * {@code SubstanceSubjects} rather than calling this directly … calling this directly is how an arm ends
 * up folding a narrower row group than its siblings, which is exactly what #206 was") — and until this
 * class existed nothing enforced it. A sixth arm resolving its own subject would reintroduce the split,
 * and the whole complaint of issue #236 is that its safety rested on nothing stated.
 *
 * <p><b>Why the second needle, and why it is not a belt on the first's braces.</b> The caller rule is a
 * rule about {@code interactionSubject}, and the cheapest way to reintroduce #236's defect makes no call
 * to it: re-construct {@code SubstanceSubjects} over the arm's own row group and read {@code subjectOf}
 * off that. That is semantically the deleted {@code canonicalSubjects}, it is now the most natural edit
 * available because this change introduced the class it reuses, and the caller scan is green through it —
 * measured by writing exactly that line into {@code addActiveOrderPairInteractions} and again into
 * {@code addQuestionPairInteractions}. So the two needles forbid different things: one arm may not fold
 * its own group, and no arm may hold its own lookup. Locating the single construction inside
 * {@code validate} is what closes the variant a count alone would not — a per-arm helper returning a
 * fresh instance is one {@code new} in the file and one object per caller.
 *
 * <p><b>Why structural and not behavioural.</b> A second resolution is only OBSERVABLE where the two row
 * groups differ, which needs a substance whose rows publish different alias sets. Measured over the
 * shipped 19 MB KB by driving {@code DdiDrugReferenceSource.parse}, {@code DrugReference.matchesText}
 * and {@code DrugReference.substanceGroupKey}: of its 129 multi-row substances, 22 publish a name that
 * resolves a strict non-empty SUBSET of the family. Putting that subset and the whole family to
 * {@code DrugSafetyValidator.interactionSubject} then elects two different rows on 10 of the 22 —
 * <b>and that figure is a property of the SECOND operand, which has to be stated with it</b>: 10 with
 * {@code Collections.emptyList()} as the recorded names, 0 where the patient's own record names the
 * subset-resolving name, and 22 — every one of them — where it names one of the family's other rows.
 * So the divergence is rare in the KB and ordinary on a chart that names the drug, which is why the
 * shared group carries the ORDER rows too.
 * {@code OrderedSubjectRowTest.theQuestionPairChipNamesTheSubstanceTheOtherArmsName} poses it on a
 * hand-authored fixture, which is also what the chart lever needs — see there.
 * So a new arm resolving separately would agree with its siblings on almost every input and redden
 * nothing. That is the same argument {@code OrderPartnerNameSourceWritePathTest} makes for its own scan,
 * and {@code ArchitectureGuardTest} is this repo's other source-scanning guard.
 *
 * <p><b>The permitted callers, and why each is permitted rather than merely present.</b>
 * <ul>
 *   <li>{@code SubstanceSubjects.subjectOf} — the shared lookup itself. This is the one that must
 *       remain the whole of how a CHIP names a substance.</li>
 *   <li>{@code interactionSubject(List, PatientClinicalContext)} — the two-arity overload, which does
 *       nothing but add {@code recordedDrugNames} and delegate. One method, two arities, never a second
 *       definition; its own javadoc says so.</li>
 *   <li>{@code addPartnersForUnmappedOrders} — the class arm's PARTNER-naming rung (issue #228), a
 *       different question from a chip's SUBJECT and licensed by the same javadoc's "#228 the class
 *       arm's PARTNER too" paragraph. {@code CLAUDE.md} routes partner naming through
 *       {@code partnerLabel}/{@code foldedPartnerLabel}, not through this lookup.</li>
 * </ul>
 *
 * <p><b>What it does NOT cover, stated so the guard is not credited with more than it has.</b> It reads
 * ONE file, and what that costs differs between the two needles rather than being one residue.
 * {@code interactionSubject} is package-private and static, so a new chip arm written in another class
 * of {@code …chartsearchai.reference} could call it and the caller scan would not see it — unlike the
 * write-path precedent, whose two fields are private members of a private nested class, so the compiler
 * covers every other file. That residue is deliberate: the five arms all live in
 * {@code DrugSafetyValidator}, a sixth outside it would be a larger design change than a slip, and the
 * blessed cross-class caller ({@code DrugReferenceInjector.chartAnchoredSubject}, which raises no chip)
 * shows the package boundary is not the rule's edge. The CONSTRUCTION needle has no such residue while
 * the class stays as declared: {@code SubstanceSubjects} is a {@code private static final} nested class,
 * so no other file can name it and the compiler makes the one-file scan complete for that needle — which
 * is why {@link #LOOKUP_DECLARATION} asserts the modifiers and not just the name, so that widening them
 * reddens here rather than silently enlarging what is unscanned.
 *
 * <p>Neither needle says anything about an arm that resolves a subject some OTHER way — by calling
 * {@code DrugReference.canonicalRow} directly, say, or by writing a second lookup class of its own —
 * which is a different needle and a different rule; what bounds that residue is that such an arm still
 * has to fold the group somehow, and the two folds this class knows of are {@code interactionSubject}
 * (scanned) and {@code canonicalRow} (not). Both needles are statements about the source as WRITTEN:
 * rename any of the three permitted methods, the lookup class or {@code validate}'s widest arity, and
 * this fails loudly rather than quietly forbidding nothing, which is the safe direction.
 *
 * <p><b>Why neither test can pass vacuously.</b> For the caller scan: every permitted body is located
 * and asserted before anything is compared, the declaration count is asserted, and the number of calls
 * FOUND is asserted. For the construction scan: the class declaration and {@code validate}'s body are
 * both located through {@link #uniqueOffsetOf}, which fails on absent and on ambiguous alike, and the
 * count of constructions is asserted to be one rather than at most one — a needle matching nothing
 * therefore fails instead of forbidding nothing. Either way a rename, a truncated read or a
 * brace-matching failure fails this class rather than silently enforcing nothing.
 */
public class ChipSubjectOneResolutionTest {

	private static final String RESOLVER = "interactionSubject";

	/**
	 * The three bodies a call to {@link #RESOLVER} may sit in — each a needle that matches the method's
	 * DECLARATION and not a call to it, optionally scoped to an enclosing declaration.
	 *
	 * <p>{@code subjectOf} needs the scope and the guard's own uniqueness check is what found that out:
	 * {@code ContraindicationChips} declares a {@code subjectOf(DrugReference)} of its own that merely
	 * delegates to this one, so the bare needle matched two bodies and could not say which it had
	 * delimited.
	 */
	private static final Map<String, String[]> PERMITTED = new LinkedHashMap<String, String[]>();

	static {
		PERMITTED.put("SubstanceSubjects.subjectOf",
				new String[] { "final class SubstanceSubjects {", "DrugReference subjectOf(DrugReference row) {" });
		PERMITTED.put(RESOLVER + "(List, PatientClinicalContext)",
				new String[] { null,
						"static DrugReference " + RESOLVER + "(List<DrugReference> rows, PatientClinicalContext context) {" });
		PERMITTED.put("addPartnersForUnmappedOrders",
				new String[] { null, "void addPartnersForUnmappedOrders(" });
	}

	/** A call, never a declaration: {@code static DrugReference interactionSubject(} is excluded by
	 *  requiring that the name is not preceded by a return type on the same construct — asserted here by
	 *  matching the name followed by {@code (} and then filtering out the offsets the declaration scan
	 *  found, which is exact rather than heuristic. */
	private static final Pattern CALL = Pattern.compile("\\b" + RESOLVER + "\\s*\\(");

	private static final Pattern DECLARATION =
			Pattern.compile("\\bstatic\\s+DrugReference\\s+" + RESOLVER + "\\s*\\(");

	private static final String RELATIVE_SOURCE =
			"src/main/java/org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.java";

	private static final String CONSTRUCTION = "new SubstanceSubjects(";

	/**
	 * The declaration of the shared lookup, asserted with its MODIFIERS and not by name alone. The
	 * {@code private} is what makes {@link #CONSTRUCTION}'s one-file scan complete rather than merely
	 * convenient (see this class's javadoc), so widening it has to redden here instead of quietly
	 * enlarging what a second construction could be written in.
	 */
	private static final String LOOKUP_DECLARATION = "private static final class SubstanceSubjects {";

	/**
	 * The one arity of {@code validate} that builds the pass's shared state — the other four delegate to
	 * it. The needle stops at the line break so it is a single line of the file as written.
	 */
	private static final String VALIDATE =
			"validate(String answer, String question, PatientClinicalContext rawContext,";

	@Test
	public void onlyTheSharedLookupAndThePartnerRungResolveASubjectDirectly() throws IOException {
		String source = strippedSource();

		List<Integer> declarations = offsetsOf(DECLARATION, source);
		assertEquals(2, declarations.size(),
			"expected exactly the two arities of " + RESOLVER + " to be declared in "
					+ RELATIVE_SOURCE + ", and found " + declarations.size() + ". This guard searches for"
					+ " that name, so a rename or a signature change makes it forbid nothing — update the"
					+ " needles in this class in the same change (issue #236).");

		Map<String, Region> bodies = new LinkedHashMap<String, Region>();
		for (Map.Entry<String, String[]> permitted : PERMITTED.entrySet()) {
			String enclosing = permitted.getValue()[0];
			String needle = permitted.getValue()[1];
			int from = 0;
			int until = source.length();
			if (enclosing != null) {
				int scope = uniqueOffsetOf(source, enclosing, 0, source.length());
				Region body = bodyOf(source, scope, enclosing);
				from = body.start();
				until = body.end();
			}
			int at = uniqueOffsetOf(source, needle, from, until);
			bodies.put(permitted.getKey(), bodyOf(source, at, permitted.getKey()));
		}

		List<Integer> calls = new ArrayList<Integer>();
		for (int at : offsetsOf(CALL, source)) {
			if (!startsADeclaration(declarations, source, at)) {
				calls.add(at);
			}
		}
		assertEquals(3, calls.size(),
			"expected exactly three calls to " + RESOLVER + " in " + RELATIVE_SOURCE + " — one per"
					+ " permitted body — and found " + calls.size() + " at lines " + linesOf(source, calls)
					+ ". A FOURTH is a second answer to \"which row does this response call this substance"
					+ " by\", which is what issue #206 removed for three arms and issue #236 for the other"
					+ " two: a chip arm must read " + PERMITTED.keySet().iterator().next() + " instead, so"
					+ " that a substance cannot be named one thing by one chip and another by the chip"
					+ " beside it. If the new caller is legitimately not a chip arm, add it to PERMITTED"
					+ " with the argument for why.");

		for (int at : calls) {
			String owner = null;
			for (Map.Entry<String, Region> body : bodies.entrySet()) {
				if (body.getValue().contains(at)) {
					owner = body.getKey();
				}
			}
			assertTrue(owner != null,
				"line " + lineOf(source, at) + " calls " + RESOLVER + " outside every permitted body: "
						+ statementAt(source, at) + ". Resolve a chip's subject through"
						+ " SubstanceSubjects.subjectOf, which is the one per-validate answer every arm"
						+ " shares (issues #206/#236); calling this directly folds whatever row group the"
						+ " caller happens to hold, and since issue #175 an arm's own group is never the"
						+ " widest one.");
		}
	}

	/**
	 * The companion needle to {@link #onlyTheSharedLookupAndThePartnerRungResolveASubjectDirectly}, for
	 * the bypass that makes no call to {@link #RESOLVER} at all: re-constructing {@code SubstanceSubjects}
	 * over an arm's own narrower row group, which is the deleted {@code canonicalSubjects} under a new
	 * name and passes the caller guard untouched.
	 */
	@Test
	public void theSharedLookupIsBuiltOnceByValidateAndNeverPerArm() throws IOException {
		String source = strippedSource();

		// Asserted for its own sake and the offset discarded: this is the modifier check that keeps the
		// one-file scan below complete (see this class's javadoc), not a scope for anything.
		uniqueOffsetOf(source, LOOKUP_DECLARATION, 0, source.length());
		Region validateBody =
				bodyOf(source, uniqueOffsetOf(source, VALIDATE, 0, source.length()), VALIDATE);

		List<Integer> constructions = offsetsOfLiteral(source, CONSTRUCTION);
		assertEquals(1, constructions.size(),
			"expected " + RELATIVE_SOURCE + " to construct SubstanceSubjects once — in validate, for the"
					+ " whole pass — and found " + constructions.size() + " at lines "
					+ linesOf(source, constructions) + ". A SECOND construction over an arm's own row group"
					+ " is the deleted canonicalSubjects under a new name: it needs no call to " + RESOLVER
					+ ", so the guard beside this one does not see it, and it reintroduces issue #236 —"
					+ " one substance named two ways in one response, in the clinician's chip and in the"
					+ " citable safety_finding record. An arm must READ the instance validate builds"
					+ " (SubstanceSubjects.subjectOf). If a second instance is legitimately needed, say"
					+ " here what stops it answering differently from the first.");

		assertTrue(validateBody.contains(constructions.get(0)),
			"the one construction of SubstanceSubjects is at line " + lineOf(source, constructions.get(0))
					+ ", outside the body of validate(String, String, PatientClinicalContext, List) — so"
					+ " the arms of a pass may no longer share ONE instance even though the file holds one"
					+ " \"new\": a per-arm helper returning a fresh instance is one construction and many"
					+ " objects, which is issue #236's split with an extra hop. If the construction moved"
					+ " for a good reason, move this needle with it and state what still makes every arm"
					+ " read one instance.");
	}

	/** @return every offset of the literal {@code needle}, which is what the construction scan wants —
	 *          {@link #offsetsOf} reports the offset of {@link #RESOLVER} inside its match and so is
	 *          specific to that one needle. */
	private static List<Integer> offsetsOfLiteral(String source, String needle) {
		List<Integer> found = new ArrayList<Integer>();
		int at = source.indexOf(needle);
		while (at >= 0) {
			found.add(at);
			at = source.indexOf(needle, at + 1);
		}
		return found;
	}

	/**
	 * @return the ONE offset of {@code needle} between {@code from} and {@code until}. Absent and
	 *         ambiguous are both hard failures rather than a best guess: a needle that finds nothing
	 *         leaves this guard forbidding nothing, and one that finds two cannot say which body it
	 *         delimited.
	 */
	private static int uniqueOffsetOf(String source, String needle, int from, int until) {
		int at = source.indexOf(needle, from);
		assertTrue(at >= 0 && at < until,
			"no declaration matching \"" + needle + "\" was found in " + RELATIVE_SOURCE + " within the"
					+ " expected scope, so this guard has no permitted body to compare against and"
					+ " everything below it would be vacuous. Update the needle along with the method"
					+ " (issue #236).");
		int again = source.indexOf(needle, at + 1);
		assertTrue(again < 0 || again >= until,
			"the needle \"" + needle + "\" matched more than once, so this guard cannot say which body it"
					+ " delimited. Narrow it (issue #236).");
		return at;
	}

	/** @return whether the call at {@code at} is in fact one of the {@code declarations} — compared by
	 *          the offset of the NAME, so the two scans cannot disagree about what they matched. */
	private static boolean startsADeclaration(List<Integer> declarations, String source, int at) {
		for (int declaration : declarations) {
			if (source.indexOf(RESOLVER, declaration) == at) {
				return true;
			}
		}
		return false;
	}

	private static List<Integer> offsetsOf(Pattern pattern, String source) {
		List<Integer> found = new ArrayList<Integer>();
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			found.add(matcher.start() + matcher.group().indexOf(RESOLVER));
		}
		return found;
	}

	private static List<Integer> linesOf(String source, List<Integer> offsets) {
		List<Integer> lines = new ArrayList<Integer>();
		for (int at : offsets) {
			lines.add(lineOf(source, at));
		}
		return lines;
	}

	private static int lineOf(String source, int at) {
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static String statementAt(String source, int at) {
		int from = source.lastIndexOf('\n', at) + 1;
		int to = source.indexOf('\n', at);
		return source.substring(from, to < 0 ? source.length() : to).trim();
	}

	/** The brace-delimited body that FOLLOWS {@code from}. Comments and string literals are already
	 *  blanked (see {@link #strippedSource()}), which is what makes counting braces sound. */
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
	 * {@code DrugSafetyValidator.java} with every comment and string literal blanked to spaces, so
	 * offsets and line numbers still line up with the file on disk. Blanked and not deleted because the
	 * failure messages report line numbers, and because a {@code interactionSubject(} written inside a
	 * javadoc is prose and not a call.
	 *
	 * <p>The mechanism mirrors {@code OrderPartnerNameSourceWritePathTest}'s, whose copy is private to
	 * that class and whose file locator is deliberately kept apart from {@link ModuleSourceRoot} (see
	 * that class's javadoc). This one uses {@code ModuleSourceRoot} because it resolves a NAMED file and
	 * asserts it exists, which is the caller shape that locator's javadoc says fails loudly.
	 */
	private static String strippedSource() throws IOException {
		Path file = ModuleSourceRoot.apiRoot().resolve(RELATIVE_SOURCE);
		assertTrue(Files.exists(file),
			"no " + RELATIVE_SOURCE + " under " + ModuleSourceRoot.apiRoot() + "; a guard that reads"
					+ " nothing satisfies every \"is not called here\" assertion by containing nothing");
		char[] text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toCharArray();
		assertTrue(text.length > 10000,
			"only " + text.length + " chars were read from " + file + "; a truncated read forbids nothing");
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

	/** A half-open source region, as {@link #bodyOf} delimits it. */
	private static final class Region {

		private final int start;

		private final int end;

		Region(int start, int end) {
			this.start = start;
			this.end = end;
		}

		boolean contains(int at) {
			return at > start && at < end;
		}

		int start() {
			return start;
		}

		int end() {
			return end;
		}
	}
}
