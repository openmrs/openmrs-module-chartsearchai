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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Build-time guard on {@code CLAUDE.md}, the project instructions every session reads.
 *
 * <p>It exists because that file rotted once, measurably. It grew from 5 KB to 159 KB in
 * twenty-six days by accreting rejected alternatives, measured ratios and review-round
 * narrative, until 94% of it was one subsystem and a single bullet ran to 39,603 characters
 * — while {@code docs/adr.md} and the javadoc already held the same reasoning. Nothing
 * noticed, because prose has no compiler. The 2026-08-31 trim cut it to 70 KB and added a
 * "Documenting a decision" section saying where a rule goes and where its evidence goes;
 * this test is what stops that section from being the only thing standing against a repeat.
 *
 * <p>Every check here corresponds to a defect actually found during that trim's review
 * rounds, and each one is a defect that no human reading catches reliably: a pointer that
 * no longer resolves, an identifier elided into ungreppable form, a suite total that went
 * stale the next time someone added a test. The checks are deliberately mechanical. What
 * they cannot see is whether the prose is TRUE — only that its pointers land and its
 * figures are not of the kind that rot.
 *
 * <p>None of these is a style preference. If one fires, the fix is to correct the file, not
 * to relax the rule — except {@link #theProjectInstructionsStayWithinTheirSizeBudget}, whose
 * budget is a tripwire that may be raised deliberately, in this file, with a reason.
 */
public class ProjectInstructionsGuardTest {

	private static final Path REPO_ROOT = ModuleSourceRoot.repoRoot();

	private static final Path INSTRUCTIONS = REPO_ROOT.resolve("CLAUDE.md");

	private static final Path ADR = REPO_ROOT.resolve("docs/adr.md");

	/**
	 * Trip point for unexplained growth, not a target. The file was 70,549 bytes after the
	 * 2026-08-31 trim; this allows roughly 20% of headroom for ordinary additions and fires
	 * well before the file is back to a size nobody reads. Raising it is a decision: do it
	 * here, in one commit, and say in the message what earned the space.
	 *
	 * <p><b>Raised from 85,000 to 86,000 for issue #348</b> (September 2026). #348 adds a rule: the
	 * REFERENT axis of a safety finding's strength clause, plus the unit correction that keeps a
	 * sibling row from making a proposed drug read as current therapy. What was paid for it first,
	 * rather than instead: the EVIDENCE was pruned from four bullets of the same section — the
	 * caution-lead anchor's measured division of labour, #283's contraindication-flip figures, the
	 * premise/conclusion history — every sentence of it already carried verbatim by ADR Decision 37,
	 * which those bullets point at.
	 *
	 * <p><b>The raise still holds, and its two figures are MEASURED at a named head, never
	 * differenced from a previous quotation.</b> Both move with the file, and inside this one PR both
	 * have now moved three times — the merge at {@code fce8dc61}, the round-2 hardening commit, and
	 * the merge of {@code origin/main} @ {@code 6582f2c2}, each of which falsified the figure the
	 * commit before it recorded. So what is written here is the COMMAND and the head, not arithmetic:
	 * {@code wc -c CLAUDE.md} for the file, and {@code grep 'STRENGTH and REFERENT' CLAUDE.md | wc -c}
	 * for the #348 rule bullet, which names the line by its own words rather than by a line number
	 * that moves. At the {@code 6582f2c2} merge those read <b>85,717</b> and <b>1,081</b> bytes,
	 * leaving <b>283</b> under this tripwire. Re-run both before quoting either number again; a
	 * figure here going stale is expected rather than a defect, which is why the commands are the
	 * part that matters.
	 *
	 * <p>The CONCLUSION is what does not move: the bullet is larger than the headroom, so closing
	 * the gap under the old 85,000 tripwire means deleting DIRECTIVES, and the smallest single thing
	 * that would fit is the rule this raise exists for.
	 */
	private static final int MAX_INSTRUCTION_BYTES = 86_000;

	/**
	 * Where a cited symbol may be found. {@code eval/} is not decoration — the safety-probe
	 * scorer is Python, and the instructions name its functions and case lists
	 * ({@code CAUTION_LEAD_CASES}, {@code caution_led}); scoping this to the Java modules
	 * alone reported five false violations when this guard was first prototyped.
	 */
	private static final List<String> SOURCE_ROOTS = List.of("api/src", "omod/src", "eval");

	/**
	 * A backticked span may carry an ellipsis only where the ellipsis is part of a literal
	 * the module really prints. {@code [ATC …]} is the stand-in rendered for an order the
	 * dataset cannot name; it is a value, not an abbreviation of one.
	 */
	private static final Pattern LEGITIMATE_ELISION = Pattern.compile("\\[ATC\\s*[.…]");

	// --- Rules ---

	/**
	 * No test-suite total, in any of the shapes this file has used. Six of them — 1058,
	 * 1171, 1173, 1192, 1347/1350 and 1585 — were quoted as denominators and every one had
	 * gone stale by the time the trim read them, against a suite that then held about 1596
	 * {@code @Test} methods. A stale denominator is worse than no denominator: it tells a
	 * maintainer a measurement still holds when it does not.
	 *
	 * <p>Scoped to counts of TESTS and cases, deliberately. A figure that does not move with
	 * the code — "33 of 329 Bash calls", "939 level-4 names in the WHO index" — is allowed
	 * by the file's own rule and must not fire here.
	 */
	@Test
	public void theProjectInstructionsQuoteNoTestSuiteTotal() throws IOException {
		List<String> violations = new ArrayList<>();
		Matcher m = Pattern
				.compile("\\b(?:all\\s+)?\\d{3,5}(?:\\s*/\\s*\\d{3,5})?\\s+(?:api\\s+|unit\\s+)?"
						+ "(?:tests?|test\\s+methods|cases)\\b|\\b\\d{3,5}\\s+of\\s+\\d{3,5}\\s+tests?\\b",
						Pattern.CASE_INSENSITIVE)
				.matcher(instructions());
		while (m.find()) {
			violations.add("suite total \"" + m.group().trim()
					+ "\" — counts of tests go stale; state the named test instead, or put the figure in docs/adr.md with its date");
		}
		assertNoViolations(violations);
	}

	/**
	 * No identifier abbreviated into a form nobody can grep. Two shipped in the trim's own
	 * first draft and survived several review rounds because a verification regex silently
	 * skipped what it could not parse: a test cited as {@code .aFoldedClassSentenceAbout…}
	 * and a constant as {@code ..._CROSS_REACTIVITY_FILE_PATH}, whose real name is
	 * {@code DEFAULT_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH}. Both looked like pointers
	 * and neither led anywhere.
	 */
	@Test
	public void everyBacktickedIdentifierIsSpelledInFull() throws IOException {
		List<String> violations = new ArrayList<>();
		Matcher m = Pattern.compile("`[^`\\n]*`").matcher(instructions());
		while (m.find()) {
			String span = m.group();
			boolean elided = span.contains("...") || span.contains("…");
			if (elided && !LEGITIMATE_ELISION.matcher(span).find()) {
				violations.add("elided identifier " + span
						+ " — spell it in full so it can be grepped, or it is not a pointer");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every backticked code symbol resolves somewhere in the source. This is what makes the
	 * file pointers rather than prose: a cited method that no longer exists is a rule nobody
	 * can follow to its implementation.
	 *
	 * <p>Matched on the LAST segment and on the whole dotted name, and only for names long
	 * enough to be distinctive — a short one collides with ordinary English and would make
	 * this guard noise. Resolution is anywhere in {@link #SOURCE_ROOTS}, deliberately not
	 * "in a test": several cited names are production methods that merely read like test
	 * names ({@code addPartnersForUnmappedOrders}, {@code extractCitedReferences}), and a
	 * stricter check reported all three as violations when it was tried.
	 */
	@Test
	public void everyCitedSymbolResolvesInTheSource() throws IOException {
		String corpus = sourceCorpus();
		Set<String> cited = new TreeSet<>();
		Matcher m = Pattern.compile("`\\.?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\(?\\)?`")
				.matcher(instructions());
		while (m.find()) {
			cited.add(m.group(1));
		}
		List<String> violations = new ArrayList<>();
		for (String symbol : cited) {
			String last = symbol.substring(symbol.lastIndexOf('.') + 1);
			if (last.length() <= 6) {
				continue;
			}
			if (!corpus.contains(last) && !corpus.contains(symbol)) {
				violations.add("cited symbol `" + symbol + "` resolves nowhere in " + SOURCE_ROOTS
						+ " — a pointer that does not land is worse than none");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every cited file path resolves from the repository root. Two did not after the trim —
	 * {@code config.xml} (really {@code omod/src/main/resources/config.xml}) and a fixture
	 * directory written relative to the eval harness rather than the repo. A reader greps
	 * from the root, so a path that only resolves from somewhere else is a dead pointer.
	 */
	@Test
	public void everyCitedPathResolvesFromTheRepositoryRoot() throws IOException {
		List<String> violations = new ArrayList<>();
		Matcher m = Pattern
				.compile("`([A-Za-z0-9_][A-Za-z0-9_./-]*(?:\\.(?:py|md|xml|json)|/))`")
				.matcher(instructions());
		while (m.find()) {
			String path = m.group(1);
			if (!Files.exists(REPO_ROOT.resolve(path))) {
				violations.add("cited path `" + path + "` does not exist — write it relative to the repository root");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every "ADR Decision N" the instructions defer to exists in {@code docs/adr.md}. The
	 * trim moved the evidence into those decisions and left the citations behind as the only
	 * route to it, so a citation naming a decision that was renumbered or never written
	 * silently strands the reasoning it was standing in for.
	 */
	@Test
	public void everyCitedAdrDecisionExists() throws IOException {
		String adr = Files.readString(ADR, StandardCharsets.UTF_8);
		Set<Integer> cited = new LinkedHashSet<>();
		Matcher block = Pattern.compile("ADR Decisions?\\s+([0-9][0-9,\\s]*(?:and\\s+\\d+)?)").matcher(instructions());
		while (block.find()) {
			Matcher num = Pattern.compile("\\d+").matcher(block.group(1));
			while (num.find()) {
				cited.add(Integer.parseInt(num.group()));
			}
		}
		List<String> violations = new ArrayList<>();
		for (Integer n : cited) {
			if (!adr.contains("\n## Decision " + n + ":")) {
				violations.add("cited ADR Decision " + n + " has no heading in docs/adr.md");
			}
		}
		if (cited.isEmpty()) {
			violations.add("no ADR decision is cited at all — the evidence pointers have been removed");
		}
		assertNoViolations(violations);
	}

	/**
	 * Code spans and bold runs close on the line that opens them. Unbalanced markers do not
	 * fail anything at read time; they silently swallow the rest of a rule into a code span,
	 * which is how a prohibition stops being legible without anyone editing it away.
	 */
	@Test
	public void everyCodeSpanAndBoldRunIsClosed() throws IOException {
		List<String> violations = new ArrayList<>();
		String[] lines = instructions().split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (count(lines[i], "`") % 2 != 0) {
				violations.add("line " + (i + 1) + ": unbalanced ` — a code span runs past its rule");
			}
			if (count(lines[i], "**") % 2 != 0) {
				violations.add("line " + (i + 1) + ": unbalanced ** — a bold run runs past its rule");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * The instructions stay within their size budget. This is the only check here that
	 * guards a quantity rather than a broken pointer, and it is the one aimed squarely at
	 * what actually happened: 30-fold growth in under a month, none of it reviewed as growth
	 * because each commit added only a paragraph.
	 *
	 * <p>Raising {@link #MAX_INSTRUCTION_BYTES} is legitimate and expected eventually. Doing
	 * it in the same commit as the prose that overflowed it is not — that is the accretion,
	 * with the tripwire moved out of its way.
	 */
	@Test
	public void theProjectInstructionsStayWithinTheirSizeBudget() throws IOException {
		int size = Files.readAllBytes(INSTRUCTIONS).length;
		if (size > MAX_INSTRUCTION_BYTES) {
			fail("CLAUDE.md is " + size + " bytes, over the " + MAX_INSTRUCTION_BYTES + "-byte budget by "
					+ (size - MAX_INSTRUCTION_BYTES) + ".\n\n"
					+ "This file is read in full at the start of every session, so its size is a running cost.\n"
					+ "Before raising the budget, check whether what was added is a RULE (which belongs here)\n"
					+ "or the EVIDENCE for one (which belongs in docs/adr.md or in the javadoc of the method it\n"
					+ "constrains). See the 'Documenting a decision' section of CLAUDE.md.");
		}
	}

	// --- Helpers ---

	private static String instructions() throws IOException {
		return Files.readString(INSTRUCTIONS, StandardCharsets.UTF_8);
	}

	private static String sourceCorpus() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String root : SOURCE_ROOTS) {
			Path dir = REPO_ROOT.resolve(root);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(dir)) {
				files.filter(Files::isRegularFile)
						.filter(p -> !p.toString().contains("/target/"))
						.forEach(p -> {
							try {
								sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
							}
							catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
		return sb.toString();
	}

	private static int count(String line, String token) {
		int n = 0;
		int i = line.indexOf(token);
		while (i >= 0) {
			n++;
			i = line.indexOf(token, i + token.length());
		}
		return n;
	}

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(violations.size()).append(" project-instruction violation(s) found:\n\n");
			for (String v : violations) {
				sb.append("  - ").append(v).append("\n");
			}
			sb.append("\nSee the 'Documenting a decision' section of CLAUDE.md: a rule belongs there, ")
					.append("its evidence belongs in docs/adr.md or in javadoc.");
			fail(sb.toString());
		}
	}
}
