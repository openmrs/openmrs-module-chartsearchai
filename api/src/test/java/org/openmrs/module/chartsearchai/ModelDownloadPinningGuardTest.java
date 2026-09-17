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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	 * What makes a line naming one of those artifacts a FETCH of it rather than a message about it.
	 * Anything that puts bytes on the volume belongs here — the library's own entry points and the
	 * transfer tools a call site could reach around them with.
	 */
	private static final List<String> FETCH_FORMS = List.of("fetch_or_exit", "fetch_and_verify", "curl ", "wget ");

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

	/**
	 * The global properties {@code backend-init.sh} writes that carry no model file's path, spelled
	 * as they are at their write sites.
	 *
	 * <p><b>An allow-list, for the reason {@link #DECLARED_NON_MODEL_FETCHES} is one.</b> Asking
	 * which writes LOOK like a model path is the question a variable alias walks past. Asked the
	 * other way round, an undeclared property write outside the ledger's gate is the violation, so a
	 * new wiring property costs an entry here instead of publishing unchecked bytes' path silently.
	 */
	private static final List<String> DECLARED_NON_MODEL_PROPERTIES = List.of(
			// The retrieval switch. It names no file, and maybe_seed_demo_data asserts it outright
			// because a freshly imported dump brings its own value for it.
			"chartsearchai.querystore.enabled",
			// The bootstrap sweep, which configure_retrieval_gps turns OFF when no embedder path
			// was published — the fail-closed half of the same gate.
			"querystore.bootstrap.autostart",
			// The demo seed's own bookkeeping and the CPU breadcrumb.
			"chartsearchai.demo.seedStatus", "chartsearchai.demo.seededDataset", "chartsearchai.demo.cpuInfo");

	/**
	 * A global property name written as a literal: dot-separated segments in single quotes, which
	 * {@code '$1'}, {@code 'true'} and {@code '$DEMO_SEED_TAG'} are not. A write whose property is
	 * none of these is a write this guard cannot name, and that is reported rather than skipped.
	 */
	private static final Pattern QUOTED_PROPERTY = Pattern
			.compile("'([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+)'");

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
	 * <b>A second channel, and no longer the guarantee.</b> #444's refusal is stated as leaving the
	 * embedding global properties unconfigured. What holds that is the library's ledger of what
	 * verified in THIS shell, which
	 * {@link #noModelPathReachesAGlobalPropertyExceptBehindTheLibrarysVerifiedLedger} reads and
	 * {@code ModelDownloadIntegrityTest} drives. This check reads source POSITION, a weaker question:
	 * a reviewer defeated the line comparison alone by wrapping the two fetches in a function called
	 * after the wiring, leaving every other channel green.
	 *
	 * <p>So the premise the comparison rests on is asserted rather than assumed — a must-have fetch
	 * is a top-level statement of the entrypoint, and only then does where it is written say when it
	 * runs. That is what the function wrap fails.
	 */
	@Test
	public void theEmbedderIsVerifiedBeforeAnythingWritesItsPathIntoAGlobalProperty() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);

		List<String> violations = new ArrayList<String>();
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
				String enclosing = enclosingFunction(lines, i);
				if (enclosing != null) {
					violations.add("backend-init.sh fetches the embedder inside " + enclosing + "() at line "
							+ (i + 1) + ", so where it is written is not when it runs and the ordering read below"
							+ " means nothing; a must-have fetch belongs at top level");
				}
			}
		}

		assertEquals(List.of(), violations, "a must-have fetch whose source position is not its run order");
		assertTrue(wiring >= 0, "backend-init.sh no longer invokes configure_retrieval_gps; this guard read nothing");
		assertTrue(lastEmbedderFetch >= 0, "backend-init.sh no longer fetches the embedder; this guard read nothing");
		assertTrue(lastEmbedderFetch < wiring, "the embedder is verified at line " + (lastEmbedderFetch + 1)
				+ ", after the global properties are written at line " + (wiring + 1));
	}

	/**
	 * <b>The guarantee the check above is only a second channel for.</b> A model file's path reaches a
	 * global property only behind {@code require_verified} naming that artifact, so what decides it is
	 * what the running shell DID rather than where a fetch is written. Rearranging the entrypoint then
	 * leaves the paths unwritten — the fail-closed direction, and where the property was blank the
	 * autostart safety in that same function then turns the sweep off — instead of pointing
	 * querystore at bytes this start never checked.
	 *
	 * <p><b>Two questions, and the second is asked the other way round.</b> The first ties a write to
	 * an ARTIFACT by the VARIABLE the fetch targets: {@code $ONNX_FILE} is what
	 * {@code fetch_or_exit embedder-e5-base-v2-onnx} writes and what the property's value is built
	 * from. That reading alone is one intermediate assignment wide — copy {@code $ONNX_FILE} into a
	 * fresh name on one line and publish a third {@code querystore.embedding.*} property from that
	 * name on the next, and the write itself mentions no fetch target at all, which is the same
	 * alias that defeated the fetch deny-list {@link #DECLARED_NON_MODEL_FETCHES} replaced. So the
	 * second question inverts
	 * it: EVERY global-property write in the entrypoint must sit behind the ledger's gate unless the
	 * property is declared in {@link #DECLARED_NON_MODEL_PROPERTIES} as carrying no model path, and
	 * a write whose property name is not a literal this guard can read is itself a violation. A new
	 * path property then costs an entry there, which is the point at which someone asks whether it
	 * is a model's.
	 *
	 * <p><b>The residue, named rather than claimed away.</b> Both questions read THIS file, and only
	 * for the two write forms it uses: a statement assembled from fragments so that no line spells
	 * {@code INSERT INTO global_property}, or a property set from outside this file entirely, is
	 * outside both. And the artifact tie is asked only for
	 * {@link #CANNOT_START_WITHOUT}, so a third must-have artifact's path sitting behind the ledger
	 * entry for a DIFFERENT one satisfies the inverted question. What {@code require_verified}
	 * answers at runtime is {@code ModelDownloadIntegrityTest}'s question, driven against the real
	 * library.
	 */
	@Test
	public void noModelPathReachesAGlobalPropertyExceptBehindTheLibrarysVerifiedLedger() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);

		List<String> violations = new ArrayList<String>();
		int writes = 0;
		Set<Integer> behindAnyLedger = new LinkedHashSet<Integer>();
		for (String artifact : CANNOT_START_WITHOUT) {
			String variable = fetchTargetVariable(lines, artifact);
			assertTrue(variable != null, "no fetch of " + artifact + " in backend-init.sh names a target variable,"
					+ " so this guard cannot tell which property carries its path");
			Set<Integer> behindTheLedger = linesBehindTheLedger(lines, artifact);
			behindAnyLedger.addAll(behindTheLedger);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (line.trim().startsWith("#") || !line.contains(variable)) {
					continue;
				}
				if (!line.contains("gp_set_if_blank") && !line.contains("global_property")) {
					continue;
				}
				writes++;
				if (!behindTheLedger.contains(i)) {
					violations.add("backend-init.sh line " + (i + 1) + " publishes $" + variable + ", the file "
							+ artifact + " is fetched into, without asking require_verified " + artifact
							+ " first: " + line.trim());
				}
			}
		}

		// The inverted question. Which property a write NAMES, rather than which variable it reads,
		// so an intermediate assignment changes nothing about the answer.
		int gated = 0;
		int declaredWrites = 0;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.trim().startsWith("#") || !writesAGlobalProperty(lines, i)) {
				continue;
			}
			List<String> named = new ArrayList<String>();
			Matcher property = QUOTED_PROPERTY.matcher(line);
			while (property.find()) {
				named.add(property.group(1));
			}
			if (named.isEmpty()) {
				violations.add("backend-init.sh line " + (i + 1) + " writes a global property this guard cannot"
						+ " name, so it cannot say whether it is a model's path; spell the property as a"
						+ " literal: " + line.trim());
				continue;
			}
			for (String name : named) {
				if (behindAnyLedger.contains(i)) {
					gated++;
				} else if (DECLARED_NON_MODEL_PROPERTIES.contains(name)) {
					declaredWrites++;
				} else {
					violations.add("backend-init.sh line " + (i + 1) + " writes " + name + " outside the"
							+ " require_verified gate and it is not declared as carrying no model path: "
							+ line.trim());
				}
			}
		}

		assertEquals(List.of(), violations, "a model path published without the library's verdict on its bytes");
		assertTrue(writes >= CANNOT_START_WITHOUT.size(), "backend-init.sh publishes no must-have artifact's path at"
				+ " all; this guard read nothing");
		assertTrue(gated >= CANNOT_START_WITHOUT.size(), "no global-property write in backend-init.sh sits behind"
				+ " the ledger's gate; the inverted question read nothing");
		assertTrue(declaredWrites > 0, "no declared non-model property is written either, so the allow-list this"
				+ " question rests on is never exercised");
	}

	/**
	 * Whether line {@code index} of {@code backend-init.sh} WRITES a global property. Both spellings
	 * the entrypoint uses count — the {@code gp_set_if_blank} helper and raw seed SQL — while a read
	 * ({@code gp_value}, the seed's {@code global_property} dump, the schema probe) does not. The
	 * helper's own statement is excluded by the function it sits in: it writes whatever it is handed,
	 * and its callers are the sites with a property name to read.
	 */
	private static boolean writesAGlobalProperty(List<String> lines, int index) {
		String line = lines.get(index);
		if (line.contains("INSERT INTO global_property") || line.contains("UPDATE global_property")) {
			return !"gp_set_if_blank".equals(enclosingFunction(lines, index));
		}
		return line.contains("gp_set_if_blank") && !line.contains("gp_set_if_blank()");
	}

	/**
	 * The name of the shell function {@code index} falls inside, or null when it is at top level. Only
	 * the multi-line definition form is read, closing at column 0: a one-liner {@code f() { …; }}
	 * encloses no other line, and a function whose brace is indented would read as never closing —
	 * which over-reports rather than passing, so it fails loudly.
	 */
	private static String enclosingFunction(List<String> lines, int index) {
		String open = null;
		Matcher definition = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*\\{\\s*$").matcher("");
		for (int i = 0; i < index; i++) {
			if (definition.reset(lines.get(i)).matches()) {
				open = definition.group(1);
			} else if (open != null && lines.get(i).equals("}")) {
				open = null;
			}
		}
		return open;
	}

	/** The variable a must-have artifact is fetched INTO, read off the fetch's own target argument. */
	private static String fetchTargetVariable(List<String> lines, String artifact) {
		Pattern fetch = Pattern.compile("^fetch_(?:and_verify|or_exit)\\s+" + Pattern.quote(artifact) + "\\s.*");
		Matcher name = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)").matcher("");
		for (int i = 0; i < lines.size(); i++) {
			String command = logicalCommand(lines, i).trim();
			String[] words = command.split("\\s+");
			if (command.startsWith("#") || words.length < 3 || !fetch.matcher(command).matches()) {
				continue;
			}
			if (name.reset(words[2]).find()) {
				return name.group(1);
			}
		}
		return null;
	}

	/**
	 * The line indexes inside the then-branch of an {@code if require_verified …} naming
	 * {@code artifact}. Nested {@code if}s are counted so the branch ends where it really ends; a
	 * {@code case} would defeat this and the function it reads has none.
	 */
	private static Set<Integer> linesBehindTheLedger(List<String> lines, String artifact) {
		Set<Integer> inside = new LinkedHashSet<Integer>();
		for (int i = 0; i < lines.size(); i++) {
			String opener = logicalCommand(lines, i).trim();
			if (!opener.startsWith("if require_verified ")
					|| !List.of(opener.split("[\\s;]+")).contains(artifact)) {
				continue;
			}
			int depth = 1;
			for (int j = i + 1; j < lines.size() && depth > 0; j++) {
				String trimmed = lines.get(j).trim();
				if (trimmed.startsWith("#")) {
					continue;
				}
				if (trimmed.equals("fi")) {
					depth--;
					continue;
				}
				if (depth == 1 && (trimmed.equals("else") || trimmed.startsWith("elif "))) {
					depth = 0;
					continue;
				}
				inside.add(j);
				if (trimmed.startsWith("if ")) {
					depth++;
				}
			}
		}
		return inside;
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
	 * form that leaves rather than returning — and the call is the command itself, neither
	 * backgrounded nor an element of a pipeline, because an {@code exit} in a subshell stops
	 * nothing.
	 *
	 * <p><b>This narrows a property successive reviews have defeated; it does not close it.</b>
	 * While the entrypoint branched on the library's status itself, four readings of the source
	 * were defeated in turn (ADR Decision 103 lists them) and each repair made the next reachable.
	 * Moving the branch into the library made the refusal a BEHAVIOUR that
	 * {@code ModelDownloadIntegrityTest.aRefusalOfAnArtifactTheModuleCannotStartWithoutStopsTheScript}
	 * drives — and reviewers then found two more spellings of the same subshell, each with both
	 * source guards and shellcheck green: one {@code &} on the last continuation line backgrounds
	 * the whole command, and a {@code | tee} appended to it runs it as a pipeline element, which
	 * POSIX also puts in a subshell. Both let the start continue to the global-property write.
	 *
	 * <p><b>What this reads, and the residue.</b> It reads the logical command a must-have
	 * artifact is named on, and asks three things of it: that the command IS a
	 * {@code fetch_or_exit} rather than one taken inside another (a command substitution is a
	 * subshell too), that it does not end in {@code &}, and that it contains no pipe. What a
	 * line-level rule cannot see is a subshell the line does not spell — a {@code fetch_or_exit}
	 * inside a shell FUNCTION that is itself backgrounded or piped, or inside a multi-line
	 * {@code ( … ) &} group — and closing that would mean the library detecting its own subshell,
	 * which it has no portable way to do.
	 *
	 * <p>What escapes is the EXIT, and the global-property write no longer follows it. The ledger
	 * {@link #noModelPathReachesAGlobalPropertyExceptBehindTheLibrarysVerifiedLedger} reads is an
	 * ordinary shell variable, so a verification taken in any of those subshells records nothing the
	 * shell that publishes the path can see — {@code ModelDownloadIntegrityTest
	 * .aVerificationTakenInASubshellPublishesNothingToTheShellThatWritesThePath} drives these shapes
	 * and asserts the path stays unpublishable.
	 */
	@Test
	public void everyArtifactTheModuleCannotStartWithoutIsFetchedThroughTheExitingForm() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);
		List<String> violations = new ArrayList<String>();
		int fetches = 0;

		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			// Read whole logical commands, and only from the line that OPENS one: an artifact named
			// on a continuation line belongs to the command above it, and asking the physical line
			// would judge the wrong text — or, where the opener carries the fetch and the
			// continuation the id, judge nothing at all.
			if (trimmed.startsWith("#") || (i > 0 && lines.get(i - 1).trim().endsWith("\\"))) {
				continue;
			}
			String command = logicalCommand(lines, i);
			if (CANNOT_START_WITHOUT.stream().noneMatch(command::contains)) {
				continue;
			}
			// A command that only TALKS about the artifact is not a fetch. Asked as "does it RUN a
			// fetch" rather than "does it start with echo": the prefix form skipped
			// `echo "$(fetch_or_exit ...)"` outright, and the entrypoint already writes
			// echo-with-command-substitution lines.
			if (FETCH_FORMS.stream().noneMatch(command::contains)) {
				continue;
			}
			fetches++;
			if (!command.startsWith("fetch_or_exit ")) {
				violations.add(command.contains("fetch_or_exit")
						? "backend-init.sh takes a fetch_or_exit inside another command, where a command"
								+ " substitution runs it in a subshell and its exit stops nothing: " + command
						: "backend-init.sh fetches an artifact the module cannot start without through a"
								+ " form that returns instead of exiting, so a refusal would leave the start running on"
								+ " to the global-property write: " + command);
				continue;
			}
			if (command.endsWith("&") && !command.endsWith("&&")) {
				violations.add("backend-init.sh backgrounds a fetch_or_exit call, so its exit runs in a"
						+ " subshell and stops nothing: " + command);
			}
			// `||` is an or-list separator and leaves the call in the current shell; a single `|`
			// makes it an element of a pipeline, and POSIX runs every element in a subshell.
			if (command.replace("||", "").indexOf('|') >= 0) {
				violations.add("backend-init.sh pipes a fetch_or_exit call, and every element of a pipeline"
						+ " runs in a subshell, so its exit stops nothing: " + command);
			}
		}

		assertEquals(List.of(), violations, "a refusal that would not stop the start");
		assertTrue(fetches > 0, "backend-init.sh fetches no must-have artifact; this guard read nothing");
	}

	/** The logical command starting at {@code from}, continuation lines joined, {@code \\} dropped. */
	private static String logicalCommand(List<String> lines, int from) {
		StringBuilder command = new StringBuilder(lines.get(from).trim());
		int i = from;
		while (command.length() > 0 && command.charAt(command.length() - 1) == '\\' && i + 1 < lines.size()) {
			command.setLength(command.length() - 1);
			command.append(' ').append(lines.get(++i).trim());
		}
		return command.toString().trim();
	}

	/**
	 * Every digest the standalone build reads is paired with its url by the library's rule, and
	 * paired with the RIGHT one. The rule itself is driven by
	 * {@code ModelDownloadIntegrityTest.aDigestInputWithNoUrlOfItsOwnStopsTheBuildAndNamesBothInputs};
	 * what source has to say is that the workflow still ASKS it, for each digest it accepts. Deleting
	 * one of the three calls left that behavioural case green, which is the two-channel gap this
	 * class exists for.
	 *
	 * <p><b>Asking only whether the digest appears on SOME call line is not enough.</b> A reviewer
	 * crossed two of the three pairings — giving one url another's digest — and the earlier form of
	 * this check stayed green, which left the rule unpinned on the very thing it exists for. So the
	 * pairing is read off the workflow's two other statements of it, neither composed from the
	 * other: the {@code env:} block, which says which workflow input each shell variable carries,
	 * and the {@code fetch_and_verify_override} call, which is where a url and a digest are
	 * actually used together. A {@code require_url_for_digest} line has to name the same two
	 * variables as the override it guards, and to spell each one's own input name — which is the
	 * reason the rule takes those names as arguments at all, {@code vocab_url} not being
	 * {@code vocab_model_url}.
	 */
	@Test
	public void everyDigestTheStandaloneBuildAcceptsIsPairedWithItsUrlByTheLibrarysRule() throws IOException {
		List<String> code = codeLines(".github/workflows/build-standalone.yml");
		String step = String.join("\n", code);

		// Every digest variable the step declares, however it is declared — a digest that does NOT
		// come from an input still has to reach the rule, and the pairing check below then reports
		// that nothing carries it.
		List<String> digests = new ArrayList<String>();
		Matcher declaration = Pattern.compile("^([A-Z0-9_]*SHA256):").matcher("");
		// Shell variable -> the workflow input it carries, as the env: block spells it.
		Map<String, String> inputs = new LinkedHashMap<String, String>();
		Matcher carried = Pattern.compile("^([A-Z0-9_]+):\\s*\\$\\{\\{\\s*inputs\\.([a-z0-9_]+)").matcher("");
		for (String line : code) {
			if (declaration.reset(line.trim()).find()) {
				digests.add(declaration.group(1));
			}
			if (carried.reset(line.trim()).find()) {
				inputs.put(carried.group(1), carried.group(2));
			}
		}
		assertFalse(digests.isEmpty(), "the workflow declares no digest input; this guard read nothing");

		List<String> violations = new ArrayList<String>();
		Set<String> guarded = new LinkedHashSet<String>();
		Matcher call = Pattern
				.compile("require_url_for_digest\\s+\"\\$([A-Z0-9_]+)\"\\s+\"\\$([A-Z0-9_]+)\"\\s+(\\S+)\\s+(\\S+)")
				.matcher(step);
		while (call.find()) {
			guarded.add(call.group(1) + " " + call.group(2));
			String[] read = { call.group(1), call.group(2) };
			String[] spelled = { call.group(3), call.group(4) };
			for (int a = 0; a < read.length; a++) {
				String declared = inputs.get(read[a]);
				if (declared == null) {
					violations.add("require_url_for_digest is passed $" + read[a]
							+ ", which the workflow's env: block carries from no input at all");
				} else if (!declared.equals(spelled[a])) {
					violations.add("require_url_for_digest is passed $" + read[a] + " and told to call it '"
							+ spelled[a] + "', but the env: block carries that variable from the input '" + declared
							+ "', so the refusal would name an input the operator did not give");
				}
			}
		}
		assertFalse(guarded.isEmpty(), "no require_url_for_digest call in the workflow reads as a url, a digest and"
				+ " the two input names; this guard read nothing");

		for (String digest : digests) {
			if (guarded.stream().noneMatch(pair -> pair.endsWith(" " + digest))) {
				violations.add(digest + " is read by the step but never passed to require_url_for_digest, so a"
						+ " digest given without its url would be accepted and then ignored");
			}
		}

		int overrides = 0;
		Matcher used = Pattern.compile("fetch_and_verify_override\\s+\"\\$([A-Z0-9_]+)\"\\s+\"\\$([A-Z0-9_]+)\"")
				.matcher(step);
		while (used.find()) {
			overrides++;
			String pair = used.group(1) + " " + used.group(2);
			if (!guarded.contains(pair)) {
				violations.add("the build fetches with $" + used.group(1) + " checked against $" + used.group(2)
						+ ", a pairing no require_url_for_digest line asks about, so that digest given without that"
						+ " url would be accepted and then ignored. Pairings that are guarded: " + guarded);
			}
		}
		assertTrue(overrides > 0, "the workflow fetches no dispatched override; this guard read nothing");

		assertEquals(List.of(), violations, "a digest input nothing pairs with its own url");
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
