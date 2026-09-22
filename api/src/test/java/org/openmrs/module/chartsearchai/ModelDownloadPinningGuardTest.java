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
 * project downloads" — ADR Decision 106 names what is left out and why.
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
 * <p><b>Naming and placing a fetch is not the same as REACHING it.</b> Every check here about the
 * entrypoint once answered only the first two, and a three-line test of the target's own presence in
 * front of a retained call satisfied both — the early return #444 removed, put back. {@link
 * #everyArtifactTheEntrypointProvisionsIsFetchedUnconditionally} is the third question, and it is
 * the entrypoint's LAYOUT half; the weights fetch's own behaviour is
 * {@link EntrypointVolumeVerificationTest}, which runs it against a target already on the volume.
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
	 * The artifacts CHART SEARCH cannot run without, so a refusal of one must leave its path
	 * unpublished. It no longer stops the start: ADR Decision 106's amendment measures what that
	 * cost the whole instance. The LLM weights are not among them for a different reason — they are
	 * fetched in background subshells, which cannot write the ledger these two are read out of, and
	 * the comment above {@code _download_llm_file} says the rest.
	 *
	 * <p>Declared rather than matched by substring, so adding a third such artifact costs an entry
	 * here instead of passing unnoticed.
	 */
	private static final List<String> CHART_SEARCH_NEEDS = List.of("embedder-e5-base-v2-onnx",
			"embedder-e5-base-v2-vocab");

	/**
	 * What makes a line naming one of those artifacts a FETCH of it rather than a message about it.
	 * Anything that puts bytes on the volume belongs here — the library's own entry points and the
	 * transfer tools a call site could reach around them with.
	 */
	private static final List<String> FETCH_FORMS = List.of("fetch_or_degrade", "fetch_and_verify", "curl ", "wget ");

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
			// The demo dataset dump. Out of scope for #444/#449 — ADR Decision 106 says why.
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
			// The bootstrap sweep, which configure_retrieval_gps turns OFF whenever the ledger
			// declined — the fail-closed half of the same gate, alongside the two path
			// withdrawals. Off the ledger's verdict rather than off reading the path back, so the
			// line an operator gets names what changed in THIS start.
			// EntrypointRetrievalWiringTest drives it.
			"querystore.bootstrap.autostart",
			// The refusal's own diagnosis, which is the artifact id and the library's code and
			// deliberately not a path — ADR Decision 106's amendment, whose second premise is that
			// nobody on the deployment can read a container log.
			"chartsearchai.models.embedderStatus",
			// The demo seed's own bookkeeping and the CPU breadcrumb.
			"chartsearchai.demo.seedStatus", "chartsearchai.demo.seededDataset", "chartsearchai.demo.cpuInfo");

	/**
	 * A global property name written as a literal: dot-separated segments in single quotes, which
	 * {@code '$1'}, {@code 'true'} and {@code '$DEMO_SEED_TAG'} are not. A write whose property is
	 * none of these is a write this guard cannot name, and that is reported rather than skipped.
	 */
	private static final Pattern QUOTED_PROPERTY = Pattern
			.compile("'([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+)'");

	/**
	 * The entrypoint's read of the library's ledger — the one command that names an artifact without
	 * fetching it, so {@link #everyArtifactTheEntrypointProvisionsIsFetchedUnconditionally} excludes
	 * it. {@code EntrypointRetrievalWiringTest} reads the same line for the artifacts it names.
	 *
	 * <p>The WHOLE command has to be the gate. Excluding anything merely STARTING with it would let
	 * {@code if require_verified …; then fetch_or_degrade …; fi} carry a conditional fetch out through
	 * the exemption, which is the shape of every bypass this class has already been shown.
	 */
	private static final Pattern LEDGER_GATE = Pattern
			.compile("^(?:if )?require_verified(?: [A-Za-z0-9_.-]+)+;?\\s*(?:then)?$");

	/**
	 * The shell words that open a block, in command position. {@code elif} closes and reopens one.
	 *
	 * <p>{@code \u007b} is one of them: a brace GROUP is the shape that backgrounds or pipes a run of
	 * statements without touching any of their own lines, so a walk that did not count it reported
	 * the statements inside {@code \u007b … \u007d &} at top level and still balanced to 0
	 * (measured 2026-09-21). It is unambiguous in command position in this file in a way {@code (}
	 * is not — see {@link #nestingDepths}'s residue.
	 */
	private static final Set<String> BLOCK_OPENERS = Set.of("if", "case", "while", "until", "for", "{");

	private static final Set<String> BLOCK_CLOSERS = Set.of("fi", "esac", "done", "}");

	/** A function definition whose body is the lines below it, closing at column 0. */
	private static final Pattern FUNCTION_OPENER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\(\\)\\s*\\{\\s*$");

	/** What separates one command from the next on a line, a {@code case} arm's label included. */
	private static final Pattern SEGMENT_BREAK = Pattern.compile(";|&&|\\|\\||\\||\\)");

	/** A here-document opener, captured as the word that terminates the body. */
	private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)");

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
			if (line.trim().matches("^fetch_(and_verify|or_degrade) .*") && line.contains("embedder-e5-base-v2")) {
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
	 * leaves no path standing — the fail-closed direction — instead of pointing querystore at bytes
	 * this start never checked, and the same decline both WITHDRAWS a path an earlier good start
	 * wrote and turns the bootstrap sweep off. A refused fetch and one taken in a subshell both
	 * reach the wiring with an empty ledger, and the gate declines for either. What that composes to
	 * in a database that remembers an earlier start is {@link EntrypointRetrievalWiringTest}'s
	 * question, not this one's; source cannot answer it.
	 *
	 * <p><b>Two questions, and the second is asked the other way round.</b> The first ties a write to
	 * an ARTIFACT by the VARIABLE the fetch targets: {@code $ONNX_FILE} is what
	 * {@code fetch_or_degrade embedder-e5-base-v2-onnx} writes and what the property's value is built
	 * from. That reading alone is one intermediate assignment wide — copy {@code $ONNX_FILE} into a
	 * fresh name on one line and publish a third {@code querystore.embedding.*} property from that
	 * name on the next, and the write itself mentions no fetch target at all, which is the same
	 * alias that defeated the fetch deny-list {@link #DECLARED_NON_MODEL_FETCHES} replaced. So the
	 * second question inverts
	 * it: EVERY global-property write in the entrypoint must sit behind the ledger's gate unless the
	 * property is declared in {@link #DECLARED_NON_MODEL_PROPERTIES} as carrying no model path or
	 * the write {@link #withdrawsAPath}, and a write whose property name is not a literal this guard
	 * can read is itself a violation. A new path property then costs an entry there, which is the
	 * point at which someone asks whether it is a model's.
	 *
	 * <p><b>The residue, named rather than claimed away.</b> Both questions read THIS file, and only
	 * for the two write forms it uses: a statement assembled from fragments so that no line spells
	 * {@code INSERT INTO global_property}, or a property set from outside this file entirely, is
	 * outside both. And the artifact tie is asked only for
	 * {@link #CHART_SEARCH_NEEDS}, so a third must-have artifact's path sitting behind the ledger
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
		for (String artifact : CHART_SEARCH_NEEDS) {
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
		int withdrawals = 0;
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
				} else if (withdrawsAPath(line)) {
					withdrawals++;
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
		assertTrue(writes >= CHART_SEARCH_NEEDS.size(), "backend-init.sh publishes no must-have artifact's path at"
				+ " all; this guard read nothing");
		assertTrue(gated >= CHART_SEARCH_NEEDS.size(), "no global-property write in backend-init.sh sits behind"
				+ " the ledger's gate; the inverted question read nothing");
		assertTrue(declaredWrites > 0, "no declared non-model property is written either, so the allow-list this"
				+ " question rests on is never exercised");
		assertTrue(withdrawals > 0, "no write in backend-init.sh takes a model path back, so the exemption the"
				+ " question above rests on is never exercised; the decline arm has to blank both embedder paths"
				+ " (#444, ADR Decision 106)");
	}

	/**
	 * Whether the write on this line sets the property to the EMPTY string, which publishes no path
	 * and takes back whatever one was there. That is how {@code configure_retrieval_gps}'s decline
	 * arm withdraws a row an earlier good start left standing — the half {@code gp_set_if_blank}
	 * deliberately will not do, and the one a refusal that deleted nothing (the library's codes 4
	 * and 5) leaves pointing at unverified bytes still on the volume.
	 *
	 * <p>Read off the VALUE in the statement rather than off a helper's name, so a write that grew
	 * a path to publish stops being a withdrawal here the moment it does. What this cannot see is a
	 * statement assembled from fragments, which is the same residue the question it serves names.
	 */
	private static boolean withdrawsAPath(String line) {
		return line.contains("SET property_value=''");
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
		Pattern fetch = Pattern.compile("^fetch_(?:and_verify|or_degrade)\\s+" + Pattern.quote(artifact) + "\\s.*");
		Matcher name = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)").matcher("");
		for (int i = 0; i < lines.size(); i++) {
			String command = EntrypointSource.logicalCommand(lines, i).trim();
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
			String opener = EntrypointSource.logicalCommand(lines, i).trim();
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
	 * Each artifact chart search needs is fetched through {@code fetch_or_degrade} in the start's
	 * OWN shell — the call is the command itself, neither backgrounded, nor taken in a command
	 * substitution, nor an element of a pipeline.
	 *
	 * <p><b>What the subshell costs is no longer the exit; it is the LEDGER.</b> While this form
	 * exited, a subshell swallowed the exit and let the start run on to the property write. It now
	 * returns — ADR Decision 106's amendment, and
	 * {@code ModelDownloadIntegrityTest.aRefusalOfTheEmbedderLetsTheStartContinueWithItsPathStillUnpublishable}
	 * drives what it does instead — so what a subshell swallows is the ledger entry. That direction
	 * is not fail-open: {@code require_verified} declines and the path stays unwritten
	 * ({@code ModelDownloadIntegrityTest
	 * .aVerificationTakenInASubshellPublishesNothingToTheShellThatWritesThePath}). It is fail-SHUT
	 * on a good download — the bytes verify, nothing records it, and a healthy deployment
	 * configures no retrieval at all, silently. That is the failure this guard now exists for.
	 *
	 * <p><b>It narrows a property successive reviews have defeated; it does not close it.</b> While
	 * the entrypoint branched on the library's status itself, four readings of the source were
	 * defeated in turn (ADR Decision 106 lists them) and each repair made the next reachable.
	 * Reviewers then found two more spellings of the same subshell, each with both source guards and
	 * shellcheck green: one {@code &} on the last continuation line backgrounds the whole command,
	 * and a {@code | tee} appended to it runs it as a pipeline element, which POSIX also puts in a
	 * subshell.
	 *
	 * <p><b>What this reads, and the residue.</b> It reads the logical command such an artifact is
	 * named on, and asks two things of it: that the command IS a {@code fetch_or_degrade} rather
	 * than one taken inside another (a command substitution is a subshell too), and that
	 * {@link #outsideTheStartsOwnShell} finds no operator in it that moves the call into a
	 * subshell. What a line-level rule cannot see is a subshell the line does not spell — a
	 * {@code fetch_or_degrade} inside a shell FUNCTION that is itself backgrounded or piped, or
	 * inside a multi-line {@code ( … ) &} group — and closing that would mean the library detecting
	 * its own subshell, which it has no portable way to do.
	 *
	 * <p>The second of those is not outside every channel any more. A multi-line {@code ( … ) &}
	 * around this entrypoint's own two fetches reddens
	 * {@link EntrypointRetrievalWiringTest#theEntrypointsOwnStatementsLeaveTheStartRunningWhenTheEmbedderIsRefused},
	 * which runs the file: {@code MODEL_MANIFEST_REFUSED} is written in the subshell, so
	 * {@code chartsearchai.models.embedderStatus} comes back saying no refusal was recorded
	 * (measured round 6). What that case cannot reach is the shape written where it does not run.
	 */
	@Test
	public void everyArtifactChartSearchNeedsIsFetchedInTheStartsOwnShell() throws IOException {
		List<String> lines = Files.readAllLines(repo("backend-init.sh"), StandardCharsets.UTF_8);
		List<String> violations = new ArrayList<String>();
		int fetches = 0;

		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			// Read whole logical commands, and only from the line that OPENS one: an artifact named
			// on a continuation line belongs to the command above it, and asking the physical line
			// would judge the wrong text — or, where the opener carries the fetch and the
			// continuation the id, judge nothing at all.
			if (trimmed.startsWith("#") || EntrypointSource.continuesTheLineAbove(lines, i)) {
				continue;
			}
			String command = EntrypointSource.logicalCommand(lines, i);
			if (CHART_SEARCH_NEEDS.stream().noneMatch(command::contains)) {
				continue;
			}
			// A command that only TALKS about the artifact is not a fetch. Asked as "does it RUN a
			// fetch" rather than "does it start with echo": the prefix form skipped
			// `echo "$(fetch_or_degrade ...)"` outright, and the entrypoint already writes
			// echo-with-command-substitution lines.
			if (FETCH_FORMS.stream().noneMatch(command::contains)) {
				continue;
			}
			fetches++;
			if (!command.startsWith("fetch_or_degrade ")) {
				violations.add(command.contains("fetch_or_degrade")
						? "backend-init.sh takes a fetch_or_degrade inside another command, where a command"
								+ " substitution runs it in a subshell and its verification reaches no ledger: "
								+ command
						: "backend-init.sh fetches an artifact chart search needs through a form that is not"
								+ " the one whose verification the property write reads: " + command);
				continue;
			}
			String subshell = outsideTheStartsOwnShell(command);
			if (subshell != null) {
				violations.add("backend-init.sh " + subshell + " a fetch_or_degrade call, so it records its"
						+ " verification in a subshell the property write cannot read: " + command);
			}
		}

		assertEquals(List.of(), violations, "a verification the start's own shell would never see");
		assertTrue(fetches > 0, "backend-init.sh fetches nothing chart search needs; this guard read nothing");
	}

	/**
	 * How {@code command} moves the call out of the start's own shell — the verb for the message
	 * above — or {@code null} where it does not.
	 *
	 * <p><b>Asked of the whole command's SYNTAX, not of its last character.</b>
	 * {@code endsWith("&")} was the earlier reading, and it is false of two shapes that background
	 * the command all the same: {@code … &  # why}, which the shell reads as {@code … &} and a
	 * comment, and {@code … & wait}, where the {@code &} terminates the fetch and {@code wait} is
	 * the next command. Both left this class green at the PR head (ADR Decision 106). So the
	 * question is asked of the whole command: does an unquoted {@code &} or {@code |} appear
	 * ANYWHERE in it that is not part of {@code &&}, {@code ||} or a redirection, over the text
	 * {@link EntrypointSource#shellSyntaxOf} leaves — a {@code &} or a {@code |} inside an
	 * argument being text rather than syntax.
	 *
	 * <p>{@code &&} and {@code ||} are list separators and leave the call in this shell, so they
	 * are not reported; what they lead to is {@code EntrypointRetrievalWiringTest
	 * .theEntrypointsOwnStatementsLeaveTheStartRunningWhenTheEmbedderIsRefused}'s question, which
	 * runs the entrypoint instead of reading it. {@code >&} and {@code <&} are redirections. The
	 * residue is the one this guard's own javadoc names: a subshell no operator on this line spells.
	 */
	private static String outsideTheStartsOwnShell(String command) {
		String operators = EntrypointSource.shellSyntaxOf(EntrypointSource.withoutComment(command))
				.replace("&&", "  ").replace(">&", "  ").replace("<&", "  ");
		if (operators.indexOf('&') >= 0) {
			return "backgrounds";
		}
		if (operators.replace("||", "  ").indexOf('|') >= 0) {
			return "pipes";
		}
		return null;
	}

	/**
	 * The shapes {@link #outsideTheStartsOwnShell} has to answer, spelled as commands rather than
	 * left to whatever {@code backend-init.sh} happens to contain: the shipped file spells none of
	 * them, so the rule would otherwise be pinned only by the absence of a mutation nobody runs.
	 *
	 * <p>Mutate the predicate — swap the whole-command scan back for {@code endsWith("&")}, drop
	 * the {@code &&} exemption, blank the quote walk — and read which rows fail.
	 */
	@Test
	public void theOwnShellRuleReadsTheOperatorsTheShellReads() {
		Map<String, String> shapes = new LinkedHashMap<String, String>();
		// Backgrounded, in the three spellings a line can carry.
		shapes.put("fetch_or_degrade a \"$T\" 'label' &", "backgrounds");
		shapes.put("fetch_or_degrade a \"$T\" 'label' &  # overlap it with the LLM pulls", "backgrounds");
		shapes.put("fetch_or_degrade a \"$T\" 'label' & wait", "backgrounds");
		// A pipeline element.
		shapes.put("fetch_or_degrade a \"$T\" 'label' | tee /tmp/log", "pipes");
		// In this shell: a plain call, a list, and arguments that merely CONTAIN an operator or a
		// comment character. The diagnostic lines the entrypoint passes are of the last kind.
		shapes.put("fetch_or_degrade a \"$T\" 'label'", null);
		shapes.put("fetch_or_degrade a \"$T\" 'label' || echo 'refused'", null);
		shapes.put("fetch_or_degrade a \"$T\" 'label' && echo 'ready'", null);
		shapes.put("fetch_or_degrade a \"$T\" 'e5-base-v2 (~440MB) & its vocab'", null);
		shapes.put("fetch_or_degrade a \"$T\" \"a misleading \\\"Not a\\\" error | not a pipe\"", null);
		shapes.put("fetch_or_degrade a \"${T#/openmrs/data/}\" 'label'", null);
		shapes.put("fetch_or_degrade a \"$T\" 'label' 2>&1", null);
		shapes.put("fetch_or_degrade a \"$T\" 'label'  # fetched here & verified here", null);

		Map<String, String> read = new LinkedHashMap<String, String>();
		for (String command : shapes.keySet()) {
			read.put(command, outsideTheStartsOwnShell(command));
		}
		assertEquals(shapes, read, "the own-shell rule does not read these commands the way the shell does");
	}

	/**
	 * Each artifact the entrypoint provisions is fetched by a statement REACHED on every start, not
	 * by one a test of the file's own presence can skip.
	 *
	 * <p><b>This is the guarantee #444 turns on, and nothing else here asks it.</b> The checks above
	 * ask whether a fetch is NAMED — routed through the library — and POSITIONED — in the current
	 * shell, ahead of the property write. All of them stay green, with {@code sh -n} and
	 * {@code shellcheck}, when {@code if [ -f "$target" ]; then return; fi} goes back in: the early
	 * return ADR Decision 106 says removing is the point of the decision, and the optimisation that
	 * decision's own cost table invites. {@code /openmrs/data} outlives the container, so a start that
	 * skips the hash for a file it finds by NAME never verifies the population the fix most needs to
	 * reach.
	 *
	 * <p><b>Asked positively.</b> Enumerating the spellings of a skip is the shape successive reviews
	 * of this change defeated; what is asserted instead is that every command naming one of these
	 * artifacts sits at the entrypoint's top level — nesting depth 0, inside no block and inside no
	 * function — so where it is written is when it runs. The ledger gate is the one command excluded,
	 * by its own name: it READS what has verified rather than fetching anything.
	 *
	 * <p><b>The residue.</b> Depth is not reachability — an {@code exit} or {@code return} placed
	 * above these statements would skip them at depth 0 — and a skip written INSIDE a function is
	 * invisible here, which is {@link EntrypointVolumeVerificationTest}'s question: it drives the
	 * weights fetch with the target already present rather than reading where the call sits. The
	 * walk's balance to 0 at end of file fails a construct it HALF reads and not one it does not
	 * read at all — {@link #nestingDepths} carries what that cost and which shape is still open.
	 * And the unit is a command that
	 * NAMES an artifact, so a log line mentioning one from inside a function would be reported here;
	 * that direction over-reports rather than passing, and an artifact named nowhere outside the
	 * ledger gate fails the same way.
	 */
	@Test
	public void everyArtifactTheEntrypointProvisionsIsFetchedUnconditionally() throws IOException {
		List<String> lines = Files.readAllLines(repo(EntrypointSource.ENTRYPOINT), StandardCharsets.UTF_8);
		int[] depths = nestingDepths(lines);

		List<String> violations = new ArrayList<String>();
		Map<String, Integer> statements = new LinkedHashMap<String, Integer>();
		for (String artifact : ENTRYPOINT_ARTIFACTS) {
			statements.put(artifact, 0);
		}
		for (int i = 0; i < lines.size(); i++) {
			// Whole logical commands, read only from the line that OPENS one — the reason
			// everyArtifactChartSearchNeedsIsFetchedInTheStartsOwnShell gives.
			if (lines.get(i).trim().startsWith("#") || EntrypointSource.continuesTheLineAbove(lines, i)) {
				continue;
			}
			String command = EntrypointSource.logicalCommand(lines, i);
			if (LEDGER_GATE.matcher(command).matches()) {
				continue;
			}
			for (String artifact : ENTRYPOINT_ARTIFACTS) {
				if (!command.contains(artifact)) {
					continue;
				}
				statements.put(artifact, statements.get(artifact) + 1);
				if (depths[i] != 0) {
					violations.add(EntrypointSource.ENTRYPOINT + " names " + artifact + " at line " + (i + 1)
							+ " inside a block or a function, so what it does depends on something other than the"
							+ " start reaching this line — a condition, a caller, or the subshell a brace group"
							+ " closed with & or a pipe runs it in: " + command);
				} else if (BLOCK_OPENERS.contains(firstWord(command))) {
					violations.add(EntrypointSource.ENTRYPOINT + " names " + artifact + " at line " + (i + 1)
							+ " on a command that is itself a condition, so the fetch runs only when that"
							+ " condition passes: " + command);
				}
			}
		}

		assertEquals(List.of(), violations, "a model fetch a start could skip");
		for (Map.Entry<String, Integer> named : statements.entrySet()) {
			assertTrue(named.getValue() > 0, EntrypointSource.ENTRYPOINT + " carries no statement naming "
					+ named.getKey() + " outside the ledger gate, so this guard read nothing about an artifact it"
					+ " is supposed to provision");
		}
	}

	/**
	 * The block-nesting depth at the start of each line of the entrypoint: 0 at top level, one deeper
	 * inside every {@code if}, {@code case} or loop body, and one deeper inside every function
	 * definition.
	 *
	 * <p>A keyword counts only in COMMAND position — the first word of a segment, segments being what
	 * {@code ;}, {@code &&}, {@code ||}, {@code |} and a {@code case} arm's {@code )} separate —
	 * because this script's own message text says {@code done:} and {@code until it completes}, which
	 * a plain token scan reads as shell. Here-document bodies are skipped for the same reason: they
	 * carry SQL and Java properties rather than commands.
	 *
	 * <p>The walk has to balance to 0 at end of file, which is what fails a construct it half-reads.
	 * It is not a calibration of the depths themselves, and the difference was measured on
	 * 2026-09-21: a construct neither side of which it recognises contributes 0 at both ends, so it
	 * balances while the statements inside it are reported at top level. A brace group did exactly
	 * that — {@code \u007b … \u007d &}, which is "background the embedder like the LLM" and touches
	 * neither fetch's own line — until {@code \u007b} and {@code \u007d} were added to the words
	 * counted above.
	 *
	 * <p><b>The residue is the shape of that miss, not the one instance.</b> A multi-line
	 * {@code ( … ) &} is the same hole and is left open deliberately: {@code (} in this file also
	 * opens a command substitution, an arithmetic expansion and a {@code case} arm's label, so
	 * counting it in command position is a parse this walk does not attempt, and a wrong depth is
	 * worse here than a missing one. It is left open HERE rather than left open: {@code
	 * require_verified} withholds the path at runtime either way, and around this file's own two
	 * fetches the shape reddens
	 * {@link EntrypointRetrievalWiringTest#theEntrypointsOwnStatementsLeaveTheStartRunningWhenTheEmbedderIsRefused}
	 * through {@code chartsearchai.models.embedderStatus} (measured round 6).
	 */
	private static int[] nestingDepths(List<String> lines) {
		int[] depths = new int[lines.size()];
		int depth = 0;
		String terminator = null;
		for (int i = 0; i < lines.size(); i++) {
			depths[i] = depth;
			String trimmed = lines.get(i).trim();
			if (terminator != null) {
				if (trimmed.equals(terminator)) {
					terminator = null;
				}
				continue;
			}
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			depth += nestingDelta(lines.get(i));
			assertTrue(depth >= 0, EntrypointSource.ENTRYPOINT + " line " + (i + 1) + " closes a block this guard"
					+ " never saw opened, so it cannot say which statements are conditional: " + trimmed);
			Matcher heredoc = HEREDOC.matcher(trimmed);
			if (heredoc.find()) {
				terminator = heredoc.group(1);
			}
		}
		assertEquals(0, depth, EntrypointSource.ENTRYPOINT + "'s blocks do not balance as this guard reads them, so"
				+ " the depths it reported are not the script's");
		return depths;
	}

	/** How much one line changes the nesting depth: a function definition, a block, or neither. */
	private static int nestingDelta(String line) {
		if (FUNCTION_OPENER.matcher(line).matches()) {
			return 1;
		}
		if (line.equals("}")) {
			return -1;
		}
		int delta = 0;
		for (String segment : SEGMENT_BREAK.split(line.trim())) {
			String head = firstWord(segment);
			if (BLOCK_OPENERS.contains(head)) {
				delta++;
			} else if (BLOCK_CLOSERS.contains(head)) {
				delta--;
			}
		}
		return delta;
	}

	private static String firstWord(String command) {
		return command.trim().split("\\s+", 2)[0];
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
	 * <p>The consequence is not subtle: sourcing a file that is not there ends a POSIX shell on the
	 * spot, so PID 1 dies before {@code exec}ing the server and the whole deployment goes with it —
	 * the chain ADR Decision 106's amendment measures. That is the failure
	 * {@code build.yml}'s {@code entrypoint-lint} comment is written against, and neither
	 * {@code sh -n} nor shellcheck can see it: both are happy with a {@code .} of an absolute path
	 * that does not exist.
	 *
	 * <p>It is not the entrypoint's only pre-Tomcat exit. The {@code exec runuser -u openmrs} that
	 * drops the start to uid 1001 is another, and it is on the published image's path:
	 * {@code Dockerfile.backend} declares no {@code USER}, so the entrypoint starts as root and
	 * takes that branch on every start, and an {@code exec} that cannot run its target ends a
	 * non-interactive shell before the next statement the same way (measured 2026-09-21: exit 126
	 * under {@code /bin/sh}, 127 under {@code dash}). {@code runuser} is in the
	 * {@code eclipse-temurin:21-jre} base today, so nothing there is broken — which is why this
	 * guard reads the two files below and says nothing about it.
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

	/**
	 * The mode a {@code COPY --chmod=} line names is stamped on the DIRECTORIES BuildKit creates for
	 * the destination as well as on the file, so a mode that is right for a file is wrong for the
	 * directory it invents: {@code 0644} on a directory clears the traverse bit, and nothing under it
	 * can be opened once the start has dropped to uid 1001.
	 *
	 * <p>Measured 2026-09-22 in the image the demo was serving
	 * ({@code openmrs/openmrs-reference-application-3-backend:sha-d11b5308}, amd64): the layer
	 * carrying the manifest holds {@code drw-r--r-- usr/local/share/chartsearchai/} beside
	 * {@code -rw-r--r-- .../model-manifest.tsv}, while {@code usr/local/share/} above it — a
	 * directory the base image ships — is {@code drwxr-xr-x}. {@code /usr/local/bin} is shipped too,
	 * which is why the library COPYed there was readable and only the manifest was not. The
	 * entrypoint's {@code exec runuser -u openmrs} runs before every fetch, so {@code _mm_field}'s
	 * {@code [ ! -f "$MODEL_MANIFEST_FILE" ]} answers "no manifest" and every fetch resolves nothing.
	 * The embedder's two codes are the part that is RECORDED — that start's
	 * {@code chartsearchai.models.embedderStatus} read
	 * {@code not verified in this start: embedder-e5-base-v2-onnx:4 embedder-e5-base-v2-vocab:4} —
	 * and the weights' is inferred from the same step, since they are fetched in background subshells
	 * that record nothing. What was MEASURED of them is the absence: the demo's own
	 * {@code ModelFileResolver} reported {@code Model file not found} for both GGUF names, and for
	 * the three older names this project has used.
	 *
	 * <p>So the rule is that a mode-setting COPY may not be what CREATES the directory it lands in.
	 * It is deliberately unconditional rather than exempting the directories the base image ships:
	 * which those are is the fact that changed silently here, {@code /usr/local/bin} having covered
	 * for this line shape for as long as nothing was added beside it. {@code mkdir -p} over a
	 * directory that already exists leaves its mode alone, so obeying it costs nothing.
	 *
	 * <p>What this cannot see is a mode named on the {@code mkdir} itself ({@code mkdir -m 700}), and
	 * it says nothing about the FILE's mode, which is what {@code --chmod} is there to set.
	 * → #466; ADR Decision 106. The weights' half of that outage — the same failure, recorded
	 * nowhere a deployment can read — is #467.
	 */
	@Test
	public void noCopyThatSetsAModeIsWhatCreatesTheDirectoryTheStartMustTraverse() throws IOException {
		List<String> created = new ArrayList<String>();
		List<String> violations = new ArrayList<String>();
		int modeSetting = 0;
		for (String line : joinedCodeLines("Dockerfile.backend")) {
			String[] words = line.trim().split("\\s+");
			if (words[0].equals("FROM")) {
				// A directory made in an earlier stage is not in THIS one's filesystem, and this file
				// has five stages. Without the reset, a `mkdir` anywhere above would answer for a COPY
				// in a stage that never ran it.
				created.clear();
			} else if (words[0].equals("RUN")) {
				created.addAll(directoriesMade(words));
			} else if (words[0].equals("COPY") && line.contains("--chmod=")) {
				modeSetting++;
				String destination = words[words.length - 1];
				String directory = destination.endsWith("/")
						? destination.substring(0, destination.length() - 1)
						: destination.replaceAll("/[^/]*$", "");
				if (!created.contains(directory)) {
					violations.add(directory + " (" + line.trim() + ")");
				}
			}
		}
		assertTrue(modeSetting > 0, "Dockerfile.backend names a mode on no COPY at all; this guard read nothing");
		assertEquals(List.of(), violations,
				"a COPY that names a mode stamps it on the directories it creates too, and a file's mode leaves"
						+ " those untraversable to the uid the entrypoint drops to, so every fetch resolves nothing;"
						+ " create these with an earlier RUN mkdir -p instead");
	}

	/** Every absolute directory the {@code mkdir} calls on one shell line make, in order. */
	private static List<String> directoriesMade(String[] words) {
		List<String> made = new ArrayList<String>();
		boolean inMkdir = false;
		for (String word : words) {
			if (word.equals("mkdir")) {
				inMkdir = true;
			} else if (word.startsWith("&") || word.startsWith(";") || word.startsWith("|")) {
				inMkdir = false;
			} else if (inMkdir && word.startsWith("/")) {
				made.add(word);
			}
		}
		return made;
	}

	/** {@link #codeLines} with backslash continuations joined, so one command reads as one line. */
	private static List<String> joinedCodeLines(String relative) throws IOException {
		List<String> joined = new ArrayList<String>();
		StringBuilder pending = new StringBuilder();
		for (String line : codeLines(relative)) {
			String trimmed = line.trim();
			if (trimmed.endsWith("\\")) {
				pending.append(trimmed, 0, trimmed.length() - 1).append(' ');
				continue;
			}
			joined.add(pending.append(trimmed).toString());
			pending.setLength(0);
		}
		if (pending.length() > 0) {
			joined.add(pending.toString());
		}
		return joined;
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
