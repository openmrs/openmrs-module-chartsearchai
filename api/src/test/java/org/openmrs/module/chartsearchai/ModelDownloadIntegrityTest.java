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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * The behavioural half of issues #444 and #449: model files the module executes are fetched under a
 * digest committed in this repository, and bytes that do not match it never reach the filename the
 * module loads.
 *
 * <p><b>This drives the real shell.</b> {@code scripts/model-manifest.sh} is production code — it is
 * sourced by {@code backend-init.sh} (the published backend image's ENTRYPOINT) and by
 * {@code .github/workflows/build-standalone.yml} (the release pipeline for the README's download) —
 * and every case here executes it with {@code /bin/sh} rather than restating what it ought to do.
 * The composed step {@code fetch_and_verify_url} is what both call sites reach, through the id and
 * override forms above it, so that is what is tested: calling a download helper and a digest helper in sequence from Java would test an assembly
 * no call site uses, which is the failure the project instructions' composed-method rule names.
 *
 * <p><b>The bad bytes are SERVED, not simulated.</b> Both findings state their acceptance the same
 * way — "verify by serving a file with a differing digest and observing the entrypoint reject it" —
 * so the cases here stand up a loopback {@link HttpServer} and let curl fetch from it over the
 * network stack the entrypoint really uses. A test that wrote a bad file into place and called only
 * the digest comparison would skip the fetch, the {@code .partial} and the rename, which is where
 * the defect lived.
 *
 * <p><b>Both controls are measured.</b> A verification test that only shows a rejection cannot tell
 * a working check from one that rejects everything, so each rejection case has a counterpart that
 * differs in the one thing being rejected — the same served bytes accepted under their own digest,
 * the same fetch accepted at the recorded size — and the counterpart asserts the file is placed.
 *
 * @see ModelDownloadPinningGuardTest for the structural half — that each call site still routes
 *      through this library and still pins its revision, which no behaviour of this library can show
 */
public class ModelDownloadIntegrityTest {

	/** Exit codes {@code fetch_and_verify_url} contracts with its callers, which branch on them. */
	private static final int OK = 0;

	private static final int DIGEST_MISMATCH = 1;

	private static final int SIZE_MISMATCH = 2;

	private static final int DOWNLOAD_FAILED = 3;

	/** The library's code table is the one home for what each of these means. */
	private static final int UNRESOLVABLE_ARTIFACT = 4;

	private static final int HASH_UNAVAILABLE = 5;

	private static final byte[] GOOD_BYTES = "the bytes the maintainers reviewed\n".getBytes(StandardCharsets.UTF_8);

	/**
	 * The same LENGTH as {@link #GOOD_BYTES} and one byte different, so the digest is the only thing
	 * that can tell them apart. A substitution of a different length would be caught by the size
	 * check first and would never reach the comparison these cases are about.
	 */
	private static final byte[] SUBSTITUTED_BYTES = "the bytes the maintainers reviewer\n"
			.getBytes(StandardCharsets.UTF_8);

	private HttpServer server;

	private byte[] served = GOOD_BYTES;

	private int status = 200;

	@TempDir
	Path work;

	@BeforeEach
	public void startServer() throws IOException {
		assumeTrue(Files.isExecutable(Paths.get("/bin/sh")), "POSIX /bin/sh is required to drive the library");
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/model", exchange -> {
			if (status != 200) {
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(200, served.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(served);
			}
		});
		server.start();
	}

	@AfterEach
	public void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/model";
	}

	// ---- the composed step: fetch, verify, place -----------------------------------------------

	@Test
	public void aServedFileMatchingItsCommittedDigestIsPlacedAtTheNameTheModuleLoads() throws Exception {
		served = GOOD_BYTES;
		Path target = work.resolve("model.bin");

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(OK, result.exit, "a file matching its digest must be accepted\n" + result);
		assertTrue(Files.exists(target), "the verified file must be placed at the target name\n" + result);
		assertEquals(sha256(GOOD_BYTES), sha256(Files.readAllBytes(target)),
				"the placed file must be the bytes that were served");
		assertFalse(Files.exists(work.resolve("model.bin.partial")), "no .partial may be left behind\n" + result);
	}

	@Test
	public void aServedFileWhoseDigestDiffersNeverReachesTheNameTheModuleLoads() throws Exception {
		served = SUBSTITUTED_BYTES;
		Path target = work.resolve("model.bin");

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(DIGEST_MISMATCH, result.exit, "a substituted file must be refused\n" + result);
		assertFalse(Files.exists(target), "refused bytes must never reach the target name\n" + result);
		assertFalse(Files.exists(work.resolve("model.bin.partial")),
				"the refused download must be deleted, not left for curl -C - to resume\n" + result);
		assertTrue(result.output.contains(sha256(GOOD_BYTES)) && result.output.contains(sha256(SUBSTITUTED_BYTES)),
				"the refusal must report both the expected and the received digest\n" + result);
	}

	/**
	 * The case a fresh download cannot reach: bytes already on the persistent volume. A deployment
	 * provisioned before this check existed, or one whose volume was written to directly, holds a file
	 * the entrypoint would otherwise skip over on the strength of its name alone.
	 *
	 * <p>It is replaced rather than merely refused — ADR Decision 103 gives the reasoning. The case
	 * below is what "not accepted" looks like, and the two together are what say the replacement is
	 * not a way past the check.
	 */
	@Test
	public void aFileAlreadyOnTheVolumeThatDoesNotMatchIsReplacedByTheReviewedArtifact() throws Exception {
		served = GOOD_BYTES;
		Path target = work.resolve("model.bin");
		Files.write(target, SUBSTITUTED_BYTES);

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(OK, result.exit, "a stale file must be replaced by the reviewed artifact\n" + result);
		assertEquals(sha256(GOOD_BYTES), sha256(Files.readAllBytes(target)),
				"the file left behind must be the reviewed artifact, not the one that was there\n" + result);
	}

	@Test
	public void aFileAlreadyOnTheVolumeIsRefusedWhenTheReviewedArtifactCannotBeFetchedEither() throws Exception {
		served = SUBSTITUTED_BYTES;
		Path target = work.resolve("model.bin");
		Files.write(target, SUBSTITUTED_BYTES);

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(DIGEST_MISMATCH, result.exit, "bytes that match nowhere must be refused\n" + result);
		assertFalse(Files.exists(target), "an existing file that does not match must be deleted\n" + result);
		assertFalse(Files.exists(work.resolve("model.bin.partial")),
				"the refused replacement must be deleted too\n" + result);
	}

	@Test
	public void aFileAlreadyOnTheVolumeThatMatchesIsKeptAndNotRefetched() throws Exception {
		status = 500; // any fetch at all would fail the case
		Path target = work.resolve("model.bin");
		Files.write(target, GOOD_BYTES);

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(OK, result.exit, "an existing file that matches must be accepted without refetching\n" + result);
		assertTrue(Files.exists(target), "a matching file must be kept\n" + result);
	}

	/**
	 * The size is checked before the digest, and the point of the order is the MESSAGE: a transfer
	 * that stopped short and a substitution are different things to an operator, and only the first
	 * has a remedy they own. So the case has to be wrong on BOTH counts — an earlier form served
	 * correct bytes against a wrong expected size, which the digest check would have passed anyway,
	 * and swapping the two branches of {@code _mm_verify_file} left it green while silently turning
	 * every truncated transfer into "looks like a substitution".
	 */
	@Test
	public void aTruncatedTransferIsRefusedAsAShortFileRatherThanAsASubstitution() throws Exception {
		served = Arrays.copyOf(GOOD_BYTES, 10); // wrong length AND wrong digest
		Path target = work.resolve("model.bin");

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(SIZE_MISMATCH, result.exit,
				"a short file must report the size code, not the digest code\n" + result);
		assertFalse(result.output.contains(sha256(GOOD_BYTES)),
				"the size refusal must not lead with a digest comparison\n" + result);
		assertFalse(Files.exists(target), "a short file must never reach the target name\n" + result);
	}

	/**
	 * Code 5 — the file could not be hashed at all — is the one refusal that leaves the file where it
	 * is, and the library's own contract says only codes 1 and 2 promise a deletion. An earlier form
	 * of {@code fetch_and_verify_url} fell through to the replacement path for every non-zero code,
	 * so an unhashable file was announced as "Replacing..." while it stayed on disk and stayed
	 * served — the exact state #444 is about.
	 */
	@Test
	public void aFileThatCannotBeHashedIsReportedAsUnverifiedRatherThanReplaced() throws Exception {
		Path onlyFetchTools = pathWith("no-hashing-tool", List.of("curl", "stat", "rm", "mv"));
		assumeTrue(which("stat") != null, "stat is needed to reach the hashing step");
		Path target = work.resolve("model.bin");
		Files.write(target, GOOD_BYTES);

		Result result = library("fetch_and_verify_url '" + url() + "' '" + sha256(GOOD_BYTES) + "' '"
				+ GOOD_BYTES.length + "' '" + target + "' 'test model'", manifest(), onlyFetchTools);

		assertEquals(HASH_UNAVAILABLE, result.exit, "an unhashable file must report its own code\n" + result);
		assertTrue(Files.exists(target), "a file that was never hashed must not be deleted\n" + result);
		assertFalse(result.output.contains("Replacing"),
				"nothing may announce a replacement for a file that is still there\n" + result);
	}

	@Test
	public void anErrorPageIsRefusedRatherThanRenamedIntoPlace() throws Exception {
		status = 404;
		Path target = work.resolve("model.bin");

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertEquals(DOWNLOAD_FAILED, result.exit, "a non-2xx response must fail the fetch, not be accepted\n" + result);
		assertFalse(Files.exists(target), "a non-2xx response must never reach the target name\n" + result);
	}

	/**
	 * The manually-dispatched standalone build is the one path where the url is not the manifest's,
	 * so it is the one path where a digest can be missing. #449's criterion is that every bundled
	 * model is checked against a digest, which means the absence of one has to stop the build rather
	 * than fall back to fetching it unverified.
	 */
	@Test
	public void anOverriddenUrlWithNoDigestIsRefusedRatherThanFetchedUnverified() throws Exception {
		served = GOOD_BYTES;
		Path target = work.resolve("model.bin");

		Result result = library("fetch_and_verify_override '" + url() + "' '' '" + target + "' 'the dispatched model'"
				+ " gguf_sha256");

		assertEquals(UNRESOLVABLE_ARTIFACT, result.exit, "an override with no digest must be refused\n" + result);
		assertFalse(Files.exists(target), "an unverifiable override must not be fetched at all\n" + result);
		assertTrue(result.output.contains("gguf_sha256"),
				"the refusal must name the input the operator has to supply\n" + result);
	}

	@Test
	public void anOverriddenUrlWithItsDigestIsFetchedAndPlaced() throws Exception {
		served = GOOD_BYTES;
		Path target = work.resolve("model.bin");

		Result result = library("fetch_and_verify_override '" + url() + "' '" + sha256(GOOD_BYTES) + "' '" + target
				+ "' 'the dispatched model' gguf_sha256");

		assertEquals(OK, result.exit, "an override carrying its digest must be accepted\n" + result);
		assertEquals(sha256(GOOD_BYTES), sha256(Files.readAllBytes(target)),
				"the placed file must be the bytes that were served");
	}

	/**
	 * {@code fetch_and_verify} is the form the call sites use — {@code backend-init.sh} for all four
	 * of its artifacts, and the standalone build for everything a push build fetches — and every
	 * other case here drives the url form one level below it. Two fresh review agents independently
	 * showed what that left open: with the id form's delegation mutated, or its whole body replaced
	 * by a bare undigested {@code curl}, the suite stayed green. The second of those IS the defect
	 * both findings report, restored in four lines.
	 */
	@Test
	public void fetchingByIdPlacesTheRowsArtifactAndRefusesASubstitution() throws Exception {
		Path fixture = work.resolve("manifest-by-id.tsv");
		Files.write(fixture, ("probe-artifact " + sha256(GOOD_BYTES) + " " + GOOD_BYTES.length + " " + url() + "\n")
				.getBytes(StandardCharsets.UTF_8));
		Path target = work.resolve("model.bin");

		served = GOOD_BYTES;
		Result accepted = library("fetch_and_verify probe-artifact '" + target + "' 'the probe artifact'", fixture);

		assertEquals(OK, accepted.exit, "the row's own artifact must be accepted\n" + accepted);
		assertEquals(sha256(GOOD_BYTES), sha256(Files.readAllBytes(target)),
				"the id form must place the bytes the row's url served\n" + accepted);

		served = SUBSTITUTED_BYTES;
		Files.delete(target);
		Result refused = library("fetch_and_verify probe-artifact '" + target + "' 'the probe artifact'", fixture);

		assertEquals(DIGEST_MISMATCH, refused.exit, "a substitution must be refused through the id form too\n"
				+ refused);
		assertFalse(Files.exists(target), "refused bytes must never reach the target name\n" + refused);
	}

	/**
	 * {@code file_sha256} has three branches and the suite only ever executes the first, because
	 * {@code sha256sum} is found on every machine that runs it. The other two are the ones that run
	 * where it is not — and {@code openssl} needs its own output parsing, since it prints
	 * {@code SHA2-256(file)= <hex>} rather than {@code <hex>  file}.
	 *
	 * <p>Each case runs with a PATH holding exactly ONE of the three, so the branch under test is the
	 * only one reachable, and compares the answer against Java's own digest of the same bytes rather
	 * than against another shell tool.
	 */
	@Test
	public void everyHashingToolTheLibraryFallsBackToAgreesWithTheOthers() throws Exception {
		Path file = work.resolve("hashed.bin");
		Files.write(file, GOOD_BYTES);
		List<String> exercised = new ArrayList<String>();

		for (String tool : List.of("sha256sum", "openssl", "shasum")) {
			if (which(tool) == null) {
				continue;
			}
			Path only = pathWith("only-" + tool, List.of(tool));

			Result result = library("file_sha256 '" + file + "'", manifest(), only);

			assertEquals(0, result.exit, "file_sha256 failed with only " + tool + " on PATH\n" + result);
			assertEquals(sha256(GOOD_BYTES), result.output.trim(),
					"the digest from " + tool + " does not match the bytes\n" + result);
			exercised.add(tool);
		}

		assertTrue(exercised.contains("sha256sum") && exercised.size() >= 2,
				"this case has to reach more than one branch to mean anything; it exercised " + exercised);
	}

	/** Where {@code tool} really lives, or null when this machine does not have it. */
	private static Path which(String tool) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "command -v " + tool);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
		process.waitFor(30, TimeUnit.SECONDS);
		return process.exitValue() == 0 && !out.isEmpty() ? Paths.get(out) : null;
	}

	/**
	 * A resume that cannot succeed must not be retried forever. {@code curl -C -} exits 33 when the
	 * origin answers a {@code Range} request with a whole 200 — which a caching proxy in front of the
	 * container will do — and nothing else in the path deletes the {@code .partial}, so the next
	 * start made the same impossible request. For the embedder that is a container which stops and,
	 * with no restart policy, stays stopped.
	 *
	 * <p>The server here does not honour {@code Range}, which is what makes the case reachable.
	 */
	@Test
	public void aResumeThatCannotSucceedDiscardsThePartialInsteadOfRetryingForever() throws Exception {
		served = GOOD_BYTES;
		Path target = work.resolve("model.bin");
		Path partial = work.resolve("model.bin.partial");
		Files.write(partial, Arrays.copyOf(GOOD_BYTES, 12));

		Result result = fetchAndVerify(url(), sha256(GOOD_BYTES), GOOD_BYTES.length, target, "test model");

		assertFalse(Files.exists(partial) && Files.size(partial) == 12,
				"a partial that could not be resumed must not be left for the next attempt to retry\n" + result);
		if (result.exit != OK) {
			assertEquals(DOWNLOAD_FAILED, result.exit, "a failed resume is a failed fetch\n" + result);
			assertFalse(Files.exists(partial), "the unusable partial must be discarded\n" + result);
		}
	}

	/**
	 * A file that cannot be MEASURED is as unverified as one that cannot be hashed, and must be left
	 * alone rather than deleted. Answering 0 for a missing {@code stat} instead deleted correct files
	 * and refetched them forever, reporting them as 0 bytes.
	 */
	@Test
	public void aFileThatCannotBeMeasuredIsLeftAloneRatherThanRefusedAsShort() throws Exception {
		Path noStat = pathWith("no-stat", List.of("curl", "rm", "mv", "sha256sum", "openssl", "shasum"));
		Path target = work.resolve("model.bin");
		Files.write(target, GOOD_BYTES);

		Result result = library("fetch_and_verify_url '" + url() + "' '" + sha256(GOOD_BYTES) + "' '"
				+ GOOD_BYTES.length + "' '" + target + "' 'test model'", manifest(), noStat);

		assertEquals(HASH_UNAVAILABLE, result.exit, "an unmeasurable file must not be refused as short\n" + result);
		assertTrue(Files.exists(target), "a file that was never measured must not be deleted\n" + result);
	}

	/**
	 * The refusal names where the expected digest came from, because on the dispatched path it did
	 * not come from the manifest and sending an operator there would send them to a file the manifest
	 * deliberately does not record.
	 */
	@Test
	public void anOverriddenUrlsRefusalNamesTheInputItsDigestCameFromAndNotTheManifest() throws Exception {
		served = SUBSTITUTED_BYTES;
		Path target = work.resolve("model.bin");

		Result result = library("fetch_and_verify_override '" + url() + "' '" + sha256(GOOD_BYTES) + "' '" + target
				+ "' 'the dispatched model' gguf_sha256", manifest());

		assertEquals(DIGEST_MISMATCH, result.exit, "a substituted override must be refused\n" + result);
		assertTrue(result.output.contains("gguf_sha256"),
				"the refusal must name the input the digest came from\n" + result);
		assertFalse(result.output.contains("model-manifest.tsv"),
				"the refusal must not send an operator to a file the manifest does not record\n" + result);
	}

	/**
	 * A digest input with no url of its own would be accepted and then ignored, and the build would
	 * bundle the manifest's model under a digest that does match it — #449's own shape inverted. The
	 * rule is in the library rather than the workflow because the input names have to be spelled:
	 * an inline version composed {@code vocab_model_url}, which is not an input that exists.
	 */
	@Test
	public void aDigestInputWithNoUrlOfItsOwnStopsTheBuildAndNamesBothInputs() throws Exception {
		Result orphan = library("require_url_for_digest '' 'abc' vocab_url vocab_sha256");

		assertEquals(UNRESOLVABLE_ARTIFACT, orphan.exit, "a digest with no url must stop the build\n" + orphan);
		assertTrue(orphan.output.contains("vocab_url") && orphan.output.contains("vocab_sha256"),
				"the refusal must name both inputs as the dispatch form spells them\n" + orphan);

		assertEquals(0, library("require_url_for_digest '' '' vocab_url vocab_sha256").exit,
				"neither given is a push build and must pass");
		assertEquals(0, library("require_url_for_digest 'http://x' 'abc' vocab_url vocab_sha256").exit,
				"both given is the supported dispatch and must pass");
	}

	/**
	 * Decision 103 publishes a measured 5x spread as the REASON for the fallback order, and the
	 * agreement case above cannot see the order at all — it drives one tool at a time. This one puts
	 * the slow tool on PATH beside a fast one and asserts the slow one is not what runs.
	 */
	@Test
	public void theSlowestHashingToolIsOnlyReachedWhenNothingFasterIsThere() throws Exception {
		assumeTrue(which("sha256sum") != null && which("shasum") != null, "needs both tools to compare");
		Path both = pathWith("both-tools", List.of("sha256sum"));
		Path marker = work.resolve("shasum-was-used");
		Files.write(both.resolve("shasum"), ("#!/bin/sh\ntouch '" + marker + "'\nexec " + which("shasum")
				+ " \"$@\"\n").getBytes(StandardCharsets.UTF_8));
		both.resolve("shasum").toFile().setExecutable(true);
		Path file = work.resolve("hashed.bin");
		Files.write(file, GOOD_BYTES);

		Result result = library("file_sha256 '" + file + "'", manifest(), both);

		assertEquals(sha256(GOOD_BYTES), result.output.trim(), "the digest must still be right\n" + result);
		assertFalse(Files.exists(marker),
				"shasum ran while a faster tool was on PATH, so the measured order is not the one taken\n" + result);
	}

	/**
	 * For an artifact the module cannot start without, a refusal must STOP the script rather than
	 * return a code someone has to remember to branch on.
	 *
	 * <p><b>This is the behaviour that replaced a source-reading guard defeated four times.</b>
	 * While the entrypoint spelled the branch itself, every reading of the source was defeated one
	 * more way and each fix opened the next; the branch now lives in {@code fetch_or_exit}, so the
	 * question is what the shell DOES. The case asserts it by putting a line after the call and
	 * checking it never runs — which is exactly what the entrypoint puts there
	 * ({@code echo "Embedder ready..."} and then the global-property write).
	 */
	@Test
	public void aRefusalOfAnArtifactTheModuleCannotStartWithoutStopsTheScript() throws Exception {
		Path fixture = work.resolve("manifest-or-exit.tsv");
		Files.write(fixture, ("critical-artifact " + sha256(GOOD_BYTES) + " " + GOOD_BYTES.length + " " + url()
				+ "\n").getBytes(StandardCharsets.UTF_8));
		Path target = work.resolve("model.bin");
		String call = "fetch_or_exit critical-artifact '" + target + "' 'the critical artifact' 'a hint line'\n"
				+ "echo REACHED-THE-LINE-AFTER";

		served = SUBSTITUTED_BYTES;
		Result substituted = library(call, fixture);

		assertEquals(DIGEST_MISMATCH, substituted.exit, "a substitution must leave its own status\n" + substituted);
		assertFalse(substituted.output.contains("REACHED-THE-LINE-AFTER"),
				"the script ran on past a refusal, which is what reaches the global-property write\n" + substituted);

		// The caller's hint lines are the size diagnostic — what a digest mismatch gets instead is
		// the generic refusal, because "the export changed shape" is the wrong thing to tell someone
		// whose bytes are the wrong bytes at the right length.
		served = Arrays.copyOf(GOOD_BYTES, 10);
		Result truncated = library(call, fixture);

		assertEquals(SIZE_MISMATCH, truncated.exit, "a short file must leave its own status\n" + truncated);
		assertFalse(truncated.output.contains("REACHED-THE-LINE-AFTER"),
				"the script ran on past a short file\n" + truncated);
		assertTrue(truncated.output.contains("a hint line"),
				"the caller's size diagnostic must be printed\n" + truncated);
		assertFalse(substituted.output.contains("a hint line"),
				"the size diagnostic must not be printed for a substitution\n" + substituted);

		served = GOOD_BYTES;
		Files.deleteIfExists(target);
		Result accepted = library(call, fixture);

		assertEquals(OK, accepted.exit, "a verified artifact must not stop the script\n" + accepted);
		assertTrue(accepted.output.contains("REACHED-THE-LINE-AFTER"),
				"the script must continue when the artifact verifies\n" + accepted);
	}

	// ---- the manifest is the one committed record ----------------------------------------------

	/**
	 * Each lookup is compared against the artifact's OWN row, read from the file independently. An
	 * earlier form of this asserted only the SHAPE of each answer — that a digest is 64 hex, that a
	 * url is https — and a mutation making the lookup match by prefix rather than exactly left it
	 * green, because a wrong row's digest is shaped exactly like the right one's. Shape cannot
	 * distinguish rows; the row can.
	 */
	@Test
	public void everyLookupReturnsTheFieldOnThatArtifactsOwnRow() throws Exception {
		List<String[]> rows = ModelManifest.rows();

		for (String[] row : rows) {
			String id = row[0];
			assertEquals(row[1], library("manifest_sha256 " + id).output.trim(),
					"manifest_sha256 " + id + " did not return the digest on that id's row");
			assertEquals(row[2], library("manifest_bytes " + id).output.trim(),
					"manifest_bytes " + id + " did not return the byte count on that id's row");
			assertEquals(row[3], library("manifest_url " + id).output.trim(),
					"manifest_url " + id + " did not return the url on that id's row");
		}
		assertTrue(rows.size() >= 2, "the manifest must carry the artifacts both call sites fetch, found " + rows.size());
	}

	@Test
	public void aFetchOfAnIdTheManifestDoesNotCarryDownloadsNothing() throws Exception {
		Path target = work.resolve("model.bin");

		Result result = library("fetch_and_verify no-such-artifact '" + target + "' 'a model nobody recorded'");

		assertEquals(UNRESOLVABLE_ARTIFACT, result.exit, "an unrecorded artifact must not be fetched\n" + result);
		assertFalse(Files.exists(target), "an unrecorded artifact must leave no file behind\n" + result);
	}

	@Test
	public void anIdTheManifestDoesNotCarryFailsRatherThanResolvingToNothing() throws Exception {
		Result result = library("manifest_sha256 no-such-artifact");

		assertNotEquals(0, result.exit, "an unknown id must fail loudly\n" + result);
		assertTrue(result.output.contains("no-such-artifact"), "the failure must name the id it could not find\n"
				+ result);
	}

	/**
	 * One committed id is a prefix of another, so a lookup that matched loosely would hand back a
	 * neighbour's digest and every later check would pass against the wrong artifact. The committed
	 * manifest cannot show this on its own: with the rows in their current order a prefix match
	 * happens to reach the right row anyway, so a mutation loosening the comparison left
	 * {@link #everyLookupReturnsTheFieldOnThatArtifactsOwnRow} green. This asks the question the
	 * committed order cannot — both orders, so neither can be the one that passes by luck.
	 */
	@Test
	public void anIdThatIsAPrefixOfAnotherResolvesToItsOwnRowInEitherOrder() throws Exception {
		String shortRow = "shared-prefix " + "a".repeat(64) + " 10 " + pinnedUrl("short");
		String longRow = "shared-prefix-more " + "b".repeat(64) + " 20 " + pinnedUrl("long");

		for (String order : List.of(shortRow + "\n" + longRow, longRow + "\n" + shortRow)) {
			Path fixture = work.resolve("manifest-" + System.nanoTime() + ".tsv");
			Files.write(fixture, order.getBytes(StandardCharsets.UTF_8));

			assertEquals(pinnedUrl("short"), library("manifest_url shared-prefix", fixture).output.trim(),
					"the shorter id resolved to a neighbouring row, rows in this order:\n" + order);
			assertEquals(pinnedUrl("long"), library("manifest_url shared-prefix-more", fixture).output.trim(),
					"the longer id resolved to a neighbouring row, rows in this order:\n" + order);
		}
	}

	private static String pinnedUrl(String file) {
		return "https://huggingface.co/owner/repo/resolve/" + "0".repeat(40) + "/" + file;
	}

	// ---- driving the real library ---------------------------------------------------------------

	private static Path manifest() {
		return ModelManifest.path();
	}

	private Result fetchAndVerify(String url, String sha256, long bytes, Path target, String label) throws Exception {
		return library("fetch_and_verify_url '" + url + "' '" + sha256 + "' '" + bytes + "' '" + target + "' '" + label
				+ "'");
	}

	private Result library(String call) throws Exception {
		return library(call, manifest());
	}

	/**
	 * A directory holding symlinks to exactly the named tools, for driving the library with a PATH
	 * that can reach nothing else. A tool this machine does not have is skipped, and the caller says
	 * what it needs.
	 */
	private Path pathWith(String name, List<String> tools) throws Exception {
		Path only = Files.createDirectories(work.resolve("path-" + name));
		for (String tool : tools) {
			Path real = which(tool);
			if (real != null && !Files.exists(only.resolve(tool))) {
				Files.createSymbolicLink(only.resolve(tool), real);
			}
		}
		return only;
	}

	private Result library(String call, Path manifestFile) throws Exception {
		return library(call, manifestFile, null);
	}

	private Result library(String call, Path manifestFile, Path onlyPathEntry) throws Exception {
		Path script = work.resolve("drive-" + System.nanoTime() + ".sh");
		Files.write(script, (". '" + ModuleSourceRoot.repoRoot().resolve(ModelManifest.LIBRARY) + "'\n" + call + "\n").getBytes(StandardCharsets.UTF_8));

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", script.toString());
		builder.environment().put("MODEL_MANIFEST_FILE", manifestFile.toString());
		if (onlyPathEntry != null) {
			builder.environment().put("PATH", onlyPathEntry.toString());
		}
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the library call did not finish: " + call);
		return new Result(process.exitValue(), output, call);
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

	private static String sha256(byte[] bytes) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest(bytes)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	/** What the shell said and how it exited, carried together so a failure message shows both. */
	private static final class Result {

		private final int exit;

		private final String output;

		private final String call;

		private Result(int exit, String output, String call) {
			this.exit = exit;
			this.output = output;
			this.call = call;
		}

		@Override
		public String toString() {
			return "  call: " + call + "\n  exit: " + exit + "\n  output: " + output;
		}
	}
}
