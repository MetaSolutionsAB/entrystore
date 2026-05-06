package org.entrystore.rest.springboot.util;

import lombok.NoArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class FileUtil {

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
	 * Sanitizes a filename by appending a suffix if it has a dangerous extension.
	 *
	 * The extension is determined from a normalized form of the filename: URL-decoded,
	 * Unicode-whitespace-stripped, and with trailing dots, semicolons, and control
	 * characters removed. The suffix is always appended to the original (non-normalized)
	 * filename so that warning signals are preserved in logs and serving paths.
	 *
	 * @param filename the original filename to sanitize
	 * @return sanitized filename with "_dangerous" suffix if extension is dangerous,
	 * original filename otherwise
	 */
	public static String sanitizeFilename(String filename) {
		String normalized;
		try {
			normalized = URLDecoder.decode(filename, StandardCharsets.UTF_8).strip();
		} catch (IllegalArgumentException e) {
			// Truncated %-escape — treat as suspicious
			return filename + "_dangerous";
		}
		while (!normalized.isEmpty()) {
			char last = normalized.charAt(normalized.length() - 1);
			if (last == '.' || last == ';' || last < ' ' || last == '\u007F' || Character.isSpaceChar(last)) {
				normalized = normalized.substring(0, normalized.length() - 1);
			} else {
				break;
			}
		}
		String fileExt = FilenameUtils.getExtension(normalized);
		return isDangerousExtension(fileExt) ? filename + "_dangerous" : filename;
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
