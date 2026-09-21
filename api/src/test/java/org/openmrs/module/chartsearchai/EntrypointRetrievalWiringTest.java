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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * {@code fetch_or_degrade}, whose refusal now leaves the start RUNNING, so this wiring is reached
 * with an empty ledger by an ordinary refusal as well as by the subshell residue ADR Decision 106
 * names. The row the last good start wrote is still standing either way, and what it names depends
 * on WHICH refusal: the library's own code table gives codes 1, 2 and 6 a deletion and codes 3, 4
 * and 5 none — code 4, an artifact no manifest row resolves, never opens the target at all. So a
 * safety keyed on reading that property back finds it non-blank in both, and leaves
 * {@code querystore.bootstrap.autostart} on: the per-record exception flood that function's own
 * comment exists to prevent where the file is gone, and querystore embedding clinical questions
 * with bytes this start did not check where it is not. Neither existing channel can see either:
 * one drives the library without a database, the other reads source. Only running the wiring
 * against a store that REMEMBERS an earlier start does.
 *
 * <p><b>It runs the entrypoint's own functions, not a retelling of them.</b> Each function below is
 * taken verbatim out of {@code backend-init.sh} by name — a definition that moves or changes shape
 * fails this loudly rather than leaving it testing a stale copy — and sourced alongside the real
 * {@code scripts/model-manifest.sh}, so {@code require_verified} answers from a ledger real fetches
 * wrote. The test supplies two things and no logic: the variables the surrounding script assigns,
 * and a stand-in for the {@code mariadb} client, which is the database boundary the way
 * {@code ModelDownloadIntegrityTest}'s loopback server is the network one.
 *
 * <p><b>Most cases here drive the wiring function; one drives the whole file.</b>
 * {@link #theEntrypointsOwnStatementsLeaveTheStartRunningWhenTheEmbedderIsRefused} runs every
 * top-level statement of {@code backend-init.sh}, with the edges that reach outside a test
 * redirected, because what a refusal does to the START is not a property of the function the rest of
 * these cases call. Where a fetch SITS relative to the wiring call is still
 * {@code ModelDownloadPinningGuardTest}'s question, and what the library itself returns is
 * {@code ModelDownloadIntegrityTest}'s.
 *
 * <p><b>The residue, named rather than claimed away.</b> The stand-in understands only the
 * statements these starts issue and REFUSES anything else, so a statement it cannot answer fails a
 * case instead of being silently accepted — but it is not MariaDB, and a defect that needs real
 * server semantics is outside it. Nothing here reaches the network: the manifest rows and the demo
 * dump url both name a file nothing serves.
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
			"withdraw_embedder_paths", "quarantine_unverified_embedder", "configure_retrieval_gps");

	/**
	 * The artifacts the gate asks about, which the fixture manifest has to carry rows for.
	 * Reconciled against the entrypoint's own {@code require_verified} line before every case, so a
	 * rename there cannot leave this fixture quietly verifying artifacts nothing asks about.
	 */
	private static final List<String> GATED_ARTIFACTS = List.of("embedder-e5-base-v2-onnx",
			"embedder-e5-base-v2-vocab");

	private static final String MODEL_PATH_GP = "querystore.embedding.modelFilePath";

	private static final String VOCAB_PATH_GP = "querystore.embedding.vocabFilePath";

	/**
	 * querystore's third path under the same application data directory — the query encoder of a
	 * dual-encoder model, which it resolves with {@code optional=true} and which nothing in this
	 * repository ever points at a file. A deployment that pointed it at the file the entrypoint
	 * provisions has a row naming bytes a refused start did not verify, so the withdrawal takes this
	 * one back as well.
	 */
	private static final String QUERY_MODEL_PATH_GP = "querystore.embedding.queryModelFilePath";

	private static final String AUTOSTART_GP = "querystore.bootstrap.autostart";

	/**
	 * The retrieval switch, which names no file and which the summary line shows first. A start past
	 * its first good one reads it back non-blank whatever this start did, {@code gp_set_if_blank}
	 * leaving a written row standing, so a case can ask what the line says about a row it knows is
	 * there.
	 */
	private static final String ENABLED_GP = "chartsearchai.querystore.enabled";

	/** Where the refusal's own diagnosis is recorded, readable over REST. */
	private static final String EMBEDDER_STATUS_GP = "chartsearchai.models.embedderStatus";

	/**
	 * What the entrypoint prints when {@code command -v mariadb} finds nothing. It is what tells
	 * that branch apart from a client that IS present and cannot connect: both reach the same
	 * quarantine, and a REAL client found on the host writes no stand-in log, so an empty log is
	 * no evidence the client was absent. Spelled as a literal, so a rewording in
	 * {@code backend-init.sh} fails the case that reads it rather than quietly widening what that
	 * case accepts.
	 */
	private static final String NO_CLIENT_IN_THE_IMAGE = "the mariadb client is absent from this image";

	private static final byte[] RECORDED_BYTES = "the embedder bytes the maintainers reviewed\n"
			.getBytes(StandardCharsets.UTF_8);

	/** One byte different and the same LENGTH, so the digest is what refuses it, not the size. */
	private static final byte[] UNRECORDED_BYTES = "the embedder bytes the maintainers reviewer\n"
			.getBytes(StandardCharsets.UTF_8);

	/** The seed's own sentinel, which says the dataset a start would otherwise fetch is already in. */
	private static final String SEEDED_DATASET_GP = "chartsearchai.demo.seededDataset";

	/**
	 * Echoed where {@code backend-init.sh} hands off to Tomcat, so a case can ask whether the start
	 * reached its own last statement rather than inferring it from something that ran earlier.
	 */
	private static final String REACHED_STARTUP = "the start reached the hand-off to /openmrs/startup.sh";

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

	/**
	 * What the {@code information_schema} probe answers, {@code null} for the stand-in's own
	 * default of four. The gate {@code openmrs_schema_present} closes on was stubbed permanently
	 * open until round 2 of the amendment's review, so no case could reach past it.
	 */
	private String schemaTables;

	/** Whether the stand-in refuses every statement, the way a database that is not answering does. */
	private boolean databaseUnreachable;

	/** Whether the {@code mariadb} client is on PATH at all — the first of the two old returns. */
	private boolean clientAbsent;

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
	 * refused and its file deleted, in a subshell so neither the status nor the ledger entry reaches
	 * the entrypoint's shell — the residue ADR Decision 106 names as what the ledger, not the
	 * line-level guard, covers. The row an earlier start wrote is what {@code gp_set_if_blank} would
	 * leave standing, naming a file that is no longer there, so the arm has to withdraw it and turn
	 * the sweep off on the gate's verdict.
	 */
	@Test
	public void theSweepGoesOffWhenTheEmbedderDidNotVerifyAndAnEarlierStartLeftItsPathBehind() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");

		Run run = run(refusedInASubshell());

		assertFalse(Files.exists(onnx), "the refusal did not delete the file, so this case is not the state it"
				+ " is about\n" + run);
		assertEquals("", gp(MODEL_PATH_GP), "the earlier start's path still names the file this start's refusal"
				+ " deleted\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the embedder was refused and its file deleted, and the sweep was"
				+ " left on to fail once per record\n" + run);
		assertTrue(run.output.contains("the embedder did not verify in this start"),
				"the operator is not told which of the two reasons turned the sweep off\n" + run);
	}

	/**
	 * <b>The refusal that deletes nothing, which is the one the withheld write cannot cover.</b> A
	 * backend image whose manifest resolves neither embedder row refuses both at code 4 without
	 * opening either file, so the unverified copy an earlier start left is still on the volume. The
	 * row that names it is still in the database and reads exactly like one this start verified;
	 * withholding the write leaves it standing, and querystore then embeds clinical questions with
	 * bytes nothing checked — the state #444 and ADR Decision 106 exist to remove, reached with a
	 * green healthcheck and OpenMRS running. Both paths have to be withdrawn.
	 */
	@Test
	public void bothEmbedderPathsAreWithdrawnWhenTheRefusalLeftTheUnverifiedFileOnTheVolume() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(QUERY_MODEL_PATH_GP, "querystore/model.onnx");
		given(AUTOSTART_GP, "true");

		Run run = run(refusedWithNothingDeleted());

		assertTrue(Files.exists(onnx) && Files.exists(vocab), "the refusal deleted the files, so this case is not"
				+ " the state it is about — it is the one above\n" + run);
		assertTrue(run.output.contains("Chart search cannot run without a verified copy of this file"),
				"nothing was refused, so this case would pass on a start that verified\n" + run);
		assertEquals("", gp(MODEL_PATH_GP), "querystore is still pointed at an ONNX file on the volume that this"
				+ " start refused to verify\n" + run);
		assertEquals("", gp(VOCAB_PATH_GP), "querystore is still pointed at a vocab file on the volume that this"
				+ " start refused to verify\n" + run);
		assertEquals("", gp(QUERY_MODEL_PATH_GP), "querystore's query-encoder row still names the ONNX file on the"
				+ " volume that this start refused to verify, and it is loaded through the same resolver as the"
				+ " two rows beside it\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on over a path this start did not verify\n"
				+ run);
	}

	/**
	 * <b>The entrypoint's own statements, and what a refusal does to the start.</b> The library
	 * returns rather than exiting, and {@code ModelDownloadIntegrityTest} drives that; what nothing
	 * drove is the SCRIPT. This runs {@code backend-init.sh} itself, every top-level statement of it
	 * from the first line through to the hand-off to Tomcat, over a manifest that resolves neither
	 * embedder row — and asks whether the start reaches the retrieval wiring and then that hand-off.
	 *
	 * <p><b>Collecting the two fetch commands was not enough, and it took a round to see why.</b>
	 * The earlier form of this case took the two {@code fetch_or_degrade} statements out of the file
	 * and appended an {@code echo} of its own, so "the statement after them" was the harness's and
	 * not the entrypoint's next statement. Two one-line edits restore the outage in full and each
	 * left all four classes that read this file green with {@code sh -n} and
	 * {@code shellcheck -s sh -S warning} clean (measured 2026-09-21): a {@code require_verified}
	 * over both {@link #GATED_ARTIFACTS} followed by {@code || exit 1}, written as the next
	 * top-level statement — which the ledger-gate exemption's own pattern does not even admit, so it
	 * is not exempted but simply unread — and a {@code set -e} after the shebang, under which the
	 * real library exits 4 at the refusal and the statement after it never runs. Both are inside
	 * what this case now runs. ADR Decision 106.
	 */
	@Test
	public void theEntrypointsOwnStatementsLeaveTheStartRunningWhenTheEmbedderIsRefused() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");
		given(SEEDED_DATASET_GP, demoSeedTag());

		Run run = runTheWholeEntrypointWithItsEmbedderRefused();

		assertTrue(run.output.contains("Chart search cannot run without a verified copy of this file"),
				"nothing was refused, so this case says nothing about what a refusal does to the start\n" + run);
		assertTrue(run.output.contains("already loaded"), "the demo seed did not read its own sentinel as already"
				+ " satisfied, so this start was on its way to fetching a dataset dump\n" + run);
		assertTrue(gp(EMBEDDER_STATUS_GP).contains(GATED_ARTIFACTS.get(0) + ":4"), "the refusal this start took"
				+ " is not in the channel a deployment nobody can open a shell on reads, which is either a start"
				+ " that never reached the retrieval wiring or a refusal taken where this shell could not read"
				+ " it — an empty record tells them apart, and this is what was recorded: "
				+ gp(EMBEDDER_STATUS_GP) + "\n" + run);
		assertEquals("", gp(MODEL_PATH_GP), "the start reached the wiring and left an earlier start's path naming"
				+ " bytes this start refused\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the start reached the wiring and left the sweep on\n" + run);
		assertTrue(run.output.contains(REACHED_STARTUP), "backend-init.sh ended the start on a refused embedder"
				+ " instead of handing off to Tomcat without chart search, which is the outage ADR Decision 106's"
				+ " amendment measures\n" + run);
		assertEquals(0, run.exit, "the start did not survive its own statements on a refused embedder\n" + run);
		assertTheStandInUnderstoodEveryStatement(run);
	}

	// ---- and reaches the withdrawal whatever else the start cannot do --------------------------

	/**
	 * <b>The schema probe answering no did not use to reach the withdrawal at all.</b>
	 * {@code configure_retrieval_gps} opened with two unconditional returns, and this was the
	 * second: a probe answer of anything below four tables ended the function before the gate.
	 * The probe is the one of the two that can be WRONG while the row stands — it wants
	 * {@code global_property} and three others, so a database carrying the row without one of the
	 * others answers no and the {@code UPDATE} on it lands. Driven with the count the stand-in
	 * reports set to zero and the last good start's rows in the store: the withdrawal has to be
	 * issued, and the sweep switched off, on a start that could not otherwise configure anything.
	 */
	@Test
	public void theWithdrawalIsIssuedEvenWhereTheSchemaProbeAnswersNo() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");
		schemaTables = "0";

		Run run = run(refusedWithNothingDeleted());

		assertTrue(run.output.contains("carries no OpenMRS schema"), "the schema gate did not fire, so this case is"
				+ " not the state it is about\n" + run);
		assertEquals("", gp(MODEL_PATH_GP), "querystore is still pointed at an ONNX file this start refused to"
				+ " verify, because the schema probe ended the function before the withdrawal\n" + run);
		assertEquals("", gp(VOCAB_PATH_GP), "the vocab row was left standing for the same reason\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on over a path this start did not verify\n"
				+ run);
	}

	/**
	 * <b>The withdrawal that cannot be issued at all, and the instrument that needs no database.</b>
	 * A database the entrypoint cannot reach answers every statement with a failure, so the row the
	 * last good start wrote cannot be read, cannot be blanked, and goes on naming an ONNX file this
	 * start refused at a code that deletes nothing. {@code startup.sh} waits for the database on its
	 * own afterwards, so OpenMRS can serve against one this step missed — the row is not a dead
	 * letter. What is left is the FILE, and moving it out from under the name the row carries leaves
	 * querystore throwing "Model file not found" where a landed withdrawal would have it throwing on
	 * an unconfigured property.
	 *
	 * <p><b>And it is the branch the summary line cannot speak for.</b> Every {@code gp_value} here
	 * FAILS, and a failed read answers the empty string — the same answer a blanked row gives — so
	 * the last thing this start says about the rows showed each of them empty while all of them were
	 * standing. That is the shape ADR Decision 106 records a whole arm being removed for, a log
	 * asserting the opposite so nobody looks, and on this branch README says the container log is
	 * this start's only account of itself.
	 */
	@Test
	public void theUnverifiedCopyIsPutOutOfReachWhenTheDatabaseCouldNotBeReached() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");
		given(ENABLED_GP, "true");
		databaseUnreachable = true;

		Run run = execute(refusedWithNothingDeleted(), null);

		assertTrue(run.output.contains("could not be reached"), "the database was reachable, so this case is not"
				+ " the state it is about\n" + run);
		assertEquals("querystore/model.onnx", gp(MODEL_PATH_GP), "the row was withdrawn after all, so what this"
				+ " case then reads about the file says nothing\n" + run);
		assertFalse(Files.exists(onnx), "the row still names querystore/model.onnx and the unverified ONNX file is"
				+ " still there under that name, so querystore embeds clinical questions with bytes this start"
				+ " refused\n" + run);
		assertFalse(Files.exists(vocab), "the vocab this start refused is still under the name its row carries\n"
				+ run);
		assertTrue(Files.exists(quarantined(onnx)) && Files.exists(quarantined(vocab)),
				"the refused copies were not moved aside, so an operator has lost them\n" + run);
		assertEquals("true", gp(AUTOSTART_GP), "the sweep switch landed after all, so what this case then reads"
				+ " off the summary line says nothing\n" + run);
		assertEquals("true", gp(ENABLED_GP), "the retrieval switch was rewritten after all, so what this case"
				+ " then reads off the summary line about it says nothing\n" + run);

		// Found by the prefix it is written with rather than by position, so a line printed after it
		// does not change what this reads.
		String shown = "";
		for (String line : wiringLines(run)) {
			if (line.startsWith("[retrieval-wiring] chartsearchai.querystore.enabled=")) {
				shown = line;
			}
		}
		assertFalse(shown.isEmpty(), "the wiring printed no line showing the rows at all, so this case cannot read"
				+ " what it told an operator about them\n" + run);
		assertFalse(shown.contains("chartsearchai.querystore.enabled= "), "the retrieval switch is shown blank,"
				+ " which reads as chart search being off, over a row still carrying true: " + shown + "\n" + run);
		assertFalse(shown.contains("querystore.embedding.modelFilePath= "), "a row this start could not read is"
				+ " shown blank, over one still naming the file it refused: " + shown + "\n" + run);
		assertFalse(shown.endsWith("bootstrap.autostart="), "the sweep row is shown blank, which reads as off,"
				+ " over a row still carrying true: " + shown + "\n" + run);
	}

	/**
	 * <b>The operator-facing lines about a copy taken out of reach claim no refusal where nothing
	 * was refused.</b> The quarantine's failure line and the line that calls it used to assert the
	 * moved copy was one this start refused, and two shapes reach the arm with that false: one
	 * artifact refused and the other verified, where a VERIFIED file is moved aside, and a
	 * verification taken where this shell cannot read it, which refuses nothing at all —
	 * {@code MODEL_MANIFEST_REFUSED} is empty, and {@code _embedder_status} beside them already gets
	 * that right. The {@code .unverified} suffix stays: it is the EMBEDDER's verdict, the unit
	 * everywhere in that function, and no per-file name is true of whichever copy did verify.
	 *
	 * <p>This drives the second shape, with a database that answers the reads
	 * and rejects the model path's {@code UPDATE} so the diagnosis itself still lands and can be read
	 * back as the control, and asks the whole of what the wiring told an operator — a line at odds
	 * with the property beside it sends them looking for a refusal that never happened.
	 */
	@Test
	public void theQuarantinesOwnLinesClaimNoRefusalWhereNothingWasRefused() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");
		// The subshell shape fetches the ONNX alone and its refusal deletes it, so this is the copy
		// left for the quarantine to move — an earlier start's vocab, which nothing in this start
		// refused either.
		Files.write(vocab, UNRECORDED_BYTES);

		Run run = run(refusedInASubshell(), MODEL_PATH_GP);

		assertTrue(gp(EMBEDDER_STATUS_GP).contains("no refusal was recorded"), "this shell recorded a refusal of"
				+ " its own, so this case is the other shape and says nothing about wording a refusal nobody"
				+ " took: " + gp(EMBEDDER_STATUS_GP) + "\n" + run);
		assertTrue(Files.exists(quarantined(vocab)), "no copy was taken out of reach, so the lines this case reads"
				+ " were never printed\n" + run);
		for (String line : wiringLines(run)) {
			assertFalse(line.contains("refus"), "the wiring told an operator a refusal this shell never took: "
					+ line + "\n" + run);
		}
	}

	/**
	 * <b>What the diagnosis cannot say, stated here rather than left to be found.</b> The status
	 * property goes through the same {@code seed_sql} as the withdrawal, so on the branches that
	 * reach the quarantine because nothing can be written — no {@code mariadb} client, and a
	 * database that is not answering — it is not written either: REST goes on serving the last good
	 * start's value while the row it names has been moved to {@code .unverified}. No shell that
	 * cannot reach the database can close that, so what is owed is that the claim in
	 * {@code README.md} says where the channel holds, and that this is pinned rather than assumed.
	 * This is the database-not-answering branch;
	 * {@link #theUnverifiedCopyIsPutOutOfReachWhenTheMariadbClientIsAbsent} pins the other one by
	 * asserting the line the entrypoint prints for an absent client, which is what tells the two
	 * apart — {@link #NO_CLIENT_IN_THE_IMAGE} carries why an empty stand-in log does not. The
	 * stand-in IS the client here and is handed every statement this branch issues, refusing each
	 * one the way a database that is not answering does.
	 */
	@Test
	public void theDiagnosisStaysTheLastStartsWhereThisStartCouldNotWriteIt() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(EMBEDDER_STATUS_GP, "verified in this start");
		databaseUnreachable = true;

		Run run = execute(refusedWithNothingDeleted(), null);

		assertTrue(run.output.contains("could not record " + EMBEDDER_STATUS_GP), "the start neither recorded a"
				+ " diagnosis nor said it could not, so an operator has no line for either\n" + run);
		assertEquals("verified in this start", gp(EMBEDDER_STATUS_GP), "this start's refusal reached the property"
				+ " after all, so README may claim the channel holds on this branch too\n" + run);
		assertTrue(Files.exists(quarantined(onnx)), "the copy was not moved aside, so this case is not the state"
				+ " the stale diagnosis is about\n" + run);
	}

	/**
	 * <b>The first of the two returns: no {@code mariadb} client in the image.</b> Nothing here can
	 * read or write a global property, so the same instrument applies — and what proves the client
	 * really was absent is the line the entrypoint prints for it. An empty stand-in log does not:
	 * a real client found on the host's PATH writes that log no more than a missing one does, so
	 * the log alone reads here just as it would on a start that found one and could not connect.
	 */
	@Test
	public void theUnverifiedCopyIsPutOutOfReachWhenTheMariadbClientIsAbsent() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(AUTOSTART_GP, "true");
		clientAbsent = true;

		Run run = execute(refusedWithNothingDeleted(), null);

		assertTrue(run.output.contains(NO_CLIENT_IN_THE_IMAGE), "the start never printed the line it prints"
				+ " for an absent mariadb client, so this case did not drive that branch: either a client was"
				+ " found, or the function returned before reaching the line\n" + run);
		assertTrue(issuedStatements().isEmpty(), "a mariadb client answered, so this case did not drive an image"
				+ " without one: " + issuedStatements() + "\n" + run);
		assertTrue(run.output.contains("Chart search cannot run without a verified copy of this file"),
				"nothing was refused, so this case says nothing about what the start then did\n" + run);
		assertFalse(Files.exists(onnx), "the row an earlier start wrote still names the unverified ONNX file and"
				+ " nothing here could withdraw it\n" + run);
		assertTrue(Files.exists(quarantined(onnx)), "the refused copy was not moved aside\n" + run);
	}

	/**
	 * <b>The withdrawal that is issued and does not land.</b> Both statements were
	 * {@code >/dev/null 2>&1 || true}, so a database that answers the reads and rejects the
	 * {@code UPDATE} left the start printing "so its paths are blanked" over a row that still named
	 * the file. Reading the read-back is not the detector either — it answers empty for a database
	 * that could not be asked. So the statement's own status is read, and a withdrawal that did not
	 * land falls back to the file.
	 */
	@Test
	public void theUnverifiedCopyIsPutOutOfReachWhenTheWithdrawalWasRejected() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");

		Run run = run(refusedWithNothingDeleted(), MODEL_PATH_GP);

		assertEquals("querystore/model.onnx", gp(MODEL_PATH_GP), "the write was supposed to be rejected; this case"
				+ " proves nothing if it landed\n" + run);
		assertFalse(run.output.contains("so its paths are blanked"), "the start said the paths were blanked over a"
				+ " row that still names the file\n" + run);
		assertFalse(Files.exists(onnx), "the row still names the unverified ONNX file and the file is still there"
				+ " under that name\n" + run);
		assertTrue(Files.exists(quarantined(onnx)), "the refused copy was not moved aside\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on over a path this start did not verify\n"
				+ run);
	}

	/**
	 * <b>A rejected withdrawal on a database the schema probe answers no for.</b> The arm used to
	 * skip the quarantine whenever the database was reachable and the four-table probe answered
	 * below four, on the ground that a database carrying no OpenMRS schema carries no
	 * {@code global_property} row to take back either. The probe cannot answer that: it is the one
	 * of the two predictions that can be WRONG while a row stands, which the comment below the gate
	 * already said of it, and a {@code DB_NAME} that does not name the database OpenMRS uses answers
	 * no for a schema that is entirely there — both probes query without a database argument while
	 * every write passes it. So a start refused at a code that deletes nothing moved nothing and
	 * reached {@code exec /openmrs/startup.sh} with querystore still pointed at the unverified ONNX.
	 * Driven with both of the knobs the stand-in's javadoc calls deliberately independent: the count
	 * below four, and a database that answers the reads and rejects the {@code UPDATE}.
	 */
	@Test
	public void theUnverifiedCopyIsPutOutOfReachWhereTheSchemaProbeAnsweredNoAndTheWithdrawalWasRejected()
			throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");
		schemaTables = "0";

		Run run = run(refusedWithNothingDeleted(), MODEL_PATH_GP);

		assertTrue(run.output.contains("carries no OpenMRS schema"), "the schema gate did not fire, so this case is"
				+ " not the state it is about\n" + run);
		assertEquals("querystore/model.onnx", gp(MODEL_PATH_GP), "the write was supposed to be rejected; this case"
				+ " proves nothing if it landed\n" + run);
		assertTrue(run.output.contains("could not be confirmed"), "the start took neither the landed-withdrawal arm"
				+ " nor the one that falls back to the file, so what it did with the row is unknown\n" + run);
		assertFalse(Files.exists(onnx), "the row still names querystore/model.onnx and the unverified ONNX file is"
				+ " still there under that name, so querystore embeds clinical questions with bytes this start"
				+ " refused\n" + run);
		assertTrue(Files.exists(quarantined(onnx)) && Files.exists(quarantined(vocab)),
				"the copies this start could not verify were not moved aside\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on over a path this start did not verify\n"
				+ run);
	}

	/**
	 * <b>Both withdrawals are issued, because the first one's rejection is no verdict on the
	 * second.</b> The two {@code UPDATE}s were chained with {@code || return 1}, so a database that
	 * rejected the model path's never had the vocab's issued at all — and querystore resolves the
	 * vocab with {@code optional=false} too, so a vocab row left standing names half an embedder
	 * this start did not verify, under a name the file is still at wherever the quarantine cannot
	 * move it.
	 */
	@Test
	public void theVocabsWithdrawalIsIssuedEvenWhereTheModelPathsWasRejected() throws Exception {
		given(MODEL_PATH_GP, "querystore/model.onnx");
		given(VOCAB_PATH_GP, "querystore/vocab.txt");
		given(AUTOSTART_GP, "true");

		Run run = run(refusedWithNothingDeleted(), MODEL_PATH_GP);

		assertEquals("querystore/model.onnx", gp(MODEL_PATH_GP), "the write was supposed to be rejected; this case"
				+ " proves nothing if it landed\n" + run);
		assertEquals("", gp(VOCAB_PATH_GP), "the vocab row still names a file this start did not verify, because"
				+ " the model path's rejection returned before the vocab's own statement was issued\n" + run);
	}

	/**
	 * <b>The vocab half of the read-back, which the claim beside it used to overstate.</b> The
	 * comment said the test answers "the gate PASSED and the write it gates did not take", and it
	 * read one of the two writes. querystore resolves the vocab path with {@code optional=false}
	 * as well, so a vocab the write never reached throws once per record exactly as a missing model
	 * path does — while {@code modelFilePath} reads back non-blank and the sweep stays on.
	 */
	@Test
	public void theSweepGoesOffWhenTheVerifiedVocabsPathCouldNotBeWritten() throws Exception {
		given(AUTOSTART_GP, "true");
		Files.write(onnx, RECORDED_BYTES);
		Files.write(vocab, RECORDED_BYTES);

		Run run = run(verified(), VOCAB_PATH_GP);

		assertEquals(onnx.toString(), gp(MODEL_PATH_GP), "the model path was supposed to land; this case is about"
				+ " the other one failing alone\n" + run);
		assertEquals("", gp(VOCAB_PATH_GP), "the vocab write was supposed to be rejected; this case proves nothing"
				+ " if it landed\n" + run);
		assertEquals("false", gp(AUTOSTART_GP), "the sweep was left on with no vocab for it to tokenize with\n"
				+ run);
	}

	// ---- and says which artifact, and why, where REST can read it ------------------------------

	/**
	 * <b>The diagnosis, in the one channel a deployment nobody can open a shell on has.</b> A
	 * withdrawn path and a switched-off sweep say chart search is off and nothing about why: a
	 * digest refusal, a missing manifest row and a failed transfer are one state seen from outside,
	 * and only some of them are recoverable by restarting. The second premise of ADR Decision 106's
	 * amendment is that nobody on {@code chartsearchai.openmrs.org} could read a container log, so
	 * the artifact id and the library's code are recorded where {@code seed_status} and
	 * {@code record_cpu_breadcrumb} record theirs.
	 *
	 * <p>The second run is what says the row is not a one-way latch: a start that verifies has to
	 * replace an earlier start's refusal rather than leave it standing, or the property tells an
	 * operator about a refusal that is over.
	 */
	@Test
	public void theRefusedArtifactAndItsCodeAreRecordedWhereRestCanReadThemAndAreReplacedOnceItVerifies()
			throws Exception {
		Run refused = run(refusedWithNothingDeleted());

		assertTrue(gp(EMBEDDER_STATUS_GP).contains(GATED_ARTIFACTS.get(0) + ":4"), "the refused artifact and the"
				+ " library's code for it are not readable over REST, so a refusal cannot be told from a manifest"
				+ " that is missing a row: " + gp(EMBEDDER_STATUS_GP) + "\n" + refused);
		assertFalse(gp(EMBEDDER_STATUS_GP).contains(onnx.toString()), "the diagnosis carries the path to bytes"
				+ " this start refused, which is what the withdrawal beside it takes back: "
				+ gp(EMBEDDER_STATUS_GP) + "\n" + refused);

		Files.write(onnx, RECORDED_BYTES);
		Files.write(vocab, RECORDED_BYTES);
		Run verified = run(verified());

		assertEquals("verified in this start", gp(EMBEDDER_STATUS_GP), "the earlier start's refusal is still what"
				+ " an operator reads on a start that verified\n" + verified);
	}

	/**
	 * A verification taken where this shell cannot see it is the one shape that reaches the decline
	 * arm with nothing refused — ADR Decision 106's fail-SHUT direction, where the bytes verify and
	 * a healthy deployment configures no retrieval. It is recorded as that rather than as a refusal,
	 * because a restart is no remedy for it and the refusal codes would send an operator looking for
	 * one.
	 */
	@Test
	public void aVerificationThisShellCannotSeeIsRecordedAsThatRatherThanAsARefusal() throws Exception {
		Run run = run(refusedInASubshell());

		assertTrue(gp(EMBEDDER_STATUS_GP).startsWith("not verified in this start"),
				"a start that published no path recorded no verdict at all: " + gp(EMBEDDER_STATUS_GP) + "\n" + run);
		assertTrue(gp(EMBEDDER_STATUS_GP).contains("no refusal was recorded"), "a refusal this shell never took is"
				+ " reported as one, which points an operator at a restart: " + gp(EMBEDDER_STATUS_GP) + "\n" + run);
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
	 * the refusal, but it happens in a background subshell, so neither its status nor its ledger
	 * entry reaches the script and the wiring runs afterwards with an empty ledger. Unrecorded
	 * bytes are already at the target, which is what the refusal deletes.
	 */
	private List<String> refusedInASubshell() throws IOException {
		Files.write(onnx, UNRECORDED_BYTES);
		return List.of("fetch_or_degrade " + GATED_ARTIFACTS.get(0) + " \"$ONNX_FILE\" 'the embedder' &", "wait");
	}

	/**
	 * The refusal that costs the deployment nothing on disk: the manifest resolves neither artifact,
	 * so {@code fetch_or_degrade} answers 4 without ever opening the target and the unverified copy
	 * an earlier start left is still on the volume when the wiring runs. This is the shape of a
	 * backend image whose {@code model-manifest.tsv} row was renamed or dropped, and it is a refusal
	 * the entrypoint's OWN shell takes — no subshell — so the start runs on to the wiring by the
	 * ordinary path. Code 5, a copy the library could not hash, reaches the arm in the same state
	 * and is not driven here: residue, rather than a claim about it.
	 */
	private List<String> refusedWithNothingDeleted() throws Exception {
		Files.write(onnx, UNRECORDED_BYTES);
		Files.write(vocab, UNRECORDED_BYTES);
		List<String> lines = new ArrayList<String>();
		lines.add("MODEL_MANIFEST_FILE='" + unresolvableManifest() + "'");
		lines.addAll(entrypointEmbedderFetches());
		return lines;
	}

	/**
	 * A manifest whose rows name neither gated artifact, so {@code fetch_or_degrade} answers 4 for
	 * both without ever opening a target — the shape of a backend image whose {@code
	 * model-manifest.tsv} row was renamed or dropped. Written beside the fixture the good runs use
	 * rather than replacing it, so a case chooses which manifest the start reads.
	 */
	private Path unresolvableManifest() throws Exception {
		Path unresolvable = work.resolve("manifest-naming-nothing-the-gate-asks-about.tsv");
		StringBuilder rows = new StringBuilder();
		for (String artifact : GATED_ARTIFACTS) {
			rows.append("renamed-").append(artifact).append('\t').append(ModelManifest.sha256(RECORDED_BYTES))
					.append('\t').append(RECORDED_BYTES.length).append('\t')
					.append(work.resolve("never-served").toUri()).append('\n');
		}
		Files.write(unresolvable, rows.toString().getBytes(StandardCharsets.UTF_8));
		return unresolvable;
	}

	/**
	 * The entrypoint's own embedder fetch statements, read out of {@code backend-init.sh} as whole
	 * logical commands so a continuation line is part of the statement it continues. Taken rather
	 * than retold for the reason the wiring functions are: what a case here drives is then the text
	 * that ships, so an {@code || exit 1} appended to one of them — the edit anyone "restoring
	 * strictness" reaches for, and the one shape the library's own {@code return} cannot rule out —
	 * is inside what these cases run.
	 */
	private static List<String> entrypointEmbedderFetches() throws IOException {
		List<String> lines = Files.readAllLines(repo(ENTRYPOINT), StandardCharsets.UTF_8);
		List<String> fetches = new ArrayList<String>();
		Map<String, Integer> found = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#") || EntrypointSource.continuesTheLineAbove(lines, i)) {
				continue;
			}
			String command = EntrypointSource.logicalCommand(lines, i);
			for (String artifact : GATED_ARTIFACTS) {
				if (command.startsWith("fetch_or_degrade " + artifact + " ")) {
					found.put(artifact, found.getOrDefault(artifact, 0) + 1);
					fetches.add(command);
				}
			}
		}
		// Per artifact rather than a total, so two statements for one of them and none for the
		// other cannot add up to the right count.
		for (String artifact : GATED_ARTIFACTS) {
			assertEquals(1, found.getOrDefault(artifact, 0), ENTRYPOINT + " does not carry exactly one statement"
					+ " beginning `fetch_or_degrade " + artifact + " `, so these cases would be driving something"
					+ " other than what it ships. Read: " + fetches);
		}
		return fetches;
	}

	/**
	 * Runs the whole of {@code backend-init.sh} with the {@code mariadb} stand-in on PATH and the
	 * manifest that resolves neither embedder row, so both of the entrypoint's own embedder fetches
	 * refuse at code 4 — by the ordinary path, in the start's own shell, and with the copies an
	 * earlier start left still on the volume.
	 *
	 * <p>Nothing here may reach the network. The manifest rows and the demo-seed dump url are both
	 * pointed at a file nothing serves, so a fetch of either fails rather than being attempted, and
	 * the seed's own sentinel is seeded besides.
	 */
	private Run runTheWholeEntrypointWithItsEmbedderRefused() throws Exception {
		Files.write(onnx, UNRECORDED_BYTES);
		Files.write(vocab, UNRECORDED_BYTES);
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", theWholeEntrypointRewrittenToRunHere().toString());
		builder.environment().put("PATH", stubs + ":" + builder.environment().get("PATH"));
		builder.environment().put("MODEL_MANIFEST_FILE", unresolvableManifest().toString());
		builder.environment().put("MARIADB_STAND_IN_STORE", store.toString());
		builder.environment().put("MARIADB_STAND_IN_LOG", statements.toString());
		builder.environment().put("CHARTSEARCHAI_DEMO_DUMP_URL", work.resolve("never-served").toUri().toString());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the entrypoint did not finish");
		return new Run(process.exitValue(), output);
	}

	/**
	 * The whole of {@code backend-init.sh}, with the edges that reach outside a test rewritten and
	 * nothing else. Each is asserted present before it is replaced, so a rename there stops this
	 * loudly rather than leaving it driving a file whose outside edges it no longer redirects:
	 *
	 * <ul>
	 * <li>the re-exec as another OS user, which only a root shell takes and which no test can
	 * follow — inert on an ordinary machine, and what keeps a build running as root from failing
	 * this case for a reason that is not about a refusal;</li>
	 * <li>the hand-off to Tomcat, replaced by an echo of {@link #REACHED_STARTUP} so that reaching
	 * the entrypoint's own last statement is something a case can read;</li>
	 * <li>the sourced library, at the path {@code Dockerfile.backend} installs it to;</li>
	 * <li>{@code /openmrs/data}, the volume this start provisions into, rewritten under the case's
	 * temporary directory — which is where the fixture targets already sit, so the entrypoint's own
	 * {@code ONNX_FILE} and {@code VOCAB_FILE} come out as the two files the case placed;</li>
	 * <li>the two roots the runtime properties are searched under, collapsed to the case's own, so a
	 * machine that really carries either cannot hand this start another instance's credentials.</li>
	 * </ul>
	 *
	 * <p>Everything else the entrypoint does, it does: the demo seed's own gate, the installer
	 * correction, the runtime-properties write, the retrieval wiring and the CPU breadcrumb all run
	 * against the stand-in.
	 */
	private Path theWholeEntrypointRewrittenToRunHere() throws IOException {
		Path root = work.resolve("openmrs");
		assertTrue(root.toString().matches("[^\\s']+"), "this case interpolates " + root + " into shell that"
				+ " cannot quote all of it, so a temporary directory carrying a space or a quote would change"
				+ " what the entrypoint reads");
		String text = new String(Files.readAllBytes(repo(ENTRYPOINT)), StandardCharsets.UTF_8);
		// Every edge goes to a placeholder before any real path goes in, because the paths going in
		// carry the strings being looked for: this repository's own checkout path contains
		// "/openmrs", and the volume's replacement ends in "/openmrs/data". So a rewrite written the
		// obvious way redirects an earlier rewrite's output — measured 2026-09-21, where the library
		// landed at a path spliced out of the checkout and the temporary directory both.
		text = replacingOnce(text, "exec /openmrs/startup.sh", "@@HAND_OFF@@");
		text = replacingOnce(text, "exec runuser -u openmrs -- \"$0\" \"$@\"", "@@NO_REEXEC@@");
		text = replacingOnce(text, ". /usr/local/bin/model-manifest.sh", "@@LIBRARY@@");
		// Both search roots collapse to the case's own, so a machine that really carries one of them
		// cannot hand this start another instance's database credentials.
		text = replacingOnce(text, "find /openmrs /usr/local/tomcat", "find @@ROOT@@");
		text = replacingEvery(text, "/openmrs/data", "@@VOLUME@@");
		text = replacingOnce(text, "@@HAND_OFF@@", "echo '" + REACHED_STARTUP + "'");
		text = replacingOnce(text, "@@NO_REEXEC@@",
				"echo '[test] the re-exec as another OS user is not followed here'");
		text = replacingOnce(text, "@@LIBRARY@@",
				". '" + ModuleSourceRoot.repoRoot().resolve(ModelManifest.LIBRARY) + "'");
		text = replacingOnce(text, "@@ROOT@@", root.toString());
		text = replacingEvery(text, "@@VOLUME@@", root.resolve("data").toString());
		assertFalse(text.contains("@@"), "a placeholder this rewrite put in is still in the file it is about to"
				+ " run, so some edge of the entrypoint is not redirected");
		Path driver = work.resolve("backend-init-driven.sh");
		Files.write(driver, text.getBytes(StandardCharsets.UTF_8));
		return driver;
	}

	/** {@code text} with its one occurrence of {@code target} replaced, or a failure naming it. */
	private static String replacingOnce(String text, String target, String replacement) {
		assertEquals(1, text.split(Pattern.quote(target), -1).length - 1, ENTRYPOINT + " does not carry `" + target
				+ "` exactly once, so this harness would drive a file it has not redirected");
		return text.replace(target, replacement);
	}

	/** {@code text} with every occurrence of {@code target} replaced, of which there has to be one. */
	private static String replacingEvery(String text, String target, String replacement) {
		assertTrue(text.contains(target), ENTRYPOINT + " no longer carries `" + target + "`, so this harness would"
				+ " drive a file it has not redirected");
		return text.replace(target, replacement);
	}

	/**
	 * The demo dataset tag {@code backend-init.sh} assigns, read out of it so the store a case hands
	 * the whole entrypoint looks like a steady-state deploy's: the seed's sentinel already carries
	 * the tag, so the seed says so and skips rather than reaching for a dataset dump.
	 */
	private static String demoSeedTag() throws IOException {
		Matcher tag = Pattern.compile("^DEMO_SEED_TAG=\"([^\"]+)\"\\s*$").matcher("");
		for (String line : Files.readAllLines(repo(ENTRYPOINT), StandardCharsets.UTF_8)) {
			if (tag.reset(line).matches()) {
				return tag.group(1);
			}
		}
		throw new IllegalStateException(ENTRYPOINT + " no longer assigns DEMO_SEED_TAG, so this case cannot tell"
				+ " its demo seed that the dataset it would otherwise download is already loaded");
	}

	/** Both artifacts verified the way a restart verifies them: off bytes already on the volume. */
	private List<String> verified() {
		List<String> lines = new ArrayList<String>();
		lines.add("fetch_or_degrade " + GATED_ARTIFACTS.get(0) + " \"$ONNX_FILE\" 'the embedder'");
		lines.add("fetch_or_degrade " + GATED_ARTIFACTS.get(1) + " \"$VOCAB_FILE\" 'the vocab'");
		return lines;
	}

	private Run run(List<String> preamble) throws Exception {
		return run(preamble, null);
	}

	/**
	 * {@link #execute} plus the invariants every case rests on: the wiring function finished, it
	 * issued at least one statement, and the stand-in understood all of them. A case whose subject
	 * is one of those — whether the start reached the wiring at all — drives {@code execute}
	 * directly, so the failure names the property rather than one of these.
	 */
	private Run run(List<String> preamble, String refuseWriteTo) throws Exception {
		Run run = execute(preamble, refuseWriteTo);
		assertEquals(0, run.exit, "configure_retrieval_gps itself failed; in the entrypoint that is the"
				+ " \"step errored\" line, and everything below is then about a function that did not finish\n"
				+ run);
		assertTheStandInUnderstoodEveryStatement(run);
		return run;
	}

	/**
	 * At least one statement reached the stand-in and it understood all of them. A statement it
	 * cannot answer fails the case rather than being silently accepted, which is what keeps a case
	 * from passing for a reason nobody checked.
	 */
	private void assertTheStandInUnderstoodEveryStatement(Run run) throws IOException {
		List<String> issued = issuedStatements();
		assertFalse(issued.isEmpty(), "no statement reached the database at all, so this case measured nothing\n"
				+ run);
		for (String statement : issued) {
			assertFalse(statement.startsWith("UNHANDLED: "), "the stand-in was handed a statement it cannot answer,"
					+ " so what the start did with it is unknown: " + statement + "\n" + run);
		}
	}

	/**
	 * Sources the real library, pastes the entrypoint's own wiring functions in, runs
	 * {@code preamble} — the fetches whose outcome the ledger then carries — and calls
	 * {@code configure_retrieval_gps}. {@code refuseWriteTo} names a property the stand-in will
	 * refuse to write, standing in for a database that rejects the statement.
	 */
	private Run execute(List<String> preamble, String refuseWriteTo) throws Exception {
		List<String> script = new ArrayList<String>();
		// Where the case is about an image with no client, PATH is replaced by one BUILT without a
		// client rather than prefixed, so neither a client the host carries elsewhere nor one it
		// ships in /usr/bin can answer. The case asserts the line the entrypoint prints for an
		// absent client on top of that, so a PATH that did not manage it fails rather than being
		// inferred from what the stand-in was not handed.
		script.add(clientAbsent ? "PATH='" + systemPathWithoutAMariadbClient() + "'"
				: "PATH='" + stubs + "':$PATH");
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
		if (schemaTables != null) {
			builder.environment().put("MARIADB_STAND_IN_SCHEMA_TABLES", schemaTables);
		}
		if (databaseUnreachable) {
			builder.environment().put("MARIADB_STAND_IN_UNREACHABLE", "yes");
		}
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the wiring did not finish");
		return new Run(process.exitValue(), output);
	}

	/**
	 * The four system directories' contents as one directory of symlinks, with every entry named
	 * {@code mariadb} left out: a PATH that has no client on it whatever the host has installed.
	 *
	 * <p><b>Why it is built and not spelled.</b> {@code PATH='/usr/bin:/bin:/usr/sbin:/sbin'} is
	 * an absent client only where the host happens not to ship one, and
	 * {@code Dockerfile.backend}'s {@code mariadb-client} puts one in {@code /usr/bin} — so on a
	 * Linux box or CI image carrying that package the case that drives the absent-client branch
	 * drove the database-not-answering one instead, where a client is found and cannot connect,
	 * and passed. Nothing else about the search changes: the same four directories, in the same
	 * order, first spelling of a name winning, so each tool the run reaches for resolves through a
	 * link to the same file it resolved to before. The residue is {@code argv[0]}, which is now the
	 * link's path rather than the system directory's — a tool that reads its own name for anything
	 * would see the difference.
	 *
	 * <p>Both halves are asserted rather than assumed — that something was linked, since a PATH of
	 * nothing is one where every command is absent and not one where only this client is, and that
	 * the one name it exists to leave out is not on it.
	 */
	private Path systemPathWithoutAMariadbClient() throws IOException {
		Path withoutAClient = work.resolve("system-bin-without-a-mariadb-client");
		if (Files.isDirectory(withoutAClient)) {
			return withoutAClient;
		}
		Files.createDirectories(withoutAClient);
		int linked = 0;
		for (String directory : List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
			Path system = Paths.get(directory);
			if (!Files.isDirectory(system)) {
				continue;
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(system)) {
				for (Path entry : entries) {
					Path link = withoutAClient.resolve(entry.getFileName().toString());
					if ("mariadb".equals(entry.getFileName().toString())
							|| Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
						continue;
					}
					Files.createSymbolicLink(link, entry);
					linked++;
				}
			}
		}
		assertTrue(linked > 0, "no system directory could be read, so this is a PATH on which every command is"
				+ " absent rather than one on which only the mariadb client is");
		assertFalse(Files.exists(withoutAClient.resolve("mariadb"), LinkOption.NOFOLLOW_LINKS),
				"the one name this PATH exists to leave out is on it");
		return withoutAClient;
	}

	private List<String> issuedStatements() throws IOException {
		return Files.readAllLines(statements, StandardCharsets.UTF_8);
	}

	/** Every line the retrieval wiring printed, which is what an operator reading a start has. */
	private static List<String> wiringLines(Run run) {
		List<String> lines = new ArrayList<String>();
		for (String line : run.output.split("\n", -1)) {
			if (line.contains("[retrieval-wiring]")) {
				lines.add(line);
			}
		}
		return lines;
	}

	/** Seeds a global property the way an earlier start or an operator would have left it. */
	private void given(String property, String value) throws IOException {
		Files.write(store.resolve(property), value.getBytes(StandardCharsets.UTF_8));
	}

	/** Where a copy the wiring put out of reach ends up, so a case reads the name rather than guessing it. */
	private static Path quarantined(Path target) {
		return target.resolveSibling(target.getFileName() + ".unverified");
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
	 *
	 * <p><b>Three knobs, and each of them answered a fixed value until round 2 of the amendment's
	 * review.</b> A stand-in that always reaches the database, always reports four schema tables and
	 * can only refuse an {@code INSERT} holds every gate in this function permanently open, so no
	 * case could drive the two returns {@code configure_retrieval_gps} used to open with, nor a
	 * rejected withdrawal. {@code MARIADB_STAND_IN_UNREACHABLE} refuses every statement the way a
	 * database that is not answering does, {@code MARIADB_STAND_IN_SCHEMA_TABLES} is what the
	 * {@code information_schema} probe counts, and {@code MARIADB_STAND_IN_REFUSE} now names a
	 * property no write of any shape may reach.
	 *
	 * <p>The schema count and the store are deliberately INDEPENDENT: a database carrying
	 * {@code global_property} without one of the other three tables the probe wants answers a count
	 * below four while the row is there and an {@code UPDATE} on it lands, which is the world where
	 * the probe is wrong rather than the database empty.
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
				"if [ -n \"$MARIADB_STAND_IN_UNREACHABLE\" ]; then",
				"\techo \"mariadb stand-in: could not connect\" >&2",
				"\texit 1",
				"fi",
				// The property this statement is about: named by a WHERE clause in a read or an
				// UPDATE, and by the first VALUES item in an INSERT. One name for both shapes, so
				// the refusal knob below reaches every write rather than only the INSERT — which
				// is what left a rejected WITHDRAWAL undriveable.
				"_property=$(printf '%s' \"$_sql\" | sed -n \"s/.*property='\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"[ -n \"$_property\" ] || _property=$(printf '%s' \"$_sql\" \\",
				"\t| sed -n \"s/.*VALUES ('\\\\([^']*\\\\)'.*/\\\\1/p\")",
				// Refused for a WRITE only, so the world a case drives is a database that answers
				// the reads and rejects the statement — which is what makes "the withdrawal was
				// issued" and "the row is blank" two different questions.
				"case \"$_sql\" in",
				"\t'INSERT INTO global_property'* | 'UPDATE global_property'*)",
				"\t\tif [ -n \"$MARIADB_STAND_IN_REFUSE\" ] \\",
				"\t\t\t&& [ \"$_property\" = \"$MARIADB_STAND_IN_REFUSE\" ]; then",
				"\t\t\techo \"mariadb stand-in: refusing to write $_property\" >&2",
				"\t\t\texit 1",
				"\t\tfi ;;",
				"esac",
				"case \"$_sql\" in",
				"\t'SELECT 1'*)",
				"\t\techo 1 ;;",
				"\t*'information_schema.tables'*)",
				"\t\techo \"${MARIADB_STAND_IN_SCHEMA_TABLES:-4}\" ;;",
				// The demo seed's own sentinel read, which the whole-entrypoint case reaches. A row
				// that was never written prints nothing, the way the client prints nothing for an
				// empty result — not the empty line COALESCE's reader gets.
				"\t'SELECT property_value FROM global_property'*)",
				"\t\tif [ -f \"$MARIADB_STAND_IN_STORE/$_property\" ]; then",
				"\t\t\tcat \"$MARIADB_STAND_IN_STORE/$_property\"",
				"\t\tfi ;;",
				"\t'SELECT COALESCE(property_value'*)",
				"\t\tif [ -f \"$MARIADB_STAND_IN_STORE/$_property\" ]; then",
				"\t\t\tcat \"$MARIADB_STAND_IN_STORE/$_property\"",
				"\t\telse",
				"\t\t\techo ''",
				"\t\tfi ;;",
				"\t'INSERT INTO global_property'*)",
				"\t\t_value=$(printf '%s' \"$_sql\" | sed -n \"s/.*VALUES ('[^']*','\\\\([^']*\\\\)'.*/\\\\1/p\")",
				"\t\tcase \"$_sql\" in",
				"\t\t\t*'IF(property_value IS NULL'*)",
				"\t\t\t\tif [ ! -s \"$MARIADB_STAND_IN_STORE/$_property\" ]; then",
				"\t\t\t\t\tprintf '%s' \"$_value\" > \"$MARIADB_STAND_IN_STORE/$_property\"",
				"\t\t\t\tfi ;;",
				"\t\t\t*)",
				"\t\t\t\tprintf '%s' \"$_value\" > \"$MARIADB_STAND_IN_STORE/$_property\" ;;",
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
