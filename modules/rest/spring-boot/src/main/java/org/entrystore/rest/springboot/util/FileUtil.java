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
import java.text.Normalizer;
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
	 * The extension is determined from a normalized form of the filename:
	 * <ol>
	 *     <li>URL-decoded iteratively until stable, defeating multi-encoded shapes such as
	 *     {@code %2520}; the form-decoder {@code +}&rarr;space behavior is suppressed by
	 *     escaping {@code +} before each decode pass.</li>
	 *     <li>{@link String#strip()} applied (removing Java whitespace).</li>
	 *     <li>Trailing characters iteratively removed if they are {@code .}, {@code ;},
	 *     {@code /}, {@code \}, ASCII control chars (&lt; 0x20), DEL (U+007F), C1 controls
	 *     (U+0080&ndash;U+009F), Unicode space chars ({@link Character#isSpaceChar} &mdash;
	 *     including NBSP, which {@code String.strip()} does NOT remove), or Unicode format
	 *     chars ({@link Character#FORMAT} &mdash; zero-width separators, BOM and bidi
	 *     overrides).</li>
	 *     <li>{@link Normalizer.Form#NFKC} compatibility normalization, so fullwidth Latin
	 *     spoofs (e.g. U+FF45 U+FF58 U+FF45) collapse to their ASCII equivalents before
	 *     the extension check.</li>
	 * </ol>
	 * <p>
	 * Filenames are flagged with the {@code _dangerous} suffix when any of the following
	 * holds: the resolved extension is dangerous, decoding fails (truncated/non-hex
	 * {@code %}-escape or unstable after {@value #MAX_DECODE_ITERATIONS} passes), or the
	 * normalized form contains a control character (C0, DEL, or C1) anywhere &mdash;
	 * control characters in stored filenames break {@code Content-Disposition} response
	 * headers and have been used for header smuggling.
	 * <p>
	 * The returned filename is the normalized form with the suffix appended when
	 * applicable. On the failure paths (decode error or embedded control char), the
	 * trailing-stripped form of the raw input is returned with the suffix, so that
	 * bidi/format characters in the input do not persist into the stored filename.
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
			// Malformed %-escape (truncated, non-hex, or unstable after the iteration cap)
			// — treat as suspicious. Normalize the input form too, so bidi/format chars
			// in the raw input do not survive into the stored filename.
			log.warn("Failed to URL-decode filename '{}', flagging as _dangerous: {}",
					filename, e.getMessage());
			return stripTrailingNoise(filename.strip()) + "_dangerous";
		}
		normalized = stripTrailingNoise(normalized.strip());
		if (containsControlChar(normalized)) {
			log.warn("Embedded control char in filename '{}', flagging as _dangerous", filename);
			return stripTrailingNoise(filename.strip()) + "_dangerous";
		}
		normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
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
	 * stabilizes, defeating multi-encoding bypass shapes such as {@code %2520} (which decodes
	 * to {@code %20} on the second pass and then to a literal space). Literal {@code +}
	 * characters are escaped to {@code %2B} before each pass so {@link URLDecoder}'s
	 * form-decoder semantics do not collapse them to spaces.
	 *
	 * @throws IllegalArgumentException if a {@code %}-escape is truncated, contains
	 *                                  non-hex characters, or remains in the result after
	 *                                  {@value #MAX_DECODE_ITERATIONS} decode passes (a
	 *                                  deeper-than-supported encoding nesting)
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
		if (current.contains("%")) {
			throw new IllegalArgumentException(
					"Filename did not stabilize within " + MAX_DECODE_ITERATIONS + " decode passes");
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
	 * Returns {@code true} if {@code s} contains any C0 control (U+0000&ndash;U+001F),
	 * DEL (U+007F), or C1 control (U+0080&ndash;U+009F) character. These characters are
	 * invalid in HTTP header field values per RFC 9110 §5.5 and have been used for
	 * header smuggling and filename-display spoofs.
	 */
	private static boolean containsControlChar(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
				return true;
			}
		}
		return false;
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
