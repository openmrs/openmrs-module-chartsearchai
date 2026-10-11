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
import java.util.Optional;

/**
 * What the host is doing to the local llama-server, read from the kernel's own counters: the body of
 * {@code GET /chartsearchai/hostdiagnostics}.
 *
 * <p>On a CPU-only host, answer latency is set by how much CPU llama-server actually gets, and that is
 * invisible from the REST side: the same prompt on the public demo answered in 19 s and, twenty minutes
 * later, in 337 s with a byte-identical answer (ADR Decision 174). This reports the counters that tell
 * the causes apart — CPU steal (a contended hypervisor), cgroup CFS throttling (a container quota smaller
 * than the threads llama.cpp starts), load average (local competition), swap (memory pressure) — and
 * llama-server's own CPU time, so an operator without a shell on the host can sample it before and after
 * a query and diff.
 *
 * <p>Every counter is reported raw and cumulative; the reader diffs two samples. A file that cannot be
 * read reports {@code null}, never zero — "could not read" is not "no steal". Nothing here is
 * patient-derived.
 */
public final class HostDiagnostics {

	private static final String LLAMA_SERVER = "llama-server";

	private static final List<String> CPU_FIELDS = Arrays.asList("user", "nice", "system", "idle", "iowait",
			"irq", "softirq", "steal");

	private HostDiagnostics() {
	}

	/** This host, as the endpoint reports it. */
	public static Map<String, Object> read() {
		return read(Paths.get("/"), ProcessHandle.current());
	}

	/**
	 * The host whose {@code /proc} and {@code /sys/fs/cgroup} sit under {@code root}, and the llama-server
	 * among {@code jvm}'s descendants.
	 */
	static Map<String, Object> read(Path root, ProcessHandle jvm) {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("sampledAtMillis", System.currentTimeMillis());
		body.put("jvmAvailableProcessors", Runtime.getRuntime().availableProcessors());
		body.put("loadAverage", loadAverage(root));
		body.put("cpuJiffies", cpuJiffies(root));
		body.put("memoryKb", memoryKb(root));
		body.put("cgroupCpu", cgroupCpu(root));
		body.put("llamaServer", llamaServer(root, jvm));
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
		return memory;
	}

	/**
	 * The CPU quota of the cgroup this JVM runs in and how often it was throttled, in microseconds for
	 * both cgroup versions. A {@code null} {@code quotaMicros} is no quota ({@code max} in v2, {@code -1}
	 * in v1).
	 */
	private static Map<String, Object> cgroupCpu(Path root) {
		Path v2 = root.resolve("sys/fs/cgroup");
		String max = readFile(v2.resolve("cpu.max"));
		if (max != null) {
			String[] fields = max.trim().split("\\s+");
			Map<String, Long> stat = keyValues(v2.resolve("cpu.stat"), " ");
			return cgroup("v2", "max".equals(fields[0]) ? null : parseLong(fields[0]),
					fields.length > 1 ? parseLong(fields[1]) : null, stat, "throttled_usec", 1L);
		}
		for (String controller : new String[] { "cpu", "cpu,cpuacct" }) {
			Path v1 = root.resolve("sys/fs/cgroup").resolve(controller);
			String quota = readFile(v1.resolve("cpu.cfs_quota_us"));
			if (quota == null) {
				continue;
			}
			Long quotaMicros = parseLong(quota.trim());
			String period = readFile(v1.resolve("cpu.cfs_period_us"));
			Map<String, Long> stat = keyValues(v1.resolve("cpu.stat"), " ");
			return cgroup("v1", quotaMicros == null || quotaMicros < 0 ? null : quotaMicros,
					period == null ? null : parseLong(period.trim()), stat, "throttled_time", 1000L);
		}
		return null;
	}

	private static Map<String, Object> cgroup(String version, Long quotaMicros, Long periodMicros,
			Map<String, Long> stat, String throttledKey, long throttledDivisor) {
		Map<String, Object> cgroup = new LinkedHashMap<String, Object>();
		cgroup.put("version", version);
		cgroup.put("quotaMicros", quotaMicros);
		cgroup.put("periodMicros", periodMicros);
		cgroup.put("periods", stat == null ? null : stat.get("nr_periods"));
		cgroup.put("throttledPeriods", stat == null ? null : stat.get("nr_throttled"));
		Long throttled = stat == null ? null : stat.get(throttledKey);
		cgroup.put("throttledMicros", throttled == null ? null : throttled / throttledDivisor);
		return cgroup;
	}

	/**
	 * The llama-server this JVM spawned, found among its descendants by executable name. Its
	 * {@code threadsArgument} is the {@code -t} it was launched with, {@code "auto"} when it was launched
	 * without one, and {@code null} when the platform does not expose a child's arguments.
	 */
	private static Map<String, Object> llamaServer(Path root, ProcessHandle jvm) {
		Map<String, Object> llama = new LinkedHashMap<String, Object>();
		Optional<ProcessHandle> found = jvm.descendants().filter(HostDiagnostics::isLlamaServer).findFirst();
		llama.put("running", found.isPresent());
		if (!found.isPresent()) {
			return llama;
		}
		ProcessHandle process = found.get();
		ProcessHandle.Info info = process.info();
		llama.put("pid", process.pid());
		llama.put("cpuMillis", info.totalCpuDuration().map(Duration::toMillis).orElse(null));
		llama.put("threadsArgument", info.arguments().map(HostDiagnostics::threadsArgument).orElse(null));
		Map<String, Long> status = keyValues(root.resolve("proc").resolve(String.valueOf(process.pid()))
				.resolve("status"), ":");
		llama.put("osThreads", status == null ? null : status.get("Threads"));
		return llama;
	}

	private static boolean isLlamaServer(ProcessHandle process) {
		Optional<String> command = process.info().command();
		if (!command.isPresent()) {
			return false;
		}
		Path name = Paths.get(command.get()).getFileName();
		return name != null && name.toString().startsWith(LLAMA_SERVER);
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
