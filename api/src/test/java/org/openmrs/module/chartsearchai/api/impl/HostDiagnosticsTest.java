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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code read(root, llamaServer)} that production calls with {@code /}. A host is not reproducible in a test
 * any other way: the counters this endpoint exists to report (steal, CFS throttling) are only non-zero
 * on a contended host.
 */
public class HostDiagnosticsTest {

	/** Every key the endpoint documents, in the order it serializes them. A new key is APPENDED. */
	private static final List<String> DOCUMENTED_FIELDS = Arrays.asList("sampledAtMillis",
			"jvmAvailableProcessors", "loadAverage", "cpuJiffies", "memoryKb", "cgroupCpu", "llamaServer",
			"jvmCpuMillis");

	/** {@code /proc/self/mountinfo} of a cgroup-v2 host or container: the unified hierarchy at /sys/fs/cgroup. */
	private static final String V2_MOUNTINFO = "22 1 259:2 / / rw,relatime - ext4 /dev/root rw\n"
			+ "35 22 0:30 / /sys/fs/cgroup rw,nosuid,nodev,noexec,relatime shared:9 - cgroup2 cgroup2 rw,nsdelegate\n";

	/** {@code /proc/self/mountinfo} of a cgroup-v1 host: the cpu controller co-mounted with cpuacct. */
	private static final String V1_MOUNTINFO = "22 1 259:2 / / rw,relatime - ext4 /dev/root rw\n"
			+ "30 25 0:26 / /sys/fs/cgroup/memory rw,nosuid shared:12 - cgroup cgroup rw,memory\n"
			+ "31 25 0:27 / /sys/fs/cgroup/cpu,cpuacct rw,nosuid shared:13 - cgroup cgroup rw,cpu,cpuacct\n";

	@TempDir
	Path root;

	@Test
	public void read_shouldReportEveryDocumentedFieldInOrder() {
		Map<String, Object> body = HostDiagnostics.read(root, null);

		assertEquals(DOCUMENTED_FIELDS, new ArrayList<Object>(body.keySet()));
		assertEquals(Runtime.getRuntime().availableProcessors(), body.get("jvmAvailableProcessors"),
				"the JVM's count is the one that honours a container CPU quota, which nproc does not");
	}

	@Test
	public void read_shouldReportNullRatherThanZeroForEveryHostFileItCannotRead() {
		Map<String, Object> body = HostDiagnostics.read(root, null);

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

		Map<String, Object> body = HostDiagnostics.read(root, null);

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
		inCgroup("0::/\n", V2_MOUNTINFO);
		write("sys/fs/cgroup/cpu.max", "200000 100000\n");
		write("sys/fs/cgroup/cpu.stat", "usage_usec 9876543\nuser_usec 8000000\nsystem_usec 1876543\n"
				+ "nr_periods 5400\nnr_throttled 4100\nthrottled_usec 812345678\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

		assertEquals("v2", cgroup.get("version"));
		assertEquals("/", cgroup.get("path"), "a container's private cgroup namespace puts it at the root");
		assertEquals(200000L, cgroup.get("quotaMicros"));
		assertEquals(100000L, cgroup.get("periodMicros"));
		assertEquals(5400L, cgroup.get("periods"));
		assertEquals(4100L, cgroup.get("throttledPeriods"));
		assertEquals(812345678L, cgroup.get("throttledMicros"));
	}

	@Test
	public void read_shouldReportAnUnlimitedCgroupV2QuotaAsNull() throws IOException {
		inCgroup("0::/\n", V2_MOUNTINFO);
		write("sys/fs/cgroup/cpu.max", "max 100000\n");
		write("sys/fs/cgroup/cpu.stat", "usage_usec 1\nnr_periods 0\nnr_throttled 0\nthrottled_usec 0\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

		assertTrue(cgroup.containsKey("quotaMicros"));
		assertNull(cgroup.get("quotaMicros"), "'max' is no quota, which is not a quota of zero");
		assertEquals(100000L, cgroup.get("periodMicros"));
	}

	@Test
	public void read_shouldReportACgroupV1QuotaWithThrottledTimeInMicroseconds() throws IOException {
		inCgroup("5:memory:/\n4:cpu,cpuacct:/\n", V1_MOUNTINFO);
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "150000\n");
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n");
		write("sys/fs/cgroup/cpu,cpuacct/cpu.stat", "nr_periods 300\nnr_throttled 120\nthrottled_time 4500000000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

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
		inCgroup("4:cpu,cpuacct:/\n", V1_MOUNTINFO);
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "-1\n");
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

		assertNull(cgroup.get("quotaMicros"), "-1 is no quota");
	}

	/**
	 * Tomcat in a systemd service on a cgroup-v1 host: the quota is on the JVM's own cgroup, and the top of
	 * the mount is the root cgroup, which has none. Reading the top reported no quota and no throttling for
	 * a JVM that had both.
	 */
	@Test
	public void read_shouldReportTheCgroupThisJvmRunsInRatherThanTheTopOfTheMount() throws IOException {
		inCgroup("4:cpu,cpuacct:/system.slice/tomcat9.service\n", V1_MOUNTINFO);
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "-1\n");
		write("sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n");
		write("sys/fs/cgroup/cpu,cpuacct/cpu.stat", "nr_periods 0\nnr_throttled 0\nthrottled_time 0\n");
		write("sys/fs/cgroup/cpu,cpuacct/system.slice/tomcat9.service/cpu.cfs_quota_us", "150000\n");
		write("sys/fs/cgroup/cpu,cpuacct/system.slice/tomcat9.service/cpu.cfs_period_us", "100000\n");
		write("sys/fs/cgroup/cpu,cpuacct/system.slice/tomcat9.service/cpu.stat",
				"nr_periods 300\nnr_throttled 120\nthrottled_time 4500000000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

		assertEquals("/system.slice/tomcat9.service", cgroup.get("path"));
		assertEquals(150000L, cgroup.get("quotaMicros"));
		assertEquals(120L, cgroup.get("throttledPeriods"));
	}

	/**
	 * A container sharing the host's cgroup namespace: {@code /proc/self/cgroup} gives the host-side path,
	 * and the mount's root is that same path, so the JVM's cgroup is the mount point itself.
	 */
	@Test
	public void read_shouldResolveTheJvmsCgroupAgainstTheRootOfItsMount() throws IOException {
		inCgroup("0::/system.slice/docker-4f2a.scope\n",
				"35 22 0:30 /system.slice/docker-4f2a.scope /sys/fs/cgroup ro,nosuid - cgroup2 cgroup rw\n");
		write("sys/fs/cgroup/cpu.max", "300000 100000\n");
		write("sys/fs/cgroup/system.slice/docker-4f2a.scope/cpu.max", "max 100000\n");

		Map<?, ?> cgroup = (Map<?, ?>) HostDiagnostics.read(root, null).get("cgroupCpu");

		assertEquals("/system.slice/docker-4f2a.scope", cgroup.get("path"));
		assertEquals(300000L, cgroup.get("quotaMicros"));
	}

	@Test
	public void read_shouldReportNoCgroupRatherThanTheTopOfTheMountWhenItCannotLocateTheJvms() throws IOException {
		write("sys/fs/cgroup/cpu.max", "200000 100000\n");
		write("sys/fs/cgroup/cpu.stat", "nr_periods 5400\nnr_throttled 4100\nthrottled_usec 812345678\n");

		assertNull(HostDiagnostics.read(root, null).get("cgroupCpu"),
				"with no /proc/self/cgroup there is no saying which cgroup's quota applies to this JVM");
	}

	@Test
	public void read_shouldReportThisJvmsOwnCpuTime() {
		Object cpuMillis = HostDiagnostics.read(root, null).get("jvmCpuMillis");

		assertTrue(cpuMillis instanceof Long && (Long) cpuMillis > 0,
				"a JVM that has run tests has spent CPU; got " + cpuMillis);
	}

	@Test
	public void read_shouldReportNoLlamaServerWhenTheEngineHoldsNone() {
		Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, null).get("llamaServer");

		assertEquals(Boolean.FALSE, llama.get("running"));
		assertFalse(llama.containsKey("pid"));
	}

	@Test
	public void read_shouldReportNoLlamaServerWhenTheProcessTheEngineHoldsHasExited() throws Exception {
		Process child = startChild("-t", "3");
		child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);

		Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, child.toHandle()).get("llamaServer");

		assertEquals(Boolean.FALSE, llama.get("running"), "a crashed server is not a running one");
		assertFalse(llama.containsKey("pid"));
	}

	/**
	 * A real child process standing in for the engine's llama-server. It is {@code yes}, which stays alive
	 * once its unread pipe fills. GNU {@code yes} rejects {@code -t} as an option and exits 1, so the
	 * arguments follow {@code --}, which ends option parsing for it; BSD {@code yes} prints it as its word.
	 * {@code --port 8091} sits before {@code -t 3} because on macOS (Darwin 24, JDK 21, measured
	 * 2026-10-11) {@code ProcessHandle.Info.arguments()} returns this child's first two arguments joined
	 * into one, which would hide a {@code -t} placed first.
	 */
	@Test
	public void read_shouldReportTheLlamaServerTheEngineLaunchedAndReadItsThreadArgument() throws Exception {
		Process child = startChild("--", "--port", "8091", "-t", "3");
		try {
			assertFalse(child.waitFor(500, TimeUnit.MILLISECONDS),
					"the stand-in exited (" + (child.isAlive() ? "-" : child.exitValue()) + ") instead of running");
			write("proc/" + child.pid() + "/status", "Name:\tllama-server\nState:\tS (sleeping)\nThreads:\t7\n");

			Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, child.toHandle()).get("llamaServer");

			assertEquals(Boolean.TRUE, llama.get("running"));
			assertEquals(child.pid(), llama.get("pid"));
			assertEquals(7L, llama.get("osThreads"));
			assertEquals("3", llama.get("threadsArgument"));
			if (System.getProperty("os.name").startsWith("Linux")) {
				// macOS (Darwin 24, measured 2026-10-11) reports no CPU time for this child at all.
				assertNotNull(llama.get("cpuMillis"), "the child's CPU time is what says whether it got the CPU");
			}
		}
		finally {
			child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
	}

	@Test
	public void read_shouldReportAutoForALlamaServerLaunchedWithoutAThreadCount() throws Exception {
		Process child = startChild("--", "--port", "8091");
		try {
			assertFalse(child.waitFor(500, TimeUnit.MILLISECONDS), "the stand-in exited instead of running");

			Map<?, ?> llama = (Map<?, ?>) HostDiagnostics.read(root, child.toHandle()).get("llamaServer");

			assertEquals("auto", llama.get("threadsArgument"));
		}
		finally {
			child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
	}

	private static Process startChild(String... arguments) throws IOException {
		List<String> command = new ArrayList<String>();
		command.add("/usr/bin/yes");
		command.addAll(Arrays.asList(arguments));
		return new ProcessBuilder(command).start();
	}

	private void inCgroup(String procSelfCgroup, String procSelfMountinfo) throws IOException {
		write("proc/self/cgroup", procSelfCgroup);
		write("proc/self/mountinfo", procSelfMountinfo);
	}

	private void write(String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}
}
