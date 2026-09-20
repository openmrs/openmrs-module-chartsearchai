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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A weights file ALREADY on the volume still reaches the library's verification — the guarantee
 * #444 turns on, because {@code /openmrs/data} outlives the container and the deployments this fix
 * most needs to reach are the ones provisioned before it existed. ADR Decision 106 records that
 * removing {@code backend-init.sh}'s "the target exists, so return" early exit is the point of the
 * decision; this is what says it is still gone.
 *
 * <p><b>Why a behavioural channel, and not a source one.</b> Six rounds of source-position and
 * source-text guards on this change were each defeated by a plausible restructure, and this
 * property was defeated by the plainest of them: re-inserting {@code if [ -f "$target" ]; then
 * return; fi} at either fetch site left {@link ModelDownloadPinningGuardTest} — which asks whether a
 * fetch is NAMED and POSITIONED, never whether REACHING it is conditioned on the file's absence —
 * {@link ModelDownloadIntegrityTest}, {@code sh -n} and {@code shellcheck} all green. So this asks
 * what the entrypoint DOES: the library is the real one, the bytes on the volume are real bytes, and
 * the question is whether they get hashed.
 *
 * <p><b>It runs the entrypoint's own functions and its own call, not a retelling of them.</b> Both
 * functions below are taken verbatim out of {@code backend-init.sh} by name through {@link
 * EntrypointSource}, the fetches are the entrypoint's own {@code fetch_llm_in_background} calls
 * pasted as written, and the library is {@code scripts/model-manifest.sh} itself. The test supplies
 * only {@code LLM_DIR}, a fixture manifest, and the bytes on the volume.
 *
 * <p><b>The residue, named rather than claimed away.</b> This reaches the LLM weights, whose fetch
 * the entrypoint wraps in a function it can paste. The embedder's two fetches are top-level
 * statements with no function to extract, so that half is
 * {@code ModelDownloadPinningGuardTest.everyArtifactTheEntrypointProvisionsIsFetchedUnconditionally}
 * — a source channel, and one that reads nesting rather than behaviour. Nor does this run the
 * entrypoint end to end: it cannot see a skip written anywhere but inside the two functions it
 * pastes.
 */
public class EntrypointVolumeVerificationTest {

	/**
	 * The functions an LLM weights fetch is composed of, in the order they are pasted into the
	 * harness. Listing them is what makes the harness's dependency on the entrypoint explicit: a
	 * function that stops existing under this name stops the run with a message naming it.
	 */
	private static final List<String> WEIGHTS_FETCH_FUNCTIONS = List.of("_download_llm_file",
			"fetch_llm_in_background");

	/** The entrypoint's own per-model fetch. Its calls are what these cases run. */
	private static final String FETCH_CALL = "fetch_llm_in_background";

	/** A word of a shell call: a double- or single-quoted run, else a bare run. */
	private static final Pattern CALL_WORD = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

	private static final byte[] RECORDED_BYTES = "the weights the maintainers reviewed\n"
			.getBytes(StandardCharsets.UTF_8);

	/** One byte different and the same LENGTH, so the digest is what refuses it, not the size. */
	private static final byte[] UNRECORDED_BYTES = "the weights the maintainers reviewer\n"
			.getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path work;

	/** What the entrypoint calls LLM_DIR: the directory on the volume the weights live in. */
	private Path weightsDir;

	private Path manifest;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(Files.isExecutable(Paths.get("/bin/sh")), "POSIX /bin/sh is required to drive the entrypoint");
		assertEquals(RECORDED_BYTES.length, UNRECORDED_BYTES.length, "the two fixtures differ in LENGTH, so the"
				+ " size branch would refuse the unrecorded one and these cases would not be about the digest");
		weightsDir = Files.createDirectories(work.resolve("openmrs/data/chartsearchai"));
		manifest = work.resolve("manifest-fixture.tsv");
	}

	/**
	 * <b>The steady-state restart.</b> The recorded artifact is already at the target and no origin
	 * serves anything, so the only way the library can answer is by hashing what is on the volume —
	 * and it has to answer, because the "ready" line is the caller's report of a verification that
	 * returned 0. A start that skipped the fetch for a file it found by NAME cannot print that line,
	 * wherever in the function the skip is written.
	 */
	@Test
	public void weightsAlreadyOnTheVolumeAreVerifiedRatherThanTrustedForTheirName() throws Exception {
		for (Call call : fetchCalls()) {
			Path target = weightsDir.resolve(call.filename);
			Files.write(target, RECORDED_BYTES);
			recordInTheManifest(call.artifact, RECORDED_BYTES);

			Run run = run(call);

			assertTrue(run.output.contains(call.label + " ready: " + target), "the weights already on the volume"
					+ " never reached the library's verification, so a file is being trusted for its name — which"
					+ " is the whole of what ADR Decision 106 removed\n" + run);
			assertTrue(Files.exists(target), "the verification deleted bytes that match the recorded digest\n" + run);
			assertArrayEquals(RECORDED_BYTES, Files.readAllBytes(target),
					"the bytes on the volume were replaced although they were the recorded artifact\n" + run);
			// Nothing serves the fixture url, so a download would have failed loudly; this says the
			// verification came off the volume rather than off a transfer.
			assertFalse(run.output.contains("Downloading " + call.label), "the file on the volume was re-downloaded"
					+ " rather than hashed where it lay\n" + run);
		}
	}

	/**
	 * <b>The returning pre-#444 deployment.</b> Bytes no manifest row records are at the target, which
	 * is what a volume provisioned before the pin existed can be carrying. They must be refused and
	 * deleted rather than served on: for {@code llm-gemma-4-e4b} that file is exactly what
	 * {@code config.xml} defaults {@code chartsearchai.llm.modelFilePath} to.
	 *
	 * <p>The replacement is then unreachable — no origin serves the fixture url — so this also pins
	 * the one refusal that costs the volume a copy it had: code 6's message, which has to say the file
	 * is gone rather than that a restart can resume it.
	 */
	@Test
	public void weightsAlreadyOnTheVolumeThatAreNotTheRecordedArtifactAreRefusedAndDeleted() throws Exception {
		for (Call call : fetchCalls()) {
			Path target = weightsDir.resolve(call.filename);
			Files.write(target, UNRECORDED_BYTES);
			recordInTheManifest(call.artifact, RECORDED_BYTES);

			Run run = run(call);

			assertFalse(Files.exists(target), "bytes that are not the artifact model-manifest.tsv records were left"
					+ " standing under the name the module loads them by, so this start never hashed them\n" + run);
			assertTrue(run.output.contains(call.label + " is not the artifact model-manifest.tsv records"),
					"the refusal was not reported against the digest\n" + run);
			assertTrue(run.output.contains(call.label + " was refused and deleted, and the pinned revision could"
					+ " not then be reached to replace it"), "the operator is not told the volume no longer holds a"
							+ " copy of this file\n" + run);
		}
	}

	// ---- driving the entrypoint's own weights fetch ---------------------------------------------

	/**
	 * Sources the real library, pastes the entrypoint's own fetch functions in, and runs one of its
	 * own {@code fetch_llm_in_background} calls verbatim. The call backgrounds the work, so
	 * {@code wait} is what the container's own start does not do and this has to.
	 */
	private Run run(Call call) throws Exception {
		List<String> script = new ArrayList<String>();
		script.add(". '" + ModuleSourceRoot.repoRoot().resolve(ModelManifest.LIBRARY) + "'");
		script.add("LLM_DIR='" + weightsDir + "'");
		List<String> lines = EntrypointSource.lines();
		for (String function : WEIGHTS_FETCH_FUNCTIONS) {
			script.add(EntrypointSource.functionText(lines, function));
		}
		script.add(call.command);
		script.add("wait");

		Path driver = work.resolve("drive-" + System.nanoTime() + ".sh");
		Files.write(driver, (String.join("\n", script) + "\n").getBytes(StandardCharsets.UTF_8));

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", driver.toString());
		builder.environment().put("MODEL_MANIFEST_FILE", manifest.toString());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the weights fetch did not finish");
		Run run = new Run(process.exitValue(), output);

		// No "it said something" check here: each case asserts a line of the library's own output, so
		// a silent run fails on the property rather than on a weaker precondition that pre-empts it.
		assertEquals(0, run.exit, "the entrypoint's own fetch call failed as a statement, so what it did with the"
				+ " file on the volume is not what these cases then read\n" + run);
		return run;
	}

	/**
	 * A fixture row for one artifact: the digest and length of the bytes the case means to be the
	 * recorded ones, and a url nothing serves. Every case here places bytes at the target itself, so
	 * a reachable origin would only let a download stand in for the verification these cases are
	 * about — and a port would be a race where an absent file is not.
	 */
	private void recordInTheManifest(String artifact, byte[] recorded) throws Exception {
		String row = artifact + '\t' + ModelManifest.sha256(recorded) + '\t' + recorded.length + '\t'
				+ work.resolve("never-served").toUri() + '\n';
		Files.write(manifest, row.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The entrypoint's own {@code fetch_llm_in_background} calls, each with the arguments it passes.
	 * Read off the source rather than spelled here, so a renamed artifact or a renamed weights file
	 * moves these cases with it instead of leaving them driving a call the entrypoint no longer makes.
	 */
	private static List<Call> fetchCalls() throws IOException {
		List<String> lines = EntrypointSource.lines();
		List<Call> calls = new ArrayList<Call>();
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#") || EntrypointSource.continuesTheLineAbove(lines, i)) {
				continue;
			}
			String command = EntrypointSource.logicalCommand(lines, i);
			List<String> words = callWords(command);
			if (words.isEmpty() || !FETCH_CALL.equals(words.get(0))) {
				continue;
			}
			assertTrue(words.size() >= 4, EntrypointSource.ENTRYPOINT + " calls " + FETCH_CALL + " with fewer"
					+ " arguments than the artifact, the filename and the label, so this harness cannot tell which"
					+ " file it is about: " + command);
			calls.add(new Call(command, words.get(1), words.get(2), words.get(3)));
		}
		assertFalse(calls.isEmpty(), EntrypointSource.ENTRYPOINT + " makes no " + FETCH_CALL + " call, so these"
				+ " cases would drive nothing");
		return calls;
	}

	/** The words of a shell call, quoted runs kept whole and their quotes dropped. */
	private static List<String> callWords(String command) {
		List<String> words = new ArrayList<String>();
		Matcher word = CALL_WORD.matcher(command);
		while (word.find()) {
			for (int group = 1; group <= 3; group++) {
				if (word.group(group) != null) {
					words.add(word.group(group));
					break;
				}
			}
		}
		return words;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;
		while ((read = in.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/** One of the entrypoint's weights fetches: the call as written, and the arguments it names. */
	private static final class Call {

		private final String command;

		private final String artifact;

		private final String filename;

		private final String label;

		private Call(String command, String artifact, String filename, String label) {
			this.command = command;
			this.artifact = artifact;
			this.filename = filename;
			this.label = label;
		}
	}

	/** What the fetch said and how it exited, carried together so a failure message shows both. */
	private static final class Run {

		private final int exit;

		private final String output;

		private Run(int exit, String output) {
			this.exit = exit;
			this.output = output;
		}

		@Override
		public String toString() {
			return "  exit: " + exit + "\n  output: " + output;
		}
	}
}
