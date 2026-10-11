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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the host is doing to the local llama-server, read from the kernel's own counters: the body of
 * {@code GET /chartsearchai/hostdiagnostics}.
 *
 * <p>On a CPU-only host, answer latency is set by how much CPU llama-server actually gets, and that is
 * invisible from the REST side: the same prompt on the public demo answered in 19 s and, twenty minutes
 * later, in 337 s with a byte-identical answer (ADR Decision 174). This reports the counters that tell
 * the causes apart — CPU steal (a contended hypervisor), cgroup CFS throttling (a container quota smaller
 * than the threads llama.cpp starts), load average (local competition), swap (memory pressure) — and the
 * CPU time of llama-server and of this JVM, so an operator without a shell on the host can sample it
 * before and after a query and diff.
 *
 * <p>Two kinds of reading, reported raw. The {@code cpuJiffies} ticks, the cgroup's {@code periods},
 * {@code throttledPeriods} and {@code throttledMicros}, {@code llamaServer.cpuMillis} and
 * {@code jvmCpuMillis} are cumulative counters, which the reader diffs between two samples.
 * {@code loadAverage} is the kernel's decaying average, and {@code memoryKb} and the cgroup's quota and
 * period are gauges; each is read as it stands at the sample.
 * A file that cannot be read reports {@code null}, never zero — "could not read" is not "no steal".
 * Nothing here is patient-derived.
 */
public final class HostDiagnostics {

	private static final List<String> CPU_FIELDS = Arrays.asList("user", "nice", "system", "idle", "iowait",
			"irq", "softirq", "steal");

	private HostDiagnostics() {
	}

	/**
	 * This host, as the endpoint reports it. {@code llamaServer} is the process the engine launched
	 * ({@code LocalLlmEngine.serverHandle()}), {@code null} when it holds none.
	 */
	public static Map<String, Object> read(ProcessHandle llamaServer) {
		return read(Paths.get("/"), llamaServer);
	}

	/**
	 * The host whose {@code /proc} and {@code /sys/fs/cgroup} sit under {@code root}; {@code /proc/self} there
	 * stands for this JVM.
	 */
	static Map<String, Object> read(Path root, ProcessHandle llamaServer) {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("sampledAtMillis", System.currentTimeMillis());
		body.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
		body.put("loadAverage", loadAverage(root));
		body.put("cpuJiffies", cpuJiffies(root));
		body.put("memoryKb", memoryKb(root));
		body.put("cgroupCpu", cgroupCpu(root));
		body.put("llamaServer", llamaServer(root, llamaServer));
		body.put("jvmCpuMillis", cpuMillis(ProcessHandle.current()));
		return body;
	}

	private static List<Double> loadAverage(Path root) {
		String text = readFile(root.resolve("proc/loadavg"));
		if (text == null) {
			return null;
		}
		String[] fields = text.trim().split("\\s+");
		if (fields.length < 3) {
			return null;
		}
		try {
			return Arrays.asList(Double.valueOf(fields[0]), Double.valueOf(fields[1]), Double.valueOf(fields[2]));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/** The aggregate {@code cpu} line of {@code /proc/stat}, in USER_HZ ticks. */
	private static Map<String, Long> cpuJiffies(Path root) {
		String text = readFile(root.resolve("proc/stat"));
		if (text == null) {
			return null;
		}
		for (String line : text.split("\n")) {
			String[] fields = line.trim().split("\\s+");
			if (!"cpu".equals(fields[0])) {
				continue;
			}
			if (fields.length < CPU_FIELDS.size() + 1) {
				return null;
			}
			Map<String, Long> jiffies = new LinkedHashMap<String, Long>();
			for (int i = 0; i < CPU_FIELDS.size(); i++) {
				Long value = parseLong(fields[i + 1]);
				if (value == null) {
					return null;
				}
				jiffies.put(CPU_FIELDS.get(i), value);
			}
			return jiffies;
		}
		return null;
	}

	private static Map<String, Long> memoryKb(Path root) {
		Map<String, Long> meminfo = keyValues(root.resolve("proc/meminfo"), ":");
		if (meminfo == null) {
			return null;
		}
		Map<String, Long> memory = new LinkedHashMap<String, Long>();
		memory.put("memTotal", meminfo.get("MemTotal"));
		memory.put("memAvailable", meminfo.get("MemAvailable"));
		memory.put("swapTotal", meminfo.get("SwapTotal"));
		memory.put("swapFree", meminfo.get("SwapFree"));
		memory.put("cached", meminfo.get("Cached"));
		memory.put("mlocked", meminfo.get("Mlocked"));
		return memory;
	}

	/**
	 * The CPU quota of the cgroup this JVM runs in and how often it was throttled, in microseconds for
	 * both cgroup versions, with that cgroup's {@code path} as {@code /proc/self/cgroup} names it. A
	 * {@code null} {@code quotaMicros} is no quota on THIS cgroup ({@code max} in v2, {@code -1} in v1): a
	 * quota set on an ancestor — a systemd slice above the service, say — is not read, and neither is the
	 * throttling it causes. {@code null} for the whole section when this JVM's cgroup cannot be located or
	 * carries no CPU controller files, which is never read as the top of {@code /sys/fs/cgroup} instead.
	 */
	private static Map<String, Object> cgroupCpu(Path root) {
		OwnCgroup v2 = ownCgroup(root, null);
		String max = v2 == null ? null : readFile(v2.dir.resolve("cpu.max"));
		if (max != null) {
			String[] fields = max.trim().split("\\s+");
			Map<String, Long> stat = keyValues(v2.dir.resolve("cpu.stat"), " ");
			return cgroup("v2", v2.path, "max".equals(fields[0]) ? null : parseLong(fields[0]),
					fields.length > 1 ? parseLong(fields[1]) : null, stat, "throttled_usec", 1L);
		}
		OwnCgroup v1 = ownCgroup(root, "cpu");
		String quota = v1 == null ? null : readFile(v1.dir.resolve("cpu.cfs_quota_us"));
		if (quota != null) {
			Long quotaMicros = parseLong(quota.trim());
			String period = readFile(v1.dir.resolve("cpu.cfs_period_us"));
			Map<String, Long> stat = keyValues(v1.dir.resolve("cpu.stat"), " ");
			return cgroup("v1", v1.path, quotaMicros == null || quotaMicros < 0 ? null : quotaMicros,
					period == null ? null : parseLong(period.trim()), stat, "throttled_time", 1000L);
		}
		return null;
	}

	/** A cgroup this JVM belongs to: its path in its hierarchy, and the directory that path is under the mount. */
	private static final class OwnCgroup {

		private final String path;

		private final Path dir;

		private OwnCgroup(String path, Path dir) {
			this.path = path;
			this.dir = dir;
		}
	}

	/**
	 * This JVM's cgroup in the v2 hierarchy ({@code controller} null) or in the v1 hierarchy carrying
	 * {@code controller}: the path {@code /proc/self/cgroup} gives, made relative to the root of the mount
	 * {@code /proc/self/mountinfo} lists for that hierarchy, under that mount's mount point. A private
	 * cgroup namespace (a container's) makes both the path and the mount root {@code /}, so it resolves to
	 * the mount point itself. {@code null} when either file is unreadable, names no such hierarchy, or
	 * gives a path outside the mount's root. A mount point containing a space, which mountinfo escapes as
	 * {@code \040}, resolves to no file, so the section reports {@code null}.
	 */
	private static OwnCgroup ownCgroup(Path root, String controller) {
		String membership = readFile(root.resolve("proc/self/cgroup"));
		String mounts = readFile(root.resolve("proc/self/mountinfo"));
		if (membership == null || mounts == null) {
			return null;
		}
		String path = null;
		for (String line : membership.split("\n")) {
			String[] fields = line.trim().split(":", 3);
			if (fields.length < 3) {
				continue;
			}
			boolean matches = controller == null ? "0".equals(fields[0]) && fields[1].isEmpty()
					: Arrays.asList(fields[1].split(",")).contains(controller);
			if (matches) {
				path = fields[2];
				break;
			}
		}
		if (path == null) {
			return null;
		}
		for (String line : mounts.split("\n")) {
			String[] halves = line.split(" - ", 2);
			if (halves.length < 2) {
				continue;
			}
			String[] mount = halves[0].trim().split(" ");
			String[] source = halves[1].trim().split(" ");
			if (mount.length < 5 || source.length < 3) {
				continue;
			}
			boolean matches = controller == null ? "cgroup2".equals(source[0])
					: "cgroup".equals(source[0]) && Arrays.asList(source[2].split(",")).contains(controller);
			if (!matches) {
				continue;
			}
			String relative = relativeTo(mount[3], path);
			if (relative == null) {
				return null;
			}
			Path mountPoint = root.resolve(withoutLeadingSlash(mount[4]));
			return new OwnCgroup(path, mountPoint.resolve(withoutLeadingSlash(relative)));
		}
		return null;
	}

	/** {@code path} below the mount root {@code mountRoot}, or {@code null} when it is not below it. */
	private static String relativeTo(String mountRoot, String path) {
		if ("/".equals(mountRoot)) {
			return path;
		}
		if (path.equals(mountRoot)) {
			return "";
		}
		return path.startsWith(mountRoot + "/") ? path.substring(mountRoot.length()) : null;
	}

	private static String withoutLeadingSlash(String path) {
		return path.startsWith("/") ? path.substring(1) : path;
	}

	private static Map<String, Object> cgroup(String version, String path, Long quotaMicros, Long periodMicros,
			Map<String, Long> stat, String throttledKey, long throttledDivisor) {
		Map<String, Object> cgroup = new LinkedHashMap<String, Object>();
		cgroup.put("version", version);
		cgroup.put("path", path);
		cgroup.put("quotaMicros", quotaMicros);
		cgroup.put("periodMicros", periodMicros);
		cgroup.put("periods", stat == null ? null : stat.get("nr_periods"));
		cgroup.put("throttledPeriods", stat == null ? null : stat.get("nr_throttled"));
		Long throttled = stat == null ? null : stat.get(throttledKey);
		cgroup.put("throttledMicros", throttled == null ? null : throttled / throttledDivisor);
		return cgroup;
	}

	/**
	 * The llama-server the engine launched, taken from the engine rather than matched by name, so a
	 * child the engine abandoned mid-reap is never reported in its place. {@code running} is whether that
	 * process is alive; the rest is reported only when it is. Its {@code threadsArgument} is the {@code -t}
	 * it was launched with, {@code "auto"} when it was launched without one, and {@code null} when the
	 * platform does not expose a child's arguments. Its {@code threadsEnvironment} is the
	 * {@code LLAMA_ARG_THREADS} in the child's environment, which it inherits from this JVM and which
	 * llama-server applies when no {@code -t} overrides it: {@code "unset"} when the environment does not
	 * carry it, and {@code null} when that environment cannot be read. Nothing else is taken from that
	 * environment, which also carries the server's key.
	 */
	private static Map<String, Object> llamaServer(Path root, ProcessHandle process) {
		Map<String, Object> llama = new LinkedHashMap<String, Object>();
		boolean running = process != null && process.isAlive();
		llama.put("running", running);
		if (!running) {
			return llama;
		}
		ProcessHandle.Info info = process.info();
		llama.put("pid", process.pid());
		llama.put("cpuMillis", cpuMillis(process));
		llama.put("threadsArgument", info.arguments().map(HostDiagnostics::threadsArgument).orElse(null));
		llama.put("threadsEnvironment", threadsEnvironment(root, process));
		Map<String, Long> status = keyValues(root.resolve("proc").resolve(String.valueOf(process.pid()))
				.resolve("status"), ":");
		llama.put("osThreads", status == null ? null : status.get("Threads"));
		llama.put("rssKb", status == null ? null : status.get("VmRSS"));
		llama.put("lockedKb", status == null ? null : status.get("VmLck"));
		llama.put("majorFaults", majorFaults(root, process));
		return llama;
	}

	/**
	 * {@code majflt} from {@code /proc/<pid>/stat}: pages the process had to read back from disk, a
	 * counter. The command name is parenthesised and may itself hold spaces or {@code )}, so the fields are
	 * counted from the LAST {@code )}, after which {@code majflt} is the tenth.
	 */
	private static Long majorFaults(Path root, ProcessHandle process) {
		String stat = readFile(root.resolve("proc").resolve(String.valueOf(process.pid())).resolve("stat"));
		if (stat == null || stat.lastIndexOf(')') < 0) {
			return null;
		}
		String[] fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+");
		return fields.length > 9 ? parseLong(fields[9]) : null;
	}

	/**
	 * The process's own CPU time, user plus system. A child's is not included: measured on Linux
	 * (Temurin 17, 2026-10-11), a JVM whose child spent at least 2.1 s of CPU grew by 160 ms. {@code null}
	 * where the platform does not report it — macOS, for a child of this JVM.
	 */
	private static Long cpuMillis(ProcessHandle process) {
		return process.info().totalCpuDuration().map(Duration::toMillis).orElse(null);
	}

	private static String threadsArgument(String[] arguments) {
		List<String> args = new ArrayList<String>(Arrays.asList(arguments));
		for (int i = 0; i < args.size() - 1; i++) {
			if ("-t".equals(args.get(i)) || "--threads".equals(args.get(i))) {
				return args.get(i + 1);
			}
		}
		return "auto";
	}

	/** {@code /proc/<pid>/environ} is NUL-separated {@code NAME=value} entries. */
	private static String threadsEnvironment(Path root, ProcessHandle process) {
		String environ = readFile(root.resolve("proc").resolve(String.valueOf(process.pid())).resolve("environ"));
		if (environ == null) {
			return null;
		}
		for (String entry : environ.split("\0")) {
			if (entry.startsWith("LLAMA_ARG_THREADS=")) {
				return entry.substring("LLAMA_ARG_THREADS=".length());
			}
		}
		return "unset";
	}

	/** {@code key<separator> value [unit]} lines as numbers; a line whose value is not a number is skipped. */
	private static Map<String, Long> keyValues(Path file, String separator) {
		String text = readFile(file);
		if (text == null) {
			return null;
		}
		Map<String, Long> values = new LinkedHashMap<String, Long>();
		for (String line : text.split("\n")) {
			int at = line.indexOf(separator);
			if (at <= 0) {
				continue;
			}
			String[] value = line.substring(at + separator.length()).trim().split("\\s+");
			Long parsed = parseLong(value[0]);
			if (parsed != null) {
				values.put(line.substring(0, at).trim(), parsed);
			}
		}
		return values;
	}

	private static Long parseLong(String text) {
		try {
			return Long.valueOf(text);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String readFile(Path file) {
		try {
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			return null;
		}
	}
}
