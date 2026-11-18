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
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.util.OpenmrsUtil;

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
 * <p>It also answers what a container that stopped with nobody watching leaves behind (ADR Decision 660):
 * whether the host's OOM killer has run since boot ({@code oomKills}, the HOST's count, so a kill in
 * another container is in it), how long the host stalled on memory ({@code memoryPressure}), what this
 * container holds and has peaked at ({@code cgroupMemory}), and how full the disk under the data directory
 * is ({@code dataDisk}).
 *
 * <p>Two kinds of reading, reported raw. The {@code cpuJiffies} ticks, the cgroup's {@code periods},
 * {@code throttledPeriods} and {@code throttledMicros}, {@code llamaServer.cpuMillis},
 * {@code jvmCpuMillis}, {@code oomKills}, the {@code memoryPressure} totals and {@code cgroupMemory.oomKills}
 * are cumulative counters, which the reader diffs between two samples.
 * {@code loadAverage} is the kernel's decaying average, and {@code memoryKb}, the cgroup's quota and
 * period, {@code cgroupMemory}'s sizes and {@code dataDisk} are gauges; each is read as it stands at the
 * sample — {@code cgroupMemory.peakKb} being the gauge's own high-water mark.
 * A file that cannot be read reports {@code null}, never zero — "could not read" is not "no steal".
 * Nothing here is patient-derived.
 */
public final class HostDiagnostics {

	private static final int MAX_PROCESSES = 10;

	private static final List<String> CPU_FIELDS =Arrays.asList("user", "nice", "system", "idle", "iowait",
			"irq", "softirq", "steal");

	private HostDiagnostics() {
	}

	/**
	 * This host, as the endpoint reports it. {@code llamaServer} is the process the engine launched
	 * ({@code LocalLlmEngine.serverHandle()}), {@code null} when it holds none.
	 */
	public static Map<String, Object> read(ProcessHandle llamaServer) {
		return read(Paths.get("/"), llamaServer, Paths.get(OpenmrsUtil.getApplicationDataDirectory()));
	}

	/**
	 * The host whose {@code /proc} and {@code /sys/fs/cgroup} sit under {@code root}; {@code /proc/self} there
	 * stands for this JVM, and {@code root} itself for the data directory.
	 */
	static Map<String, Object> read(Path root, ProcessHandle llamaServer) {
		return read(root, llamaServer, root);
	}

	/** As {@link #read(Path, ProcessHandle)}, with the data directory whose disk {@code dataDisk} reports. */
	static Map<String, Object> read(Path root, ProcessHandle llamaServer, Path dataDirectory) {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("sampledAtMillis", System.currentTimeMillis());
		body.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
		body.put("loadAverage", loadAverage(root));
		body.put("cpuJiffies", cpuJiffies(root));
		body.put("memoryKb", memoryKb(root));
		body.put("cgroupCpu", cgroupCpu(root));
		body.put("llamaServer", llamaServer(root, llamaServer));
		body.put("jvmCpuMillis", cpuMillis(ProcessHandle.current()));
		body.put("processes", processes(root));
		body.put("oomKills", oomKills(root));
		body.put("memoryPressure", memoryPressure(root));
		body.put("cgroupMemory", cgroupMemory(root));
		body.put("dataDisk", dataDisk(dataDirectory));
		return body;
	}

	/**
	 * The {@code oom_kill} counter of {@code /proc/vmstat}: how many processes the kernel's OOM killer has
	 * killed on this host since it booted. {@code /proc/vmstat} is not namespaced, so inside a container
	 * this is still the HOST's count — a kill in another container (the database's) included, and still
	 * there after this container is recreated. {@code null} when the file cannot be read or the kernel
	 * (older than 4.13) keeps no such counter.
	 */
	private static Long oomKills(Path root) {
		Map<String, Long> vmstat = keyValues(root.resolve("proc/vmstat"), " ");
		return vmstat == null ? null : vmstat.get("oom_kill");
	}

	/**
	 * The host's memory-pressure stall totals since boot from {@code /proc/pressure/memory} (PSI), in
	 * microseconds: {@code someTotalMicros} while at least one task waited on memory, {@code fullTotalMicros}
	 * while every non-idle task did. Cumulative counters, diffed between samples. {@code null} when the
	 * kernel was built or booted without PSI, or the file cannot be read.
	 */
	private static Map<String, Long> memoryPressure(Path root) {
		String text = readFile(root.resolve("proc/pressure/memory"));
		if (text == null) {
			return null;
		}
		Long some = null;
		Long full = null;
		for (String line : text.split("\n")) {
			String[] fields = line.trim().split("\\s+");
			for (String field : fields) {
				if (field.startsWith("total=")) {
					Long total = parseLong(field.substring("total=".length()));
					if ("some".equals(fields[0])) {
						some = total;
					}
					else if ("full".equals(fields[0])) {
						full = total;
					}
				}
			}
		}
		if (some == null && full == null) {
			return null;
		}
		Map<String, Long> pressure = new LinkedHashMap<String, Long>();
		pressure.put("someTotalMicros", some);
		pressure.put("fullTotalMicros", full);
		return pressure;
	}

	/**
	 * The memory of the cgroup-v2 cgroup this JVM runs in — in a container, the container, llama-server
	 * included: {@code currentKb} held now, {@code peakKb} the most held since the cgroup was created
	 * ({@code memory.peak}, a 5.19 kernel; {@code null} before), {@code maxKb} its limit ({@code null} for
	 * {@code max}, no limit on THIS cgroup), and {@code oomKills}, how many of its processes an OOM killer
	 * took, the host's included ({@code memory.events}). {@code null} for the whole section when the JVM's
	 * v2 cgroup cannot be located or carries no memory controller files; a cgroup-v1 host is not read.
	 */
	private static Map<String, Object> cgroupMemory(Path root) {
		OwnCgroup v2 = ownCgroup(root, null);
		String current = v2 == null ? null : readFile(v2.dir.resolve("memory.current"));
		if (current == null) {
			return null;
		}
		String peak = readFile(v2.dir.resolve("memory.peak"));
		String max = readFile(v2.dir.resolve("memory.max"));
		Map<String, Long> events = keyValues(v2.dir.resolve("memory.events"), " ");
		Map<String, Object> memory = new LinkedHashMap<String, Object>();
		memory.put("path", v2.path);
		memory.put("currentKb", kilobytes(current));
		memory.put("peakKb", peak == null ? null : kilobytes(peak));
		memory.put("maxKb", max == null || "max".equals(max.trim()) ? null : kilobytes(max));
		memory.put("oomKills", events == null ? null : events.get("oom_kill"));
		return memory;
	}

	private static Long kilobytes(String bytes) {
		Long parsed = parseLong(bytes.trim());
		return parsed == null ? null : parsed / 1024;
	}

	/**
	 * How full the filesystem holding {@code dataDirectory} is, in kilobytes. In a container the OpenMRS
	 * data directory is a volume, so this is the disk the host keeps its container data on, the database's
	 * volume normally among it. {@code null} when the directory does not exist or its store cannot be read.
	 */
	private static Map<String, Long> dataDisk(Path dataDirectory) {
		try {
			FileStore store = Files.getFileStore(dataDirectory);
			Map<String, Long> disk = new LinkedHashMap<String, Long>();
			disk.put("totalKb", store.getTotalSpace() / 1024);
			disk.put("usableKb", store.getUsableSpace() / 1024);
			return disk;
		}
		catch (IOException e) {
			return null;
		}
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
		memory.put("anonPages", meminfo.get("AnonPages"));
		memory.put("shmem", meminfo.get("Shmem"));
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

	/**
	 * Every process visible under {@code /proc}: their {@code count}, the sum of their {@code VmRSS} as
	 * {@code rssKbTotal}, and the {@value #MAX_PROCESSES} holding the most as {@code largest}, each by pid,
	 * {@code Name} and {@code VmRSS} — never its command line, which can carry paths and secrets. In a
	 * container only the container's own processes are visible. {@code rssKbTotal} counts a page shared by
	 * two processes twice and leaves out kernel memory and page tables, so it is not an exact share of
	 * what {@code memoryKb} shows in use; a gap of gigabytes is still memory this container does not hold. A process the kernel
	 * reports no {@code VmRSS} for (a kernel thread) holds no memory of its own and is left out.
	 * {@code null} when {@code /proc} cannot be listed.
	 */
	private static Map<String, Object> processes(Path root) {
		List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.resolve("proc"))) {
			for (Path entry : entries) {
				String pid = entry.getFileName().toString();
				if (!pid.matches("\\d+")) {
					continue;
				}
				String status = readFile(entry.resolve("status"));
				if (status == null) {
					continue;
				}
				String name = null;
				Long rss = null;
				for (String line : status.split("\n")) {
					if (line.startsWith("Name:")) {
						name = line.substring("Name:".length()).trim();
					}
					else if (line.startsWith("VmRSS:")) {
						rss = parseLong(line.substring("VmRSS:".length()).trim().split("\\s+")[0]);
					}
				}
				if (rss == null) {
					continue;
				}
				Map<String, Object> process = new LinkedHashMap<String, Object>();
				process.put("pid", Long.valueOf(pid));
				process.put("name", name);
				process.put("rssKb", rss);
				processes.add(process);
			}
		}
		catch (IOException e) {
			return null;
		}
		long total = 0;
		for (Map<String, Object> process : processes) {
			total += (Long) process.get("rssKb");
		}
		processes.sort((a, b) -> Long.compare((Long) b.get("rssKb"), (Long) a.get("rssKb")));
		Map<String, Object> visible = new LinkedHashMap<String, Object>();
		visible.put("count", (long) processes.size());
		visible.put("rssKbTotal", total);
		visible.put("largest", processes.size() > MAX_PROCESSES
				? new ArrayList<Map<String, Object>>(processes.subList(0, MAX_PROCESSES)) : processes);
		return visible;
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
