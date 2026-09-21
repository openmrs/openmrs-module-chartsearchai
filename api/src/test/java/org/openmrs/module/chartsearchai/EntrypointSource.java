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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads {@code backend-init.sh} for the suites that drive or check it — {@link
 * EntrypointRetrievalWiringTest} and {@link EntrypointVolumeVerificationTest}, which paste its own
 * functions into a harness and run them, and {@link ModelDownloadPinningGuardTest}, which reads its
 * source.
 *
 * <p><b>One reader, for the reason {@link ModelManifest} is one.</b> Both of the readings here have
 * a silent failure mode: a harness that pasted half a function would fail in a way that looks like a
 * finding about the entrypoint, and a guard that read a continuation line as a command of its own
 * would judge the wrong text. Copies of either walk that disagreed would each still produce
 * plausible shell.
 */
public final class EntrypointSource {

	private EntrypointSource() {
	}

	/** The container entrypoint, repo-relative — spelled once for its readers. */
	public static final String ENTRYPOINT = "backend-init.sh";

	private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	public static Path path() {
		return ModuleSourceRoot.repoRoot().resolve(ENTRYPOINT);
	}

	public static List<String> lines() throws IOException {
		return Files.readAllLines(path(), StandardCharsets.UTF_8);
	}

	/**
	 * A shell function's definition, verbatim, read out of {@code lines} by name. Both forms the
	 * entrypoint uses are read — a one-liner, and a multi-line definition closing at column 0 — and
	 * anything else throws, because a harness that quietly pasted half a function would fail in a
	 * way that looks like a finding about the code.
	 */
	public static String functionText(List<String> lines, String name) {
		if (!FUNCTION_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("'" + name + "' is not a shell function name");
		}
		Pattern opener = Pattern.compile("^" + Pattern.quote(name) + "\\(\\)\\s*\\{.*");
		for (int i = 0; i < lines.size(); i++) {
			if (!opener.matcher(lines.get(i)).matches()) {
				continue;
			}
			if (lines.get(i).endsWith("}")) {
				return lines.get(i);
			}
			for (int j = i + 1; j < lines.size(); j++) {
				if (lines.get(j).equals("}")) {
					return String.join("\n", lines.subList(i, j + 1));
				}
			}
			throw new IllegalStateException(name + "() in " + ENTRYPOINT + " never closes at column 0");
		}
		throw new IllegalStateException(ENTRYPOINT + " defines no " + name + "(), so this harness cannot run the"
				+ " code it is named after");
	}

	/**
	 * The logical command starting at {@code from}: continuation lines joined, {@code \\} dropped,
	 * and a trailing comment removed.
	 *
	 * <p><b>The comment is not part of the command, and reading it as part of one defeated a
	 * guard.</b> {@code … &  # why} still backgrounds the command, while {@code endsWith("&")} is
	 * false of that text — so a reader asking what the shell DOES with a command has to be handed
	 * the text the shell reads. ADR Decision 106 records the shape.
	 */
	public static String logicalCommand(List<String> lines, int from) {
		StringBuilder command = new StringBuilder(lines.get(from).trim());
		int i = from;
		while (command.length() > 0 && command.charAt(command.length() - 1) == '\\' && i + 1 < lines.size()) {
			command.setLength(command.length() - 1);
			command.append(' ').append(lines.get(++i).trim());
		}
		return withoutComment(command.toString()).trim();
	}

	/**
	 * {@code command} up to the {@code #} that opens a comment in it, or all of it where none does.
	 *
	 * <p>A {@code #} opens one only where the shell would start a word with it: outside quotes,
	 * unescaped, and at the start of the command or after whitespace. That is what leaves
	 * {@code ${VAR#prefix}} and a {@code #} inside an argument alone.
	 */
	public static String withoutComment(String command) {
		String syntax = shellSyntaxOf(command);
		for (int i = 0; i < syntax.length(); i++) {
			if (syntax.charAt(i) == '#' && (i == 0 || Character.isWhitespace(command.charAt(i - 1)))) {
				return command.substring(0, i);
			}
		}
		return command;
	}

	/**
	 * {@code command} with everything a quote or a backslash makes literal blanked out, index for
	 * index. So a {@code &}, a {@code |} or a {@code #} surviving here is the shell's own syntax,
	 * and one inside an argument — {@code "e5-base-v2 ONNX embedder (~440MB)"}, a diagnostic line
	 * carrying {@code \"Not a} — is not.
	 *
	 * <p>Backslash escapes are read outside single quotes only, which is where the shell reads
	 * them; inside single quotes a backslash is literal. A line the caller has already stripped of
	 * its continuation {@code \\} carries none of its own.
	 */
	public static String shellSyntaxOf(String command) {
		StringBuilder syntax = new StringBuilder();
		char open = 0;
		for (int i = 0; i < command.length(); i++) {
			char c = command.charAt(i);
			if (c == '\\' && open != '\'' && i + 1 < command.length()) {
				syntax.append("  ");
				i++;
			} else if (open == 0 && (c == '\'' || c == '"')) {
				open = c;
				syntax.append(' ');
			} else if (open != 0 && c == open) {
				open = 0;
				syntax.append(' ');
			} else {
				syntax.append(open == 0 ? c : ' ');
			}
		}
		return syntax.toString();
	}

	/**
	 * Whether the line at {@code index} is a continuation of the command above it, so a caller
	 * walking every line reads each logical command once, from the line that OPENS it.
	 */
	public static boolean continuesTheLineAbove(List<String> lines, int index) {
		return index > 0 && lines.get(index - 1).trim().endsWith("\\");
	}
}
