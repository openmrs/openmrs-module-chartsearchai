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
 * What {@code backend-init.sh}'s {@code configure_retrieval_gps} leaves in the database when the
 * start it runs in has verified no embedder — the composition {@link ModelDownloadIntegrityTest}'s
 * ledger cases and {@link ModelDownloadPinningGuardTest}'s source checks each see only one half of.
 *
 * <p><b>Why a third channel exists.</b> The ledger answers what THIS shell verified and
 * {@code gp_set_if_blank} preserves a row an earlier start wrote; both are right on their own, and
 * the state they compose to was not. On the shipped entrypoint the embedder goes through
 * {@code fetch_or_exit}, whose refusal ends the shell before this wiring runs, so what the gate's
 * decline answers is a start that reaches the wiring with nothing in the ledger — the swallowed-exit
 * residue ADR Decision 106 names, a fetch taken in a subshell, which the refusal cases below
 * construct deliberately. Such a start has deleted the file and publishes no path, but the row from the last
 * good start still names that now-absent file — so a safety keyed on reading the property back finds
 * it non-blank and leaves {@code querystore.bootstrap.autostart} on, which is the per-record
 * exception flood that function's own comment exists to prevent and measures the cost of. Neither
 * existing channel can see that: one drives the library without a database, the other reads source.
 * Only running the wiring against a store that REMEMBERS an earlier start does.
 *
 * <p><b>It runs the entrypoint's own functions, not a retelling of them.</b> Each function below is
 * taken verbatim out of {@code backend-init.sh} by name — a definition that moves or changes shape
 * fails this loudly rather than leaving it testing a stale copy — and sourced alongside the real
 * {@code scripts/model-manifest.sh}, so {@code require_verified} answers from a ledger real fetches
 * wrote. The test supplies two things and no logic: the variables the surrounding script assigns,
 * and a stand-in for the {@code mariadb} client, which is the database boundary the way
 * {@code ModelDownloadIntegrityTest}'s loopback server is the network one.
 *
 * <p><b>The residue, named rather than claimed away.</b> This drives the wiring function and the
 * library; it does not run the entrypoint end to end. Where the top-level fetches sit relative to
 * the wiring call is still {@code ModelDownloadPinningGuardTest}'s question, and whether a refusal
 * ends the shell at all is {@code ModelDownloadIntegrityTest}'s. The stand-in understands only the
 * statements this wiring issues and REFUSES anything else, so a statement it cannot answer fails a
 * case instead of being silently accepted — but it is not MariaDB, and a defect that needs real
 * server semantics is outside it.
 */
public class EntrypointRetrievalWiringTest {

	/** The container entrypoint, repo-relative — read through {@link EntrypointSource}. */
	private static final String ENTRYPOINT = EntrypointSource.ENTRYPOINT;

	/**
	 * The functions the retrieval wiring is composed of, in the order they are pasted into the
	 * harness. The list is what makes the harness's dependency on the entrypoint explicit: a
	 * function that stops existing under this name stops the run with a message naming it.
	 */
	private static final List<String> WIRING_FUNCTIONS = List.of("seed_sql", "db_reachable",
			"openmrs_schema_present", "schema_absent_because", "gp_set_if_blank", "gp_value",
			"configure_retrieval_gps");

	/**
	 * The artifacts the gate asks about, which the fixture manifest has to carry rows for.
	 * Reconciled against the entrypoint's own {@code require_verified} line before every case, so a
	 * rename there cannot leave this fixture quietly verifying artifacts nothing asks about.
	 */
	private static final List<String> GATED_ARTIFACTS = List.of("embedder-e5-base-v2-onnx",
			"embedder-e5-base-v2-vocab");

	private static final String MODEL_PATH_GP = "querystore.embedding.modelFilePath";

	private static final String VOCAB_PATH_GP = "querystore.embedding.vocabFilePath";

	private static final String AUTOSTART_GP = "querystore.bootstrap.autostart";

	private static final byte[] RECORDED_BYTES = "the embedder bytes the maintainers reviewed\n"
			.getBytes(StandardCharsets.UTF_8);

	/** One byte different and the same LENGTH, so the digest is what refuses it, not the size. */
	private static final byte[] UNRECORDED_BYTES = "the embedder bytes the maintainers reviewer\n"
			.getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path work;

	/** One file per global property, which is the whole of the stand-in's storage. */
	private Path store;

	/** Every statement the stand-in was handed, so a case can assert what was NOT issued. */
	private Path statements;

	private Path stubs;

	private Path onnx;

	private Path vocab;

	private Path manifest;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(Files.isExecutable(Paths.get("/bin/sh")), "POSIX /bin/sh is required to drive the entrypoint");
		assertEquals(GATED_ARTIFACTS, gatedArtifacts(), "backend-init.sh's require_verified line names other"
				+ " artifacts than the fixture manifest below carries, so these cases would drive a gate nothing"
				+ " can satisfy");

		store = Files.createDirectories(work.resolve("global-properties"));
		statements = work.resolve("statements.log");
		Files.write(statements, new byte[0]);

		stubs = Files.createDirectories(work.resolve("stub-bin"));
		Path client = stubs.resolve("mariadb");
		Files.write(client, mariadbStandIn().getBytes(StandardCharsets.UTF_8));
		assertTrue(client.toFile().setExecutable(true), "could not make the mariadb stand-in executable");

		Path data = Files.createDirectories(work.resolve("openmrs/data/querystore"));
		onnx = data.resolve("model.onnx");
		vocab = data.resolve("vocab.txt");

		StringBuilder rows = new StringBuilder();
		for (String artifact : GATED_ARTIFACTS) {
			// A url nothing can serve: every case here either places the recorded bytes itself, so
			// the library verifies what is already there and fetches nothing, or means the fetch to
			// fail. A port would be a race; an absent file is not.
			rows.append(artifact).append('\t').append(ModelManifest.sha256(RECORDED_BYTES)).append('\t')
					.append(RECORDED_BYTES.length).append('\t').append(work.resolve("never-served").toUri())
					.append('\n');
		}
		manifest = work.resolve("manifest-fixture.tsv");
		Files.write(manifest, rows.toString().getBytes(StandardCharsets.UTF_8));
	}

	// ---- the sweep goes off whenever no embedder this start verified is configured --------------

	/**
	 * <b>The returning deployment.</b> One good start wrote the path; this start's embedder is
	 * refused and its file deleted, in a subshell so the {@code exit} never reaches the entrypoint's
	 * shell — the residue ADR Decision 106 names as what the ledger, not the line-level guard,
	 * covers. The property still names the deleted file, so a safety that reads it back sees nothing
	 * wrong; the sweep has to go off on the gate's verdict instead.
	 */
	@Test
	public void theSweepGoesOffWhenTheEmbedderDidNotVerifyAndAnEarlierStartLeftItsPathBehind() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");

		Run run = run(refusedInASubshell());

		assertFalse(Files.exists(onnx), "the refusal did not delete the file, so this case is not the state it"
				+ " is about\n" + run);
		assertEquals("querystore/model.onnx", gp(MODEL_PATH_GP),
				"the earlier start's path is what makes this case; it must still be standing\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the embedder was refused and its file deleted, and the sweep was"
				+ " left on to fail once per record\n" + run);
		assertTrue(run.output.contains("the embedder did not verify in this start"),
				"the operator is not told which of the two reasons turned the sweep off\n" + run);
	}

	/** The virgin database: nothing has ever configured an embedder, and the sweep still goes off. */
	@Test
	public void theSweepGoesOffWhenNoEmbedderPathWasEverConfigured() throws Exception {
		given(AUTOSTART_GP, "true");

		Run run = run(refusedInASubshell());

		assertEquals("", gp(MODEL_PATH_GP), "a path was published for an embedder that was refused\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on with no embedder at all\n" + run);
	}

	/**
	 * The one case the blank-path test still answers on its own: the gate PASSES, and the write it
	 * gates does not take. {@code gp_set_if_blank} discards its own errors, so the property read
	 * back is the only thing that knows.
	 */
	@Test
	public void theSweepGoesOffWhenTheVerifiedEmbeddersPathCouldNotBeWritten() throws Exception {
		given(AUTOSTART_GP, "true");
		Files.write(onnx, RECORDED_BYTES);
		Files.write(vocab, RECORDED_BYTES);

		Run run = run(verified(), MODEL_PATH_GP);

		assertEquals("", gp(MODEL_PATH_GP), "the write was supposed to be refused; this case proves nothing if it"
				+ " landed\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on with no embedder path in the database\n"
				+ run);
		assertTrue(run.output.contains("no embedder path is configured"),
				"the reason given is the gate's, but the gate passed and the write is what failed\n" + run);
	}

	// ---- and stays on for a start that did verify ----------------------------------------------

	/**
	 * The control every refusal case needs: a gate that refused everything would pass all three
	 * above. Here the bytes on the volume are the ones the fixture row records, so the library
	 * verifies them without fetching, the ledger carries both ids, and both paths are published with
	 * the sweep untouched.
	 */
	@Test
	public void aVerifiedEmbedderPublishesBothPathsAndLeavesTheSweepAlone() throws Exception {
		given(AUTOSTART_GP, "true");
		Files.write(onnx, RECORDED_BYTES);
		Files.write(vocab, RECORDED_BYTES);

		Run run = run(verified());

		// The entrypoint publishes the path with /openmrs/data/ stripped off, which is a no-op for a
		// target that does not live under it — so this is the value the script built from the
		// variable the fetch verified, not a constant restated here.
		assertEquals(onnx.toString(), gp(MODEL_PATH_GP), "a verified embedder's path was not published\n" + run);
		assertEquals(vocab.toString(), gp(VOCAB_PATH_GP), "a verified vocab's path was not published\n" + run);
		assertEquals("true", gp(AUTOSTART_GP), "the sweep was turned off for a start that verified\n" + run);
		assertFalse(String.join("\n", issuedStatements()).contains("UPDATE global_property"),
				"the sweep was written to at all on a start with nothing to answer\n" + run);
	}

	/**
	 * An operator's own path survives a start that verifies. {@code gp_set_if_blank} is what extends
	 * that courtesy, and turning the sweep off must not have grown into overwriting what it finds.
	 */
	@Test
	public void aPathAnOperatorSetDeliberatelySurvivesAStartThatVerifies() throws Exception {
		given(MODEL_PATH_GP, "somewhere/else/model.onnx");
		given(AUTOSTART_GP, "true");
		Files.write(onnx, RECORDED_BYTES);
		Files.write(vocab, RECORDED_BYTES);

		Run run = run(verified());

		assertEquals("somewhere/else/model.onnx", gp(MODEL_PATH_GP), "an operator's own path was overwritten\n"
				+ run);
		assertEquals("true", gp(AUTOSTART_GP), "the sweep was turned off for a start that verified\n" + run);
	}

	// ---- driving the entrypoint's own wiring ----------------------------------------------------

	/**
	 * The shape of a refusal the entrypoint's shell never learns about: the fetch is real and so is
	 * the refusal, but {@code fetch_or_exit}'s {@code exit} leaves the background subshell rather
	 * than the script, so the wiring runs afterwards with an empty ledger. Unrecorded bytes are
	 * already at the target, which is what the refusal deletes.
	 */
	private List<String> refusedInASubshell() throws IOException {
		Files.write(onnx, UNRECORDED_BYTES);
		return List.of("fetch_or_exit " + GATED_ARTIFACTS.get(0) + " \"$ONNX_FILE\" 'the embedder' &", "wait");
	}

	/** Both artifacts verified the way a restart verifies them: off bytes already on the volume. */
	private List<String> verified() {
		List<String> lines = new ArrayList<String>();
		lines.add("fetch_or_exit " + GATED_ARTIFACTS.get(0) + " \"$ONNX_FILE\" 'the embedder'");
		lines.add("fetch_or_exit " + GATED_ARTIFACTS.get(1) + " \"$VOCAB_FILE\" 'the vocab'");
		return lines;
	}

	private Run run(List<String> preamble) throws Exception {
		return run(preamble, null);
	}

	/**
	 * Sources the real library, pastes the entrypoint's own wiring functions in, runs
	 * {@code preamble} — the fetches whose outcome the ledger then carries — and calls
	 * {@code configure_retrieval_gps}. {@code refuseWriteTo} names a property the stand-in will
	 * refuse to write, standing in for a database that rejects the statement.
	 */
	private Run run(List<String> preamble, String refuseWriteTo) throws Exception {
		List<String> script = new ArrayList<String>();
		script.add("PATH='" + stubs + "':$PATH");
		script.add("export PATH");
		script.add(". '" + ModuleSourceRoot.repoRoot().resolve(ModelManifest.LIBRARY) + "'");
		// What the entrypoint assigns around the wiring: the connection the stand-in answers for,
		// and the two targets the fetches above it write to.
		script.add("DB_HOST=only-the-stand-in-answers");
		script.add("DB_USER=openmrs");
		script.add("DB_PASS=openmrs");
		script.add("DB_NAME=openmrs");
		script.add("ONNX_FILE='" + onnx + "'");
		script.add("VOCAB_FILE='" + vocab + "'");
		List<String> entrypoint = Files.readAllLines(repo(ENTRYPOINT), StandardCharsets.UTF_8);
		for (String function : WIRING_FUNCTIONS) {
			script.add(EntrypointSource.functionText(entrypoint, function));
		}
		script.addAll(preamble);
		script.add("configure_retrieval_gps");

		Path driver = work.resolve("drive-" + System.nanoTime() + ".sh");
		Files.write(driver, (String.join("\n", script) + "\n").getBytes(StandardCharsets.UTF_8));

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", driver.toString());
		builder.environment().put("MODEL_MANIFEST_FILE", manifest.toString());
		builder.environment().put("MARIADB_STAND_IN_STORE", store.toString());
		builder.environment().put("MARIADB_STAND_IN_LOG", statements.toString());
		if (refuseWriteTo != null) {
			builder.environment().put("MARIADB_STAND_IN_REFUSE", refuseWriteTo);
		}
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the wiring did not finish");
		Run run = new Run(process.exitValue(), output);

		assertEquals(0, run.exit, "configure_retrieval_gps itself failed; in the entrypoint that is the"
				+ " \"step errored\" line, and everything below is then about a function that did not finish\n"
				+ run);
		List<String> issued = issuedStatements();
		assertFalse(issued.isEmpty(), "the wiring issued no statement at all, so this case measured nothing\n" + run);
		for (String statement : issued) {
			assertFalse(statement.startsWith("UNHANDLED: "), "the stand-in was handed a statement it cannot answer,"
					+ " so what the wiring did with it is unknown: " + statement + "\n" + run);
		}
		return run;
	}

	private List<String> issuedStatements() throws IOException {
		return Files.readAllLines(statements, StandardCharsets.UTF_8);
	}

	/** Seeds a global property the way an earlier start or an operator would have left it. */
	private void given(String property, String value) throws IOException {
		Files.write(store.resolve(property), value.getBytes(StandardCharsets.UTF_8));
	}

	/** What the store holds for a property, empty where no row was ever written. */
	private String gp(String property) throws IOException {
		Path row = store.resolve(property);
		return Files.isRegularFile(row) ? new String(Files.readAllBytes(row), StandardCharsets.UTF_8) : "";
	}

	/** The artifacts {@code backend-init.sh}'s own gate line asks {@code require_verified} about. */
	private static List<String> gatedArtifacts() throws IOException {
		Matcher gate = Pattern.compile("^\\s*if require_verified ([^;]+);\\s*then\\s*$").matcher("");
		for (String line : Files.readAllLines(repo(ENTRYPOINT), StandardCharsets.UTF_8)) {
			if (gate.reset(line).matches()) {
				return List.of(gate.group(1).trim().split("\\s+"));
			}
		}
		throw new IllegalStateException(ENTRYPOINT + " has no `if require_verified …; then` line, so the gate these"
				+ " cases drive no longer exists");
	}

	private static Path repo(String relative) {
		return ModuleSourceRoot.repoRoot().resolve(relative);
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

	/**
	 * A stand-in for the {@code mariadb} client, understanding only the statements this wiring
	 * issues: the reachability and schema probes, the two global-property reads, the
	 * insert-if-blank, and the update that turns the sweep off. Anything else is recorded and
	 * refused — a stand-in that accepted a statement it did not understand would let a case pass for
	 * a reason nobody checked.
	 *
	 * <p>Two semantics are MariaDB's and are what the cases turn on: {@code ON DUPLICATE KEY UPDATE
	 * … IF(property_value IS NULL OR property_value = '', …)} leaves a row that already carries a
	 * value standing, and an {@code UPDATE} matches nothing where the property was never written.
	 */
	private static String mariadbStandIn() {
		return String.join("\n",
				"#!/bin/sh",
				"_sql=''",
				"_want=no",
				"for _arg in \"$@\"; do",
				"\tif [ \"$_want\" = yes ]; then _sql=$_arg; _want=no; fi",
				"\tif [ \"$_arg\" = '-e' ]; then _want=yes; fi",
				"done",
				"_sql=$(printf '%s' \"$_sql\" | tr '\\n' ' ')",
				"printf '%s\\n' \"$_sql\" >> \"$MARIADB_STAND_IN_LOG\"",
				"_property=$(printf '%s' \"$_sql\" | sed -n \"s/.*property='\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"case \"$_sql\" in",
				"\t'SELECT 1'*)",
				"\t\techo 1 ;;",
				"\t*'information_schema.tables'*)",
				"\t\techo 4 ;;",
				"\t'SELECT COALESCE(property_value'*)",
				"\t\tif [ -f \"$MARIADB_STAND_IN_STORE/$_property\" ]; then",
				"\t\t\tcat \"$MARIADB_STAND_IN_STORE/$_property\"",
				"\t\telse",
				"\t\t\techo ''",
				"\t\tfi ;;",
				"\t'INSERT INTO global_property'*)",
				"\t\t_name=$(printf '%s' \"$_sql\" | sed -n \"s/.*VALUES ('\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"\t\t_value=$(printf '%s' \"$_sql\" | sed -n \"s/.*VALUES ('[^']*','\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"\t\tif [ -n \"$MARIADB_STAND_IN_REFUSE\" ] && [ \"$_name\" = \"$MARIADB_STAND_IN_REFUSE\" ]; then",
				"\t\t\techo \"mariadb stand-in: refusing to write $_name\" >&2",
				"\t\t\texit 1",
				"\t\tfi",
				"\t\tcase \"$_sql\" in",
				"\t\t\t*'IF(property_value IS NULL'*)",
				"\t\t\t\tif [ ! -s \"$MARIADB_STAND_IN_STORE/$_name\" ]; then",
				"\t\t\t\t\tprintf '%s' \"$_value\" > \"$MARIADB_STAND_IN_STORE/$_name\"",
				"\t\t\t\tfi ;;",
				"\t\t\t*)",
				"\t\t\t\tprintf '%s' \"$_value\" > \"$MARIADB_STAND_IN_STORE/$_name\" ;;",
				"\t\tesac ;;",
				"\t'UPDATE global_property SET property_value='*)",
				"\t\t_value=$(printf '%s' \"$_sql\" \\",
				"\t\t\t| sed -n \"s/^UPDATE global_property SET property_value='\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"\t\tif [ -f \"$MARIADB_STAND_IN_STORE/$_property\" ]; then",
				"\t\t\tprintf '%s' \"$_value\" > \"$MARIADB_STAND_IN_STORE/$_property\"",
				"\t\tfi ;;",
				"\t*)",
				"\t\tprintf 'UNHANDLED: %s\\n' \"$_sql\" >> \"$MARIADB_STAND_IN_LOG\"",
				"\t\techo \"mariadb stand-in: unhandled statement: $_sql\" >&2",
				"\t\texit 1 ;;",
				"esac",
				"exit 0",
				"");
	}

	/** What the wiring said and how it exited, carried together so a failure message shows both. */
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
