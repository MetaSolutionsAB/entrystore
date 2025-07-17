package org.entrystore.rest.standalone.springboot.util;

import lombok.NoArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

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
	 * @param filename the original filename to sanitize
	 * @return sanitized filename with "_dangerous" suffix if extension is dangerous,
	 * original filename otherwise
	 */
	public static String sanitizeFilename(String filename) {

		String fileExt = FilenameUtils.getExtension(filename);
		return isDangerousExtension(fileExt)
				? filename + "_dangerous"
				: filename;
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
