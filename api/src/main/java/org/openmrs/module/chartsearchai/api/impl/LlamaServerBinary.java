/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the bundled {@code llama-server} native binary for the current
 * platform and architecture, extracting it (and any sibling shared
 * libraries listed in {@code libs.txt}) from the module's JAR resources
 * into the OpenMRS application data directory on first use. Subsequent
 * calls reuse the previously extracted binary.
 *
 * <p>Resource layout under {@code llama-server/<Platform>/<Arch>/}:
 * the binary itself ({@code llama-server} or {@code llama-server.exe})
 * plus a {@code libs.txt} manifest naming the shared libraries to
 * extract alongside it.</p>
 */
final class LlamaServerBinary {

	private LlamaServerBinary() {
	}

	private static final Logger log = LoggerFactory.getLogger(LlamaServerBinary.class);

	/**
	 * Returns the absolute path to the executable {@code llama-server}
	 * binary, extracting it from JAR resources if not already present.
	 */
	static String resolve() {
		String platform = detectPlatform();
		String arch = detectArch();

		String binaryName = platform.equals("Windows") ? "llama-server.exe" : "llama-server";
		String resourceDir = "llama-server/" + platform + "/" + arch + "/";

		File appDataDir = new File(OpenmrsUtil.getApplicationDataDirectory());
		File targetDir = new File(appDataDir, "chartsearchai/bin");
		File targetFile = new File(targetDir, binaryName);

		if (targetFile.isFile() && targetFile.canExecute()) {
			log.info("Using existing llama-server at {}", targetFile.getAbsolutePath());
			return targetFile.getAbsolutePath();
		}

		try (InputStream is = LlamaServerBinary.class.getClassLoader()
				.getResourceAsStream(resourceDir + binaryName)) {
			if (is == null) {
				throw new IllegalStateException(
						"No bundled llama-server for " + platform + "/" + arch
								+ ". Place a compatible llama-server binary at "
								+ targetFile.getAbsolutePath());
			}

			targetDir.mkdirs();
			Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			targetFile.setExecutable(true);
			log.info("Extracted bundled llama-server to {}", targetFile.getAbsolutePath());
		}
		catch (IOException e) {
			throw new IllegalStateException(
					"Failed to extract bundled llama-server: " + e.getMessage(), e);
		}

		extractSharedLibraries(resourceDir, targetDir, platform);

		return targetFile.getAbsolutePath();
	}

	private static void extractSharedLibraries(String resourceDir, File targetDir, String platform) {
		String libListResource = resourceDir + "libs.txt";
		try (InputStream libList = LlamaServerBinary.class.getClassLoader()
				.getResourceAsStream(libListResource)) {
			if (libList == null) {
				log.warn("No libs.txt found at {}; shared libraries may be missing", libListResource);
				return;
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(libList, StandardCharsets.UTF_8))) {
				String libName;
				while ((libName = reader.readLine()) != null) {
					libName = libName.trim();
					if (libName.isEmpty()) {
						continue;
					}
					try (InputStream libStream = LlamaServerBinary.class.getClassLoader()
							.getResourceAsStream(resourceDir + libName)) {
						if (libStream == null) {
							log.warn("Listed library {} not found in resources", libName);
							continue;
						}
						File libFile = new File(targetDir, libName);
						Files.copy(libStream, libFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
						if (!platform.equals("Windows")) {
							libFile.setExecutable(true);
						}
						log.debug("Extracted shared library {}", libName);
					}
				}
			}
		}
		catch (IOException e) {
			log.warn("Failed to extract shared libraries: {}", e.getMessage());
		}
	}

	static String detectPlatform() {
		String osName = System.getProperty("os.name", "");
		if (osName.contains("Windows")) {
			return "Windows";
		}
		if (osName.contains("Mac") || osName.contains("Darwin")) {
			return "Mac";
		}
		return "Linux";
	}

	static String detectArch() {
		String osArch = System.getProperty("os.arch", "").toLowerCase();
		if (osArch.contains("aarch64") || osArch.contains("arm64")) {
			return "aarch64";
		}
		if (osArch.equals("x86") || osArch.equals("i386") || osArch.equals("i686")) {
			return "x86";
		}
		return "x86_64";
	}
}
