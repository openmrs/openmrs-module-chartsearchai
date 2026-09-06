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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Build-time guard on the project instructions — the root {@code CLAUDE.md} every session
 * reads, and every nested one named in {@link #SIZE_BUDGETS}.
 *
 * <p>It exists because that file rotted once, measurably. It grew from 5 KB to 159 KB in
 * twenty-six days by accreting rejected alternatives, measured ratios and review-round
 * narrative, until 94% of it was one subsystem and a single bullet ran to 39,603 characters
 * — while {@code docs/adr.md} and the javadoc already held the same reasoning. Nothing
 * noticed, because prose has no compiler. The 2026-08-31 trim cut it to 70 KB and added a
 * "Documenting a decision" section saying where a rule goes and where its evidence goes;
 * this test is what stops that section from being the only thing standing against a repeat.
 * It had regrown to its cap within six days of that trim, which is what prompted the
 * 2026-09-06 split: the drug-safety rules were 77% of it and now sit beside their own code.
 *
 * <p>Every check here corresponds to a defect actually found during that trim's review
 * rounds, and each one is a defect that no human reading catches reliably: a pointer that
 * no longer resolves, an identifier elided into ungreppable form, a suite total that went
 * stale the next time someone added a test. The checks are deliberately mechanical. What
 * they cannot see is whether the prose is TRUE — only that its pointers land and its
 * figures are not of the kind that rot.
 *
 * <p>None of these is a style preference. If one fires, the fix is to correct the file, not
 * to relax the rule — except the two that guard a quantity,
 * {@link #theProjectInstructionsStayWithinTheirSizeBudget} and
 * {@link #noBulletCarriesMoreProseThanItsBudget}, whose budgets are tripwires that may be
 * moved deliberately, in this file, with a reason.
 */
public class ProjectInstructionsGuardTest {

	private static final Path REPO_ROOT = ModuleSourceRoot.repoRoot();

	private static final Path ROOT_INSTRUCTIONS = REPO_ROOT.resolve("CLAUDE.md");

	/**
	 * The drug-safety rules, split out of the root file on 2026-09-06 and placed beside the code
	 * they bind. They were 77% of the root file, carried by every session including the ones that
	 * never open this package.
	 */
	private static final Path REFERENCE_INSTRUCTIONS = REPO_ROOT
			.resolve("api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md");

	private static final Path ADR = REPO_ROOT.resolve("docs/adr.md");

	/**
	 * Every instruction file this guard binds, with its own size budget in bytes.
	 *
	 * <p>Trip points for unexplained growth, not targets. Before the 2026-09-06 split there was
	 * one file and one budget, and the budget had been raised twice to keep up with a file that
	 * had regrown to its cap within six days of a trim. Splitting it changed which number matters:
	 * the ROOT budget is the one paid by every session, and it is the one to defend. The reference
	 * budget is paid only by sessions that open that package.
	 *
	 * <p>So do not read the two together as a raise on the old 86,000. The per-session cost of the
	 * instructions fell by roughly three quarters at the split; what these two numbers do is keep
	 * each file from growing back independently.
	 *
	 * <p>Raising either is legitimate and expected eventually. Doing it in the same commit as the
	 * prose that overflowed it is not — that is the accretion, with the tripwire moved out of its
	 * way. Measure with {@code wc -c} on the file itself rather than differencing a figure quoted
	 * in a previous commit; every such figure written here has gone stale.
	 *
	 * <p>One principle sets both: headroom of roughly a tenth — a couple of ordinary rules, not a
	 * section. Both were set at the split, a little under a tenth above what each file measured once
	 * {@link #noBulletCarriesMoreProseThanItsBudget} had been satisfied — room for a few rules, not
	 * for a section. Deliberately not tighter, and the first draft of this split got that wrong: the
	 * drug-safety file is where this module's active work adds rules, so a budget tripping on an
	 * ordinary ticket would be raised in the same commit as the prose that overflowed it, which is
	 * the one move the paragraph above calls illegitimate. A tripwire nobody respects catches
	 * nothing. They are ratchets even so: the direction to move them is down, and the root one —
	 * the only one every session pays for — is where that is worth the effort.
	 */
	private static final Map<Path, Integer> SIZE_BUDGETS = budgets();

	private static Map<Path, Integer> budgets() {
		Map<Path, Integer> m = new LinkedHashMap<>();
		m.put(ROOT_INSTRUCTIONS, 23_000);
		m.put(REFERENCE_INSTRUCTIONS, 72_000);
		return m;
	}

	/**
	 * The most prose one bullet may carry, in bytes, excluding its trailing pointer run.
	 *
	 * <p>A ratchet, meant to come down. It is set where it bites without forcing the deletion of
	 * DIRECTIVES: the bullets it reddens carry measured figures, refuted alternatives and
	 * review-round history that {@code docs/adr.md} and the cited javadoc already hold, which is
	 * exactly what the "Documenting a decision" section says must live there instead.
	 *
	 * <p>Two things this cap deliberately does not measure, both learned by measuring before
	 * choosing the number. It excludes the pointer tail after the last {@code →}, because those
	 * ADR and test-name citations are the pointers the rest of this guard exists to keep resolving
	 * — capping a bullet's total length prices its pointers as if they were prose and pressures
	 * their removal. And it is per BULLET, so it can be satisfied by splitting one bullet into two
	 * rather than by cutting anything; that is a real limitation, and the file-level budgets above
	 * are what stands behind it.
	 *
	 * <p>It sits close to the largest bullets, and that is not the oversight it looks like beside
	 * the file budgets above, which carry about a tenth of headroom. The two differ in what a
	 * maintainer DOES when they fire. A file over budget is answered by raising a number, so slack
	 * is what keeps that number honest. A bullet over budget is answered by moving its evidence to
	 * the decision it already cites, or by giving each of its rules a sub-bullet — the shape the
	 * instructions prescribe anyway. That remedy improves the file, so tripping this is productive
	 * where tripping the other is friction. Do not buy quiet here by raising the cap.
	 */
	private static final int MAX_BULLET_PROSE_BYTES = 1_600;

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
	 * Every instruction file this guard binds is reachable from the root one. The split put the
	 * drug-safety rules beside their code, on the hope that a tool reading that code picks them up
	 * too. Nothing guarantees it: that behaviour varies by tool, and a session that greps and edits
	 * through shell commands may never open the directory. So the root file must NAME the nested
	 * one; that pointer is the only thing that makes these rules mandatory rather than merely
	 * available, and it is one search-and-replace away from being lost.
	 */
	@Test
	public void everyNestedInstructionFileIsPointedAtFromTheRootFile() throws IOException {
		String root = Files.readString(ROOT_INSTRUCTIONS, StandardCharsets.UTF_8);
		List<String> violations = new ArrayList<>();
		for (Path file : SIZE_BUDGETS.keySet()) {
			if (file.equals(ROOT_INSTRUCTIONS)) {
				continue;
			}
			if (!Files.exists(file)) {
				violations.add("instruction file " + rel(file) + " does not exist");
				continue;
			}
			if (!root.contains(rel(file))) {
				violations.add("CLAUDE.md does not name " + rel(file)
						+ " — an unreferenced instruction file is one no session is told to read");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * No bullet carries more prose than {@link #MAX_BULLET_PROSE_BYTES}, excluding its pointer
	 * tail. This is the check aimed at the shape the growth actually took: not many new rules, but
	 * existing ones swelling as each review round appended what it had just learned, until a single
	 * bullet ran to 39,603 characters and nobody could tell the directive from the argument for it.
	 *
	 * <p>What a firing bullet needs is not compression. It is the split the "Documenting a decision"
	 * section already prescribes — the imperative stays, the measurement and the refuted alternative
	 * move to the ADR decision or the javadoc the bullet already points at. If the pointer does not
	 * exist yet, write it first; deleting evidence that has no other home is how a rule becomes
	 * unfollowable.
	 */
	@Test
	public void noBulletCarriesMoreProseThanItsBudget() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			for (Bullet b : bullets(file.getValue())) {
				int size = b.prose.getBytes(StandardCharsets.UTF_8).length;
				if (size > MAX_BULLET_PROSE_BYTES) {
					violations.add(rel(file.getKey()) + " line " + b.line + ": bullet carries " + size
							+ " bytes of prose, over the " + MAX_BULLET_PROSE_BYTES + "-byte budget by "
							+ (size - MAX_BULLET_PROSE_BYTES) + " — \"" + b.title()
							+ "\". Move its evidence to docs/adr.md or to the javadoc it cites; keep the directive.");
				}
			}
		}
		assertNoViolations(violations);
	}

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
		Pattern p = Pattern.compile("\\b(?:all\\s+)?\\d{3,5}(?:\\s*/\\s*\\d{3,5})?\\s+(?:api\\s+|unit\\s+)?"
				+ "(?:tests?|test\\s+methods|cases)\\b|\\b\\d{3,5}\\s+of\\s+\\d{3,5}\\s+tests?\\b",
				Pattern.CASE_INSENSITIVE);
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			Matcher m = p.matcher(file.getValue());
			while (m.find()) {
				violations.add(rel(file.getKey()) + ": suite total \"" + m.group().trim()
						+ "\" — counts of tests go stale; state the named test instead, or put the figure in docs/adr.md with its date");
			}
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
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			Matcher m = Pattern.compile("`[^`\\n]*`").matcher(file.getValue());
			while (m.find()) {
				String span = m.group();
				boolean elided = span.contains("...") || span.contains("…");
				if (elided && !LEGITIMATE_ELISION.matcher(span).find()) {
					violations.add(rel(file.getKey()) + ": elided identifier " + span
							+ " — spell it in full so it can be grepped, or it is not a pointer");
				}
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
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			Set<String> cited = new TreeSet<>();
			Matcher m = Pattern.compile("`\\.?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\(?\\)?`")
					.matcher(file.getValue());
			while (m.find()) {
				cited.add(m.group(1));
			}
			for (String symbol : cited) {
				String last = symbol.substring(symbol.lastIndexOf('.') + 1);
				if (last.length() <= 6) {
					continue;
				}
				if (!corpus.contains(last) && !corpus.contains(symbol)) {
					violations.add(rel(file.getKey()) + ": cited symbol `" + symbol + "` resolves nowhere in "
							+ SOURCE_ROOTS + " — a pointer that does not land is worse than none");
				}
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every cited file path resolves from the repository root. Two did not after the trim —
	 * {@code config.xml} (really {@code omod/src/main/resources/config.xml}) and a fixture
	 * directory written relative to the eval harness rather than the repo. A reader greps
	 * from the root, so a path that only resolves from somewhere else is a dead pointer.
	 *
	 * <p>This binds the nested instruction file exactly as it binds the root one, and that is
	 * load-bearing rather than incidental: a rule sitting in a package is the one most likely to
	 * be written with a path relative to its own directory.
	 */
	@Test
	public void everyCitedPathResolvesFromTheRepositoryRoot() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			Matcher m = Pattern.compile("`([A-Za-z0-9_][A-Za-z0-9_./-]*(?:\\.(?:py|md|xml|json)|/))`")
					.matcher(file.getValue());
			while (m.find()) {
				String path = m.group(1);
				if (!Files.exists(REPO_ROOT.resolve(path))) {
					violations.add(rel(file.getKey()) + ": cited path `" + path
							+ "` does not exist — write it relative to the repository root");
				}
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every "ADR Decision N" the instructions defer to exists in {@code docs/adr.md}. The
	 * trim moved the evidence into those decisions and left the citations behind as the only
	 * route to it, so a citation naming a decision that was renumbered or never written
	 * silently strands the reasoning it was standing in for.
	 *
	 * <p>The "none cited at all" arm is asked of the files TOGETHER. After the split the root file
	 * cites no decision of its own, and per-file that arm would fire on it forever.
	 */
	@Test
	public void everyCitedAdrDecisionExists() throws IOException {
		String adr = Files.readString(ADR, StandardCharsets.UTF_8);
		List<String> violations = new ArrayList<>();
		Set<Integer> all = new LinkedHashSet<>();
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			Set<Integer> cited = new LinkedHashSet<>();
			Matcher block = Pattern.compile("ADR Decisions?\\s+([0-9][0-9,\\s]*(?:and\\s+\\d+)?)")
					.matcher(file.getValue());
			while (block.find()) {
				Matcher num = Pattern.compile("\\d+").matcher(block.group(1));
				while (num.find()) {
					cited.add(Integer.parseInt(num.group()));
				}
			}
			all.addAll(cited);
			for (Integer n : cited) {
				if (!adr.contains("\n## Decision " + n + ":")) {
					violations.add(rel(file.getKey()) + ": cited ADR Decision " + n
							+ " has no heading in docs/adr.md");
				}
			}
		}
		if (all.isEmpty()) {
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
		for (Map.Entry<Path, String> file : eachInstructionFile().entrySet()) {
			String[] lines = file.getValue().split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (count(lines[i], "`") % 2 != 0) {
					violations.add(rel(file.getKey()) + " line " + (i + 1)
							+ ": unbalanced ` — a code span runs past its rule");
				}
				if (count(lines[i], "**") % 2 != 0) {
					violations.add(rel(file.getKey()) + " line " + (i + 1)
							+ ": unbalanced ** — a bold run runs past its rule");
				}
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Each instruction file stays within its own size budget. This is the only check here that
	 * guards a quantity rather than a broken pointer, and it is the one aimed squarely at
	 * what actually happened: 30-fold growth in under a month, none of it reviewed as growth
	 * because each commit added only a paragraph.
	 *
	 * <p>Raising a budget in {@link #SIZE_BUDGETS} is legitimate and expected eventually. Doing
	 * it in the same commit as the prose that overflowed it is not — that is the accretion,
	 * with the tripwire moved out of its way.
	 */
	@Test
	public void theProjectInstructionsStayWithinTheirSizeBudget() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Path, Integer> budget : SIZE_BUDGETS.entrySet()) {
			int size = Files.readAllBytes(budget.getKey()).length;
			if (size > budget.getValue()) {
				violations.add(rel(budget.getKey()) + " is " + size + " bytes, over its "
						+ budget.getValue() + "-byte budget by " + (size - budget.getValue())
						+ " — check whether what was added is a RULE (which belongs there) or the EVIDENCE"
						+ " for one (which belongs in docs/adr.md or in the javadoc of the method it"
						+ " constrains). See the 'Documenting a decision' section of CLAUDE.md.");
			}
		}
		assertNoViolations(violations);
	}

	// --- Helpers ---

	/**
	 * One bullet: where it starts, and its text split at the last {@code →} into the prose the
	 * budget measures and the pointer run it does not.
	 */
	private static final class Bullet {

		private final int line;

		private final String prose;

		private Bullet(int line, String prose) {
			this.line = line;
			this.prose = prose;
		}

		private String title() {
			String t = prose.replaceAll("^\\s*- ", "").replace("*", "");
			return t.length() <= 60 ? t : t.substring(0, 60) + "…";
		}
	}

	/**
	 * Every bullet in one file. A bullet runs from its own marker to the next marker at any
	 * depth or to the next heading, so a parent is measured on its own text and each sub-bullet
	 * on its own — nesting is how a rule is broken into parts, not a way to pool a budget.
	 *
	 * <p>A trailing paragraph is charged to the bullet it FOLLOWS, which is the markdown reading
	 * of it. So a parent's closing paragraph written after its sub-bullets lands on the last
	 * sub-bullet rather than on the parent. Two of those existed when this check was written and
	 * both became sub-bullets of their own, which is what they read as anyway; write the next one
	 * that way rather than teaching this method to guess at indentation.
	 */
	private static List<Bullet> bullets(String text) {
		List<Bullet> found = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		int start = -1;
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i <= lines.length; i++) {
			String line = i < lines.length ? lines[i] : "#";
			boolean marker = line.matches("^\\s*- .*");
			boolean heading = line.startsWith("#");
			if ((marker || heading) && start >= 0) {
				found.add(bullet(start, buf.toString()));
				start = -1;
			}
			if (marker) {
				start = i + 1;
				buf = new StringBuilder(line);
			}
			else if (start >= 0) {
				buf.append('\n').append(line);
			}
		}
		return found;
	}

	/**
	 * One bullet, split at its pointer tail.
	 *
	 * <p>The tail is the last {@code ". \u2192 "} — a sentence ending, then the arrow. Matching a
	 * bare arrow is wrong: this file uses one mid-sentence too, for a pipeline ({@code normalize
	 * \u2192 transform \u2192 embed}) and for a call chain ({@code buildChart() \u2192 build()}),
	 * and taking either as a tail measured two bullets as a fraction of their real length. Every
	 * one of the pointer runs is preceded by a sentence ending and none of the prose arrows is.
	 *
	 * <p>A tail written some other way is simply not found, so the whole bullet counts as prose.
	 * That direction is deliberate: an undetected tail makes the budget STRICTER, never looser.
	 */
	private static Bullet bullet(int line, String body) {
		int tail = body.lastIndexOf(". \u2192 ");
		return new Bullet(line, tail >= 0 ? body.substring(0, tail + 1) : body);
	}

	private static Map<Path, String> eachInstructionFile() throws IOException {
		Map<Path, String> m = new LinkedHashMap<>();
		for (Path p : SIZE_BUDGETS.keySet()) {
			m.put(p, Files.readString(p, StandardCharsets.UTF_8));
		}
		return m;
	}

	private static String rel(Path p) {
		return REPO_ROOT.relativize(p).toString();
	}

	/**
	 * The source every cited symbol must resolve in.
	 *
	 * <p>The instruction files are excluded, and that is not tidiness. Since the 2026-09-06 split
	 * one of them lives UNDER {@code api/src}, so without this filter a symbol cited in it would
	 * resolve against its own citation and {@link #everyCitedSymbolResolvesInTheSource} would
	 * silently pass for every name in that file — the check answering itself. It is excluded by
	 * identity rather than by extension, because the {@code eval/} corpus is deliberately included
	 * and a future instruction file might sit anywhere.
	 */
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
						.filter(p -> !SIZE_BUDGETS.containsKey(p.toAbsolutePath().normalize()))
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
