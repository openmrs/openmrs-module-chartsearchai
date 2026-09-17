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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The structural half of issues #444 and #449: the two sites that fetch a MODEL — the container
 * entrypoint and the standalone release pipeline — name an immutable revision and an id in
 * {@code model-manifest.tsv}, and spell no Hugging Face URL of their own.
 *
 * <p>Those two sites are the scope both findings draw, and it is narrower than "everything this
 * project downloads" — ADR Decision 103 names what is left out and why.
 *
 * <p><b>This reads the SOURCE, and that is the point.</b> {@link ModelDownloadIntegrityTest} shows
 * that the shared library refuses substituted bytes; it cannot show that the two call sites still
 * ASK it to. A {@code curl} added beside a retained {@code fetch_and_verify} call, a revision
 * relaxed back to {@code resolve/main}, or a verification moved to after the rename all leave that
 * suite green and reopen the finding. This module pins that class of rule structurally — the same
 * argument {@code ActiveOrderInteractionPhraseTest} makes for reading source, and the same
 * two-channel split: one channel asks whether the right thing is named, the other whether it is
 * named in the right PLACE, because a site that named the library and then renamed the file anyway
 * would satisfy the first alone.
 *
 * <p><b>Every scan asserts it found something.</b> A guard that walks looking for violations reports
 * none when it has scanned nothing at all, and passing for that reason is indistinguishable from
 * passing because the code is right. Each check here ends by asserting what it actually read.
 */
public class ModelDownloadPinningGuardTest {

	/**
	 * A Hugging Face download URL, captured as owner/repo, revision and path. Matching the revision
	 * loosely and asserting on it afterwards is deliberate: a pattern that only matched 40-hex
	 * revisions would find nothing in a file that had regressed to {@code resolve/main}, and report
	 * no violation.
	 */
	private static final Pattern HF_RESOLVE = Pattern
			.compile("https://huggingface\\.co/([^/\\s]+/[^/\\s]+)/resolve/([^/\\s]+)/(\\S+)");

	private static final Pattern PINNED_REVISION = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/** What the container entrypoint provisions: both served models and the querystore embedder. */
	private static final List<String> ENTRYPOINT_ARTIFACTS = List.of("llm-gemma-4-e4b", "llm-gemma-4-e2b",
			"embedder-e5-base-v2-onnx", "embedder-e5-base-v2-vocab");

	/** What the standalone bundle ships. The E2B standby is deliberately not among them. */
	private static final List<String> BUNDLE_ARTIFACTS = List.of("llm-gemma-4-e4b", "embedder-e5-base-v2-onnx",
			"embedder-e5-base-v2-vocab");

	/**
	 * The artifacts the module cannot start without, so a refusal of one must stop the start. The
	 * LLM weights are deliberately NOT among them — they are fetched in the background so OpenMRS
	 * can come up without them, and the comment above {@code _download_llm_file} says why.
	 *
	 * <p>Declared rather than matched by substring, so adding a third must-have artifact costs an
	 * entry here instead of passing unnoticed.
	 */
	private static final List<String> CANNOT_START_WITHOUT = List.of("embedder-e5-base-v2-onnx",
			"embedder-e5-base-v2-vocab");

	/**
	 * The fetches in these two files that are NOT models, each named by a fragment of its own line.
	 *
	 * <p><b>An allow-list, because the deny-list it replaces was defeated twice.</b> Listing where a
	 * model LANDS cannot work: cycle 1 widened it once for a {@code curl} written with
	 * {@code $ONNX_FILE}, and a review agent then defeated the widened form in two lines by
	 * assigning the path to a fresh variable first. Every new variable name is a new bypass, so the
	 * question has to be asked the other way round — this way a fetch nobody has declared is the
	 * violation, and adding a legitimate one costs an entry here instead of passing silently.
	 */
	private static final List<String> DECLARED_NON_MODEL_FETCHES = List.of(
			// The demo dataset dump. Out of scope for #444/#449 — ADR Decision 103 says why.
			"$DEMO_DUMP_URL",
			// The standalone build polling querystore's own REST endpoints while it bakes the index.
			"$QS/indexingstatus", "$QS/drift", "/tmp/reindex.json");

	private static Path repo(String relative) {
		return ModuleSourceRoot.repoRoot().resolve(relative);
	}

	private static String read(String relative) throws IOException {
		Path file = repo(relative);
		assertTrue(Files.isRegularFile(file), "this guard reads " + file + ", which does not exist");
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertFalse(text.isEmpty(), file + " is empty, so every check over it would pass vacuously");
		return text;
	}

	// ---- the manifest is the one committed record ----------------------------------------------

	@Test
	public void everyManifestRowCarriesADigestAndAnImmutableRevision() throws IOException {
		List<String[]> rows = ModelManifest.rows();

		List<String> violations = new ArrayList<String>();
		Set<String> ids = new LinkedHashSet<String>();
		for (String[] row : rows) {
			String id = row[0];
			if (!ids.add(id)) {
				violations.add(id + ": duplicated row");
			}
			if (row.length != 4) {
				violations.add(id + ": expected id, sha256, bytes and url, found " + row.length + " fields");
				continue;
			}
			if (!SHA256.matcher(row[1]).matches()) {
				violations.add(id + ": '" + row[1] + "' is not a sha256");
			}
			if (!row[2].matches("[1-9][0-9]*")) {
				violations.add(id + ": '" + row[2] + "' is not a positive byte count");
			}
			Matcher url = HF_RESOLVE.matcher(row[3]);
			if (!url.matches()) {
				violations.add(id + ": '" + row[3] + "' is not a huggingface.co resolve URL");
			} else if (!PINNED_REVISION.matcher(url.group(2)).matches()) {
				violations.add(id + ": revision '" + url.group(2) + "' is not an immutable commit hash");
			}
		}
		assertEquals(List.of(), violations, "model-manifest.tsv rows that do not pin an artifact");
	}

	@Test
	public void everyManifestRowIsFetchedBySomeCallSiteAndEveryCallSiteNamesOnlyRowsThatExist() throws IOException {
		Set<String> declared = new LinkedHashSet<String>();
		for (String[] row : ModelManifest.rows()) {
			declared.add(row[0]);
		}
		Set<String> fetched = new LinkedHashSet<String>(ENTRYPOINT_ARTIFACTS);
		fetched.addAll(BUNDLE_ARTIFACTS);

		List<String> unused = new ArrayList<String>(declared);
		unused.removeAll(fetched);
		List<String> missing = new ArrayList<String>(fetched);
		missing.removeAll(declared);

		assertEquals(List.of(), unused, "manifest rows no call site fetches — a stale pin nothing verifies");
		assertEquals(List.of(), missing, "artifacts a call site fetches with no committed digest");
	}

	// ---- neither call site spells a URL of its own ---------------------------------------------

	@Test
	public void neitherCallSiteSpellsAHuggingFaceUrlOfItsOwn() throws IOException {
		List<String> violations = new ArrayList<String>();
		for (String file : List.of("backend-init.sh", ".github/workflows/build-standalone.yml")) {
			String text = read(file);
			int at = text.indexOf("https://huggingface.co");
			if (at >= 0) {
				violations.add(file + ": line " + lineOf(text, at)
						+ " spells a Hugging Face URL; it belongs in model-manifest.tsv");
			}
		}
		assertEquals(List.of(), violations, "a URL spelled at a call site is one the manifest cannot pin");
	}

	/**
	 * README's download instructions are copied by hand by operators, so an unpinned revision there
	 * hands out the same unverified bytes the two findings are about — the judgement call the
	 * maintainer's scope note left to the plan, taken.
	 *
	 * <p>Pinned is not enough on its own: README also has to name the SAME revision the manifest
	 * does, or it sends an operator to bytes whose digest the manifest no longer records, one
	 * sentence after telling them to check what they downloaded against it. A review agent moved the
	 * manifest's onnx revision and this test stayed green on the 40-hex check alone.
	 */
	@Test
	public void everyHuggingFaceDownloadUrlAnyoneFollowsNamesAnImmutableRevisionTheManifestAlsoNames()
			throws IOException {
		List<String> pinned = new ArrayList<String>();
		for (String[] row : ModelManifest.rows()) {
			pinned.add(row[3]);
		}

		List<String> violations = new ArrayList<String>();
		int found = 0;
		// config.xml is here because it tells an operator where to get the served model, and a
		// `resolve/` URL is one edit away from appearing in that description.
		for (String file : List.of("backend-init.sh", ".github/workflows/build-standalone.yml", "README.md",
				"model-manifest.tsv", "omod/src/main/resources/config.xml")) {
			String text = read(file);
			Matcher urls = HF_RESOLVE.matcher(text);
			while (urls.find()) {
				found++;
				// A markdown link or a sentence ends the URL with punctuation the greedy path
				// capture swallows; stripping it stops the guard blaming the manifest for prose.
				// Strip what prose and markdown put on the end, and the download query string the
				// Hub's own copy button adds; none of them change which bytes the URL names.
				String url = urls.group().replaceAll("\\?download=true$", "").replaceAll("[)\\].,*_]+$", "");
				if (!PINNED_REVISION.matcher(urls.group(2)).matches()) {
					violations.add(file + ": line " + lineOf(text, urls.start()) + " fetches " + urls.group(1)
							+ " at '" + urls.group(2) + "', a revision that can change under it");
				} else if (!pinned.contains(url)) {
					violations.add(file + ": line " + lineOf(text, urls.start())
							+ " names a pinned url no manifest row carries, so nothing records its digest: " + url);
				}
			}
		}
		assertEquals(List.of(), violations, "downloads nobody can check against a committed digest");
		assertTrue(found > 0, "no download URL was scanned at all, so this guard proved nothing");
	}

	// ---- each site asks the library, and asks it in the right place ----------------------------

	@Test
	public void theEntrypointFetchesEveryArtifactItProvisionsThroughTheSharedLibrary() throws IOException {
		assertEveryArtifactIsFetchedThroughTheLibrary("backend-init.sh", ENTRYPOINT_ARTIFACTS);
	}

	@Test
	public void theStandaloneBuildFetchesEveryBundledArtifactThroughTheSharedLibrary() throws IOException {
		assertEveryArtifactIsFetchedThroughTheLibrary(".github/workflows/build-standalone.yml", BUNDLE_ARTIFACTS);
	}

	private void assertEveryArtifactIsFetchedThroughTheLibrary(String file, List<String> artifacts) throws IOException {
		List<String> code = codeLines(file);
		List<String> violations = new ArrayList<String>();
		for (String artifact : artifacts) {
			if (code.stream().noneMatch(line -> line.contains(artifact))) {
				violations.add(file + " never names the artifact " + artifact);
			}
		}
		if (code.stream().noneMatch(line -> line.contains("fetch_and_verify"))) {
			violations.add(file + " never calls fetch_and_verify, so nothing checks a digest");
		}
		assertEquals(List.of(), violations, "artifacts fetched without the committed digest being checked");
	}

	/**
	 * Naming the library is not the same as going through it. A {@code curl} written BESIDE a
	 * retained {@code fetch_and_verify} call reopens the finding while every other channel here
	 * stays green — the shape {@code ArchitectureGuardTest}'s own javadoc records as out of reach of
	 * a call-is-present check.
	 *
	 * <p>So every fetch in these files is either the library's own or declared in
	 * {@link #DECLARED_NON_MODEL_FETCHES}. A message that merely quotes {@code curl} is not a fetch
	 * and is skipped, which is what the {@code echo} arms in {@code backend-init.sh} rely on.
	 */
	@Test
	public void everyFetchOutsideTheLibraryIsOneNobodyCouldMistakeForAModel() throws IOException {
		List<String> violations = new ArrayList<String>();
		int scanned = 0;
		for (String file : List.of("backend-init.sh", ".github/workflows/build-standalone.yml")) {
			for (String line : codeLines(file)) {
				String trimmed = line.trim();
				if (!trimmed.contains("curl ") && !trimmed.contains("wget ")) {
					continue;
				}
				if (trimmed.startsWith("echo ") || trimmed.startsWith("printf ")) {
					continue;
				}
				scanned++;
				if (DECLARED_NON_MODEL_FETCHES.stream().noneMatch(trimmed::contains)) {
					violations.add(file + ": undeclared fetch — if it is not a model, add it to "
							+ "DECLARED_NON_MODEL_FETCHES; if it is, it belongs in the library: " + trimmed);
				}
			}
		}
		assertEquals(List.of(), violations, "a fetch that could be pulling a model around the digest check");
		assertTrue(scanned > 0, "no fetch line was scanned at all, so this guard proved nothing");
	}

	/**
	 * #444's refusal is stated as leaving the embedding global properties unconfigured, so the
	 * verification has to be upstream of the call that writes them. A check that ran afterwards would
	 * satisfy every other channel here and still point querystore at rejected bytes.
	 */
	@Test
	public void theEmbedderIsVerifiedBeforeAnythingWritesItsPathIntoAGlobalProperty() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);

		int wiring = -1;
		int lastEmbedderFetch = -1;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (wiring < 0 && line.startsWith("configure_retrieval_gps") && !line.contains("()")) {
				// The FIRST invocation. Taking the last let a second, earlier one be added with the
				// fetches still ordered after it.
				wiring = i;
			}
			// Either fetch form; which one each site must use is the exiting-form guard's question.
			if (line.trim().matches("^fetch_(and_verify|or_exit) .*") && line.contains("embedder-e5-base-v2")) {
				lastEmbedderFetch = Math.max(lastEmbedderFetch, i);
			}
		}

		assertTrue(wiring >= 0, "backend-init.sh no longer invokes configure_retrieval_gps; this guard read nothing");
		assertTrue(lastEmbedderFetch >= 0, "backend-init.sh no longer fetches the embedder; this guard read nothing");
		assertTrue(lastEmbedderFetch < wiring, "the embedder is verified at line " + (lastEmbedderFetch + 1)
				+ ", after the global properties are written at line " + (wiring + 1));
	}

	/**
	 * The rename is what makes bytes reachable under the name {@code chartsearchai.llm.modelFilePath}
	 * defaults to. Verifying after it is the defect, and it is one line's difference from verifying
	 * before it.
	 */
	@Test
	public void theDigestIsCheckedBeforeTheDownloadIsRenamedIntoPlace() throws IOException {
		// Two earlier forms of this check were defeated by the mutation it exists to catch, and both
		// failures were the same shape — asking WHETHER a verification appears before the rename
		// rather than whether the renamed FILE is the one verified. The first scanned the whole
		// library and matched the digest helper's own definition near the top. The second scoped
		// itself to this function and matched the branch that verifies a file already on the volume,
		// which legitimately runs before the download. So this one reads the operand: whatever `mv`
		// renames from must have been verified, by name, on an earlier line.
		List<String> body = functionBody(ModelManifest.LIBRARY, "fetch_and_verify_url");

		int rename = -1;
		String renamed = null;
		for (int i = 0; i < body.size() && rename < 0; i++) {
			String line = body.get(i).trim();
			if (line.startsWith("#") || !line.startsWith("mv ")) {
				continue;
			}
			rename = i;
			String[] words = line.split("\\s+");
			// `mv -- src dst` is the same rename; the operand is what follows the end-of-options.
			renamed = ("--".equals(words[1]) ? words[2] : words[1]).replace("\"", "");
		}
		assertTrue(rename >= 0, "fetch_and_verify_url never renames a download; this guard read nothing");

		int verify = -1;
		for (int i = 0; i < rename; i++) {
			String line = body.get(i);
			if (!line.trim().startsWith("#") && line.contains("_mm_verify_file") && line.contains(renamed)) {
				verify = i;
			}
		}
		assertTrue(verify >= 0, "fetch_and_verify_url renames " + renamed + " into place at line " + (rename + 1)
				+ " of the function without verifying " + renamed + " first");
	}

	/**
	 * The lines of one shell function, between its {@code name() {} opener and the closing brace in
	 * the first column. Nested functions would defeat this; the library has none, and the assertion
	 * that a body was found at all is what says so.
	 */
	private static List<String> functionBody(String relative, String function) throws IOException {
		List<String> lines = Files.readAllLines(repo(relative), StandardCharsets.UTF_8);
		List<String> body = new ArrayList<String>();
		boolean inside = false;
		for (String line : lines) {
			if (line.startsWith(function + "() {")) {
				inside = true;
				continue;
			}
			if (inside && line.equals("}")) {
				break;
			}
			if (inside) {
				body.add(line);
			}
		}
		assertFalse(body.isEmpty(), relative + " has no function " + function + "; this guard read nothing");
		return body;
	}

	/**
	 * Each artifact the module cannot start without is fetched through {@code fetch_or_exit}, the
	 * form that leaves rather than returning — and the call is not backgrounded, because an
	 * {@code exit} in a background subshell stops nothing.
	 *
	 * <p><b>This narrows a property that was defeated five times; it does not close it.</b> While
	 * the entrypoint branched on the library's status itself, four readings of the source were
	 * defeated in turn (ADR Decision 103 lists them) and each repair made the next reachable.
	 * Moving the branch into the library made the refusal a BEHAVIOUR that
	 * {@code ModelDownloadIntegrityTest.aRefusalOfAnArtifactTheModuleCannotStartWithoutStopsTheScript}
	 * drives — and two reviewers then found the fifth: one {@code &} on the last continuation line
	 * backgrounds the whole command, so the exit runs in a subshell and the start continues to the
	 * global-property write, with both source guards and shellcheck green.
	 *
	 * <p><b>The residue, named rather than claimed away.</b> This checks the call: the right form,
	 * and not backgrounded. A {@code fetch_or_exit} wrapped in a shell FUNCTION that is itself
	 * backgrounded evades any line-level rule, and closing that would mean the library detecting
	 * its own subshell. What is bounded is the accidental edit — which is the shape all five
	 * defeats had.
	 */
	@Test
	public void everyArtifactTheModuleCannotStartWithoutIsFetchedThroughTheExitingForm() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);
		List<String> violations = new ArrayList<String>();
		int fetches = 0;

		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.startsWith("#") || CANNOT_START_WITHOUT.stream().noneMatch(trimmed::contains)) {
				continue;
			}
			// A line that only TALKS about the artifact is not a fetch. The sibling guard skips
			// these for the same reason; without it an `echo` naming an id failed this test with a
			// message that was false about the line it named.
			if (trimmed.startsWith("echo ") || trimmed.startsWith("printf ")) {
				continue;
			}
			fetches++;
			if (!trimmed.startsWith("fetch_or_exit ")) {
				violations.add("backend-init.sh fetches an artifact the module cannot start without through a"
						+ " form that returns instead of exiting, so a refusal would leave the start running on"
						+ " to the global-property write: " + trimmed);
				continue;
			}
			String last = lastLineOfCommand(lines, i);
			if (last.endsWith("&") && !last.endsWith("&&")) {
				violations.add("backend-init.sh backgrounds a fetch_or_exit call, so its exit runs in a"
						+ " subshell and stops nothing: " + last);
			}
		}

		assertEquals(List.of(), violations, "a refusal that would not stop the start");
		assertTrue(fetches > 0, "backend-init.sh fetches no must-have artifact; this guard read nothing");
	}

	/** The last physical line of the logical command starting at {@code from}, following {@code \\}. */
	private static String lastLineOfCommand(List<String> lines, int from) {
		String line = lines.get(from).trim();
		int i = from;
		while (line.endsWith("\\") && i + 1 < lines.size()) {
			line = lines.get(++i).trim();
		}
		return line;
	}

	/**
	 * Three files spell these two paths and nothing tied them together: {@code Dockerfile.backend}
	 * chooses where they land, {@code backend-init.sh} sources one by absolute literal, and the
	 * library defaults the other by absolute literal. A substring check on the FILENAME survives any
	 * destination — a review agent moved both COPY targets to {@code /opt/wrong/} and the guard
	 * stayed green.
	 *
	 * <p>The consequence is not subtle. Sourcing a file that is not there exits a POSIX shell, so
	 * PID 1 dies before {@code exec}ing the server, and the backend service carries no {@code
	 * restart:} key — the container stops and stays stopped. That is the failure {@code build.yml}'s
	 * {@code entrypoint-lint} comment is written against, and neither {@code sh -n} nor shellcheck
	 * can see it: both are happy with a {@code .} of an absolute path that does not exist.
	 */
	/**
	 * Every digest the standalone build reads is paired with its url by the library's rule. The rule
	 * itself is driven by
	 * {@code ModelDownloadIntegrityTest.aDigestInputWithNoUrlOfItsOwnStopsTheBuildAndNamesBothInputs};
	 * what source has to say is that the workflow still ASKS it, for each digest it accepts. Deleting
	 * one of the three calls left that behavioural case green, which is the two-channel gap this
	 * class exists for.
	 */
	@Test
	public void everyDigestTheStandaloneBuildAcceptsIsPairedWithItsUrlByTheLibrarysRule() throws IOException {
		List<String> code = codeLines(".github/workflows/build-standalone.yml");
		List<String> digests = new ArrayList<String>();
		for (String line : code) {
			Matcher env = Pattern.compile("^([A-Z_]*SHA256):").matcher(line.trim());
			if (env.find()) {
				digests.add(env.group(1));
			}
		}
		assertFalse(digests.isEmpty(), "the workflow declares no digest input; this guard read nothing");

		List<String> violations = new ArrayList<String>();
		for (String digest : digests) {
			if (code.stream().noneMatch(l -> l.contains("require_url_for_digest") && l.contains("$" + digest))) {
				violations.add(digest + " is read by the step but never passed to require_url_for_digest, so a"
						+ " digest given without its url would be accepted and then ignored");
			}
		}
		assertEquals(List.of(), violations, "a digest input nothing pairs with a url");
	}

	@Test
	public void theImageCarriesBothTheLibraryAndTheManifestAtThePathsThatReadThem() throws IOException {
		String sourced = soleMatch("backend-init.sh", "^\\.\\s+(\\S*model-manifest\\.sh)\\s*$",
				"the path backend-init.sh sources the library from");
		String manifest = soleMatch(ModelManifest.LIBRARY,
				"^MODEL_MANIFEST_FILE=\"\\$\\{MODEL_MANIFEST_FILE:-(\\S+)\\}\"\\s*$",
				"the library's default manifest path");

		List<String> copied = new ArrayList<String>();
		for (String line : codeLines("Dockerfile.backend")) {
			if (line.startsWith("COPY ")) {
				String[] words = line.trim().split("\\s+");
				String destination = words[words.length - 1];
				// A destination ending in `/` is a directory, and the file lands under its own name.
				copied.add(destination.endsWith("/") && words.length >= 2
						? destination + words[words.length - 2].replaceAll(".*/", "")
						: destination);
			}
		}

		assertTrue(copied.contains(sourced), "backend-init.sh sources " + sourced
				+ ", which Dockerfile.backend never COPYs there; the container would exit at startup. COPY destinations: "
				+ copied);
		assertTrue(copied.contains(manifest), "the library reads its digests from " + manifest
				+ ", which Dockerfile.backend never COPYs there; every fetch would fail to resolve. COPY destinations: "
				+ copied);
	}

	/** The one capture of {@code pattern} in {@code relative}, or a failure saying what was sought. */
	private static String soleMatch(String relative, String pattern, String what) throws IOException {
		List<String> found = new ArrayList<String>();
		Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(read(relative));
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		assertEquals(1, found.size(), "expected exactly one line in " + relative + " giving " + what + ", found "
				+ found);
		return found.get(0);
	}

	/** Every line of the file that is not blank and not wholly a comment, in order. */
	private static List<String> codeLines(String relative) throws IOException {
		List<String> lines = new ArrayList<String>();
		for (String line : read(relative).split("\n", -1)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				lines.add(line);
			}
		}
		assertFalse(lines.isEmpty(), relative + " has no code lines, so every check over it would pass vacuously");
		return lines;
	}

	private static int lineOf(String text, int offset) {
		int line = 1;
		for (int i = 0; i < offset; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
