/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.util;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class FileUtil {

	private static final int MAX_DECODE_ITERATIONS = 3;

	private final static Set<String> DANGEROUS_FILE_EXTENSIONS = Set.of(
			"apk",
			"app",
			"asp",
			"aspx",
			"bat",
			"bin",
			"cab",
			"cmd",
			"com",
			"command",
			"cpl",
			"csh",
			"ex",
			"exe",
			"gadget",
			"inf",
			"ins",
			"inx",
			"ipa",
			"isu",
			"js",
			"jse",
			"jsp",
			"jsx",
			"ksh",
			"lnk",
			"msc",
			"msi",
			"msp",
			"mst",
			"osx",
			"out",
			"paf",
			"pif",
			"php",
			"pl",
			"plx",
			"prg",
			"ps1",
			"rb",
			"reg",
			"rgs",
			"run",
			"scr",
			"sct",
			"shb",
			"shs",
			"u3p",
			"vb",
			"vbe",
			"vbs",
			"vbscript",
			"workflow",
			"ws",
			"wsf",
			"wsh");

	/**
	 * Sanitizes a filename by appending a {@code _dangerous} suffix when it resolves to a
	 * dangerous extension.
	 * <p>
	 * The extension is determined from a normalized form of the filename: URL-decoded iteratively
	 * (form-decoder {@code +}&rarr;space behavior is suppressed by escaping {@code +} before each
	 * decode pass), with {@link String#strip()} applied (removing Java whitespace), then with
	 * trailing characters iteratively removed if they are {@code .}, {@code ;}, {@code /},
	 * {@code \}, ASCII control chars (&lt; 0x20), DEL (U+007F), C1 controls (U+0080&ndash;U+009F),
	 * Unicode space chars ({@link Character#isSpaceChar} &mdash; including NBSP, which
	 * {@code String.strip()} does NOT remove), or Unicode format chars
	 * ({@link Character#FORMAT} &mdash; zero-width separators, BOM and bidi overrides).
	 * <p>
	 * The returned filename is the normalized form, with {@code _dangerous} appended when the
	 * resolved extension is dangerous. When decoding fails (truncated or non-hex {@code %}-escape),
	 * the raw input is returned with the suffix appended (no normalization) and a warning is
	 * logged.
	 *
	 * @param filename the original filename to sanitize, or {@code null}
	 * @return the normalized filename (with {@code _dangerous} suffix when applicable), or
	 * {@code null} if the input is {@code null}
	 */
	public static String sanitizeFilename(String filename) {
		if (filename == null) {
			return null;
		}
		String normalized;
		try {
			normalized = decodeUntilStable(filename);
		} catch (IllegalArgumentException e) {
			// Malformed %-escape (truncated or non-hex) — treat as suspicious
			log.warn("Failed to URL-decode filename '{}', flagging as _dangerous: {}",
					filename, e.getMessage());
			return filename + "_dangerous";
		}
		normalized = stripTrailingNoise(normalized.strip());
		String fileExt = FilenameUtils.getExtension(normalized);
		if (isDangerousExtension(fileExt)) {
			log.info("Dangerous extension '{}' in filename '{}', appending _dangerous suffix",
					fileExt, filename);
			return normalized + "_dangerous";
		}
		return normalized;
	}

	/**
	 * URL-decodes the input up to {@value #MAX_DECODE_ITERATIONS} times until the result
	 * stabilizes, defeating double-encoding bypass shapes such as {@code %2520} (which decodes
	 * to {@code %20} on the second pass and then to a literal space). Literal {@code +}
	 * characters are escaped to {@code %2B} before each pass so {@link URLDecoder}'s
	 * form-decoder semantics do not collapse them to spaces.
	 *
	 * @throws IllegalArgumentException if a {@code %}-escape is truncated or contains
	 *                                  non-hex characters
	 */
	private static String decodeUntilStable(String input) {
		String current = input;
		for (int i = 0; i < MAX_DECODE_ITERATIONS; i++) {
			String prev = current;
			current = URLDecoder.decode(prev.replace("+", "%2B"), StandardCharsets.UTF_8);
			if (current.equals(prev)) {
				return current;
			}
		}
		return current;
	}

	/**
	 * Removes trailing characters that should be ignored when resolving the extension
	 * (see {@link #isTrailingNoise(char)}). Single-pass index scan plus one
	 * {@link String#substring(int, int)}, so cost is O(n) rather than O(n²).
	 */
	private static String stripTrailingNoise(String s) {
		int end = s.length();
		while (end > 0 && isTrailingNoise(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(0, end);
	}

	private static boolean isTrailingNoise(char c) {
		return c == '.' || c == ';' || c == '/' || c == '\\'
				|| c < ' ' || c == '\u007F'
				|| (c >= '\u0080' && c <= '\u009F')
				|| Character.isSpaceChar(c)
				|| Character.getType(c) == Character.FORMAT;
	}

	/**
	 * Checks if a file extension is considered dangerous.
	 *
	 * @param extension the file extension to check (without dot)
	 * @return true if the extension is dangerous, false otherwise (including null and empty value)
	 */
	public static boolean isDangerousExtension(String extension) {
		return StringUtils.isNotEmpty(extension) &&
				DANGEROUS_FILE_EXTENSIONS.contains(extension.toLowerCase());
	}
}
