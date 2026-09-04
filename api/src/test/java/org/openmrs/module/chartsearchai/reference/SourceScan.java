/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * One production source file, read as TEXT with its comments and string literals blanked, for the
 * guards that pin a rule nothing behavioural can see.
 *
 * <p>Extracted at the THIRD class to need the walk, which is the threshold {@link ModuleSourceRoot}'s
 * own javadoc records for itself. {@code ChipSubjectOneResolutionTest} and
 * {@code OrderPartnerNameSourceWritePathTest} carry the other two copies and are deliberately NOT
 * migrated here: they are outside the change that extracted this, so the drift they can still make is
 * theirs rather than this class's. What that leaves is real and worth naming — the two copies have
 * already diverged in their block-comment tail handling — but migrating them is a change of its own,
 * and the second of them keeps its file locator apart from {@code ModuleSourceRoot} for a reason its
 * own javadoc states.
 *
 * <p><b>Every lookup fails LOUDLY rather than answering "not found".</b> A needle that matches nothing
 * leaves a guard forbidding nothing, and one that matches twice cannot say which body it delimited —
 * so both are assertion failures here rather than a best guess returned to the caller. The read
 * asserts the file exists and is not truncated for the same reason: a guard that reads nothing
 * satisfies every "is not called here" assertion by containing nothing.
 */
final class SourceScan {

	private final String relativePath;

	private final String source;

	/** @param relativePath the file under {@code api/}, e.g.
	 *                     {@code src/main/java/.../DrugSafetyValidator.java} */
	SourceScan(String relativePath) throws IOException {
		this.relativePath = relativePath;
		Path file = ModuleSourceRoot.apiRoot().resolve(relativePath);
		assertTrue(Files.exists(file), "no " + relativePath + " under " + ModuleSourceRoot.apiRoot()
				+ "; a guard that reads nothing satisfies every \"is not called here\" assertion by "
				+ "containing nothing");
		char[] text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toCharArray();
		assertTrue(text.length > 10000,
			"only " + text.length + " chars were read from " + file + "; a truncated read forbids nothing");
		this.source = blanked(text);
	}

	/** @return the ONE offset of {@code needle}; absent and ambiguous are both hard failures. Private
	 *          because a caller wanting a body should ask {@link #body}, which fails the same way. */
	private int uniqueOffset(String needle) {
		int at = source.indexOf(needle);
		assertTrue(at >= 0, "\"" + needle + "\" was not found in " + relativePath + ", so the guard "
				+ "reading it has nothing to compare against and everything below it would be vacuous. "
				+ "Update the needle along with the code it names.");
		assertTrue(source.indexOf(needle, at + 1) < 0, "\"" + needle + "\" matched more than once in "
				+ relativePath + ", so the guard reading it cannot say which body it delimited. Narrow it.");
		return at;
	}

	/**
	 * @return the one occurrence of {@code declaration} TOGETHER with the brace-delimited body it
	 *         opens — the region a guard wants when it forbids a NAME, since a method's declaration
	 *         mentions its own name and sits outside the braces.
	 */
	Region declarationAndBody(String declaration) {
		return new Region(uniqueOffset(declaration), body(declaration).end());
	}

	/** @return the brace-delimited body FOLLOWING the one occurrence of {@code declaration}. */
	Region body(String declaration) {
		int from = uniqueOffset(declaration);
		int open = source.indexOf('{', from);
		assertTrue(open >= 0, "no opening brace after \"" + declaration + "\" in " + relativePath);
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return new Region(open, i);
				}
			}
		}
		throw new AssertionError("braces never balanced while reading \"" + declaration + "\" in "
				+ relativePath + "; the scan cannot say where anything lives, so it must fail rather "
				+ "than guess");
	}

	/** @return every offset of the literal {@code needle}. */
	List<Integer> literalOffsets(String needle) {
		List<Integer> found = new ArrayList<Integer>();
		int at = source.indexOf(needle);
		while (at >= 0) {
			found.add(at);
			at = source.indexOf(needle, at + 1);
		}
		return found;
	}

	/** @return every match of {@code pattern}. */
	List<Integer> matches(Pattern pattern) {
		List<Integer> found = new ArrayList<Integer>();
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			found.add(matcher.start());
		}
		return found;
	}

	/** @return the trimmed line {@code at} sits on. */
	String statementAt(int at) {
		int from = source.lastIndexOf('\n', at) + 1;
		int to = source.indexOf('\n', at);
		return source.substring(from, to < 0 ? source.length() : to).trim();
	}

	int lineOf(int at) {
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	List<Integer> linesOf(List<Integer> offsets) {
		List<Integer> lines = new ArrayList<Integer>();
		for (int at : offsets) {
			lines.add(lineOf(at));
		}
		return lines;
	}

	/**
	 * Comments and string literals blanked to spaces, so offsets and line numbers still line up with
	 * the file on disk — blanked and not deleted because the failure messages report line numbers, and
	 * because a call written inside a javadoc is prose and not a call.
	 */
	private static String blanked(char[] text) {
		int i = 0;
		while (i < text.length) {
			char c = text[i];
			if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
				while (i < text.length && text[i] != '\n') {
					text[i++] = ' ';
				}
			}
			else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
				text[i++] = ' ';
				text[i++] = ' ';
				while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
					if (text[i] != '\n') {
						text[i] = ' ';
					}
					i++;
				}
				while (i < text.length && text[i] != '/') {
					text[i++] = ' ';
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else if (c == '"' || c == '\'') {
				char quote = c;
				text[i++] = ' ';
				while (i < text.length && text[i] != quote) {
					if (text[i] == '\\' && i + 1 < text.length) {
						text[i++] = ' ';
					}
					text[i++] = ' ';
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else {
				i++;
			}
		}
		return new String(text);
	}

	/** A brace-delimited span of the source. */
	static final class Region {

		private final int start;

		private final int end;

		Region(int start, int end) {
			this.start = start;
			this.end = end;
		}

		int start() {
			return start;
		}

		int end() {
			return end;
		}

		boolean contains(int at) {
			return at >= start && at <= end;
		}
	}
}
