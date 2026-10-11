/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire shape of {@link HostDiagnostics#read}, the body of {@code GET /chartsearchai/hostdiagnostics}.
 *
 * <p>The files under the fixture root are in the formats the Linux kernel writes them in — what
 * {@code /proc} and {@code /sys/fs/cgroup} hold on a real host — read by the same
 * {@code read(root, jvm)} that production calls with {@code /}. A host is not reproducible in a test
 * any other way: the counters this endpoint exists to report (steal, CFS throttling) are only non-zero
 * on a contended host.
 */
public class HostDiagnosticsTest {

	/** Every key the endpoint documents, in the order it serializes them. A new key is APPENDED. */
	private static final List<String> DOCUMENTED_FIELDS = Arrays.asList("sampledAtMillis",
			"jvmAvailableProcessors", "loadAverage", "cpuJiffies", "memoryKb", "cgroupCpu", "llamaServer");

	@TempDir
	Path root;

	@Test
	public void read_shouldReportEveryDocumentedFieldInOrder() {
		Map<String, Object> body = HostDiagnostics.read(root, ProcessHandle.current());

		assertEquals(DOCUMENTED_FIELDS, new ArrayList<Object>(body.keySet()));
		assertEquals(Runtime.getRuntime().availableProcessors(), body.get("jvmAvailableProcessors"),
				"the JVM's count is the one that honours a container CPU quota, which nproc does not");
	}

	@Test
	public void read_shouldReportNullRatherThanZeroForEveryHostFileItCannotRead() {
		Map<String, Object> body = HostDiagnostics.read(root, ProcessHandle.current());

		assertNull(body.get("loadAverage"), "an unreadable file is not a host with no load");
		assertNull(body.get("cpuJiffies"));
		assertNull(body.get("memoryKb"));
		assertNull(body.get("cgroupCpu"));
	}

	@Test
	public void read_shouldReportLoadStealAndMemoryFromProc() throws IOException {
		write("proc/loadavg", "3.92 4.10 3.87 6/812 4123\n");
		write("proc/stat", "cpu  2255 34 2290 22625563 6290 127 456 1873 0 0\n"
				+ "cpu0 1132 34 1441 11311718 3675 127 438 937 0 0\n"
				+ "intr 114930548 113199788 3 0 5 263 0 4 [...]\n");
		write("proc/meminfo", "MemTotal:       15340872 kB\nMemFree:          412000 kB\n"
				+ "MemAvailable:    2048000 kB\nBuffers:           10000 kB\nSwapTotal:       4194300 kB\n"
				+ "SwapFree:        1000000 kB\n");

		Map<String, Object> body = HostDiagnostics.read(root, ProcessHandle.current());

		assertEquals(Arrays.asList(3.92, 4.10, 3.87), body.get("loadAverage"));
		Map<?, ?> cpu = (Map<?, ?>) body.get("cpuJiffies");
		assertEquals(Arrays.asList("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal"),
				new ArrayList<Object>(cpu.keySet()));
		assertEquals(1873L, cpu.get("steal"), "steal is the eighth field of the aggregate cpu line");
		assertEquals(22625563L, cpu.get("idle"));
		Map<?, ?> memory = (Map<?, ?>) body.get("memoryKb");
		assertEquals(15340872L, memory.get("memTotal"));
		assertEquals(2048000L, memory.get("memAvailable"));
		assertEquals(4194300L, memory.get("swapTotal"));
		assertEquals(1000000L, memory.get("swapFree"));
	}

	@Test
	public void read_shouldReportACgroupV2QuotaAndItsThrottling() throws IOException {
		write("sys/fs/cgroup/cpu.max", "200000 100000\n");
		write("sys/fs/cgroup/cpu.stat", "usage_usec 9876543\nuser_usec 8000000\nsystem_usec 1876543\n"
				+ "nr_periods 5400\nnr_throttled 4100\nthrottled_usec 812345678\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("cgroupCpu");

		assertEquals("v2", cgroup.get("version"));
		assertEquals(200000L, cgroup.get("quotaMicros"));
		assertEquals(100000L, cgroup.get("periodMicros"));
		assertEquals(5400L, cgroup.get("periods"));
		assertEquals(4100L, cgroup.get("throttledPeriods"));
		assertEquals(812345678L, cgroup.get("throttledMicros"));
	}

	@Test
	public void read_shouldReportAnUnlimitedCgroupV2QuotaAsNull() throws IOException {
		write("sys/fs/cgroup/cpu.max", "max 100000\n");
		write("sys/fs/cgroup/cpu.stat", "usage_usec 1\nnr_periods 0\nnr_throttled 0\nthrottled_usec 0\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("cgroupCpu");

		assertTrue(cgroup.containsKey("quotaMicros"));
		assertNull(cgroup.get("quotaMicros"), "'max' is no quota, which is not a quota of zero");
		assertEquals(100000L, cgroup.get("periodMicros"));
	}

	@Test
	public void read_shouldReportACgroupV1QuotaWithThrottledTimeInMicroseconds() throws IOException {
		write("sys/fs/cgroup/cpu/cpu.cfs_quota_us", "150000\n");
		write("sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n");
		write("sys/fs/cgroup/cpu/cpu.stat", "nr_periods 300\nnr_throttled 120\nthrottled_time 4500000000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("cgroupCpu");

		assertEquals("v1", cgroup.get("version"));
		assertEquals(150000L, cgroup.get("quotaMicros"));
		assertEquals(100000L, cgroup.get("periodMicros"));
		assertEquals(300L, cgroup.get("periods"));
		assertEquals(120L, cgroup.get("throttledPeriods"));
		assertEquals(4500000L, cgroup.get("throttledMicros"),
				"v1 reports throttled_time in nanoseconds; the field is microseconds in both versions");
	}

	@Test
	public void read_shouldReportAnUnlimitedCgroupV1QuotaAsNull() throws IOException {
		write("sys/fs/cgroup/cpu/cpu.cfs_quota_us", "-1\n");
		write("sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("cgroupCpu");

		assertNull(cgroup.get("quotaMicros"), "-1 is no quota");
	}

	@Test
	public void read_shouldReportNoLlamaServerWhenTheJvmHasNotSpawnedOne() {
		Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("llamaServer");

		assertEquals(Boolean.FALSE, llama.get("running"));
		assertFalse(llama.containsKey("pid"));
	}

	/**
	 * A real child of this JVM whose executable is named {@code llama-server}, found the way production
	 * finds the module's own: among the JVM's descendants, by executable name. The child is a copy of
	 * {@code yes}, which accepts any arguments and blocks once its unread pipe fills. macOS kills a copy
	 * of one of its own platform binaries run from outside the system paths, so there the case is
	 * skipped rather than passed; Linux, which CI builds on, runs it.
	 */
	@Test
	public void read_shouldFindTheLlamaServerThisJvmSpawnedAndReadItsThreadArgument() throws Exception {
		Path binary = root.resolve("bin/llama-server");
		Files.createDirectories(binary.getParent());
		Files.copy(new File("/usr/bin/yes").toPath(), binary, StandardCopyOption.COPY_ATTRIBUTES);
		Process child = new ProcessBuilder(binary.toString(), "-t", "3").start();
		try {
			assumeFalse(child.waitFor(500, TimeUnit.MILLISECONDS),
					"this platform does not run a copied yes (exit " + (child.isAlive() ? "-" : child.exitValue())
							+ "), so there is no llama-server-named child to find");
			write("proc/" + child.pid() + "/status", "Name:\tllama-server\nState:\tS (sleeping)\nThreads:\t7\n");

			Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, ProcessHandle.current()).get("llamaServer");

			assertEquals(Boolean.TRUE, llama.get("running"));
			assertEquals(child.pid(), llama.get("pid"));
			assertNotNull(llama.get("cpuMillis"), "the child's CPU time is what says whether it got the CPU");
			assertEquals(7L, llama.get("osThreads"));
			if (child.info().arguments().isPresent()) {
				assertEquals("3", llama.get("threadsArgument"));
			}
		}
		finally {
			child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
	}

	private void write(String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}
}
