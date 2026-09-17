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
 * The structural half of issues #444 and #449: every fetch of a file the module later executes names
 * an immutable revision and an id in {@code model-manifest.tsv}, and never spells a Hugging Face URL
 * of its own.
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
 * <p><b>Every scan asserts it found something.</b> {@link ModuleSourceRoot}'s javadoc records that a
 * walking caller resolving the wrong tree reports no violations and passes; a guard whose whole
 * value is reading the right file owes itself that check.
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
			"embedder-e5-base-v2-vocab-intfloat");

	/** Where a model lands. A fetch naming one of these has bypassed the digest check. */
	private static final List<String> MODEL_SINKS = List.of(".gguf", ".onnx", "vocab.txt", "$LLM_DIR", "$QS_DIR");

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
		List<String[]> rows = manifestRows();

		assertFalse(rows.isEmpty(), "model-manifest.tsv carries no artifact rows");
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
		for (String[] row : manifestRows()) {
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
	 */
	@Test
	public void everyHuggingFaceDownloadUrlAnyoneFollowsNamesAnImmutableRevision() throws IOException {
		List<String> violations = new ArrayList<String>();
		int found = 0;
		for (String file : List.of("backend-init.sh", ".github/workflows/build-standalone.yml", "README.md",
				"model-manifest.tsv")) {
			String text = read(file);
			Matcher urls = HF_RESOLVE.matcher(text);
			while (urls.find()) {
				found++;
				if (!PINNED_REVISION.matcher(urls.group(2)).matches()) {
					violations.add(file + ": line " + lineOf(text, urls.start()) + " fetches " + urls.group(1) + " at '"
							+ urls.group(2) + "', a revision that can change under it");
				}
			}
		}
		assertEquals(List.of(), violations, "downloads bound to a mutable revision");
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
	 * retained {@code fetch_and_verify} call reopens the finding while every other channel here stays
	 * green — the shape {@code ArchitectureGuardTest}'s own javadoc records as out of reach of a
	 * call-is-present check. So no fetch in either file may write into a model's directory or under a
	 * model's extension by itself.
	 */
	@Test
	public void noFetchBesideTheLibraryWritesAModelFileOfItsOwn() throws IOException {
		List<String> violations = new ArrayList<String>();
		int scanned = 0;
		for (String file : List.of("backend-init.sh", ".github/workflows/build-standalone.yml")) {
			for (String line : codeLines(file)) {
				if (!line.contains("curl ") && !line.contains("wget ")) {
					continue;
				}
				scanned++;
				for (String sink : MODEL_SINKS) {
					if (line.contains(sink)) {
						violations.add(file + ": a fetch writes '" + sink + "' itself: " + line.trim());
					}
				}
			}
		}
		assertEquals(List.of(), violations, "a model fetched around the digest check");
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
			if (line.startsWith("configure_retrieval_gps") && !line.contains("()")) {
				wiring = i;
			}
			if (line.trim().startsWith("fetch_and_verify ") && line.contains("embedder-e5-base-v2")) {
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
		List<String> body = functionBody("scripts/model-manifest.sh", "fetch_and_verify_url");

		int rename = -1;
		String renamed = null;
		for (int i = 0; i < body.size() && rename < 0; i++) {
			String line = body.get(i).trim();
			if (line.startsWith("#") || !line.startsWith("mv ")) {
				continue;
			}
			rename = i;
			renamed = line.split("\\s+")[1].replace("\"", "");
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

	@Test
	public void theImageCarriesBothTheLibraryAndTheManifestTheEntrypointReads() throws IOException {
		String dockerfile = read("Dockerfile.backend");

		assertTrue(dockerfile.contains("model-manifest.sh"),
				"Dockerfile.backend does not COPY scripts/model-manifest.sh, so the entrypoint cannot source it");
		assertTrue(dockerfile.contains("model-manifest.tsv"),
				"Dockerfile.backend does not COPY model-manifest.tsv, so the entrypoint has no digests to check");
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

	private static List<String[]> manifestRows() throws IOException {
		List<String[]> rows = new ArrayList<String[]>();
		for (String line : Files.readAllLines(repo("model-manifest.tsv"), StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			rows.add(trimmed.split("\\s+"));
		}
		return rows;
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
