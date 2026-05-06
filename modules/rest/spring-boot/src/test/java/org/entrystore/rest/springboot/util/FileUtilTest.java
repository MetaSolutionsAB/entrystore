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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilTest {

	@Nested
	class SanitizeFilename {

		@Test
		void appendsSuffixForDangerousExtension() {
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe"));
		}

		@Test
		void appendsSuffixForUrlEncodedSpace() {
			assertEquals("datei.exe%20_dangerous", FileUtil.sanitizeFilename("datei.exe%20"));
		}

		@Test
		void appendsSuffixForTrailingAsciiSpace() {
			assertEquals("datei.exe _dangerous", FileUtil.sanitizeFilename("datei.exe "));
		}

		@Test
		void appendsSuffixForTrailingNonBreakingSpace() {
			// NBSP (U+00A0) is not stripped by String.trim() but is stripped by String.strip()
			assertEquals("datei.exe _dangerous", FileUtil.sanitizeFilename("datei.exe "));
		}

		@Test
		void appendsSuffixForTrailingDot() {
			assertEquals("datei.exe._dangerous", FileUtil.sanitizeFilename("datei.exe."));
		}

		@Test
		void doesNotFlagSemicolonFollowedBySafeExtension() {
			// "datei.exe;.txt" — getExtension returns "txt" (safe), so no suffix.
			// The semicolon+extension pattern is a known remaining limitation.
			assertEquals("datei.exe;.txt", FileUtil.sanitizeFilename("datei.exe;.txt"));
		}

		@Test
		void appendsSuffixForUrlEncodedNullByte() {
			// %00 decodes to null char (U+0000); stripped as a control char
			assertEquals("datei.exe%00_dangerous", FileUtil.sanitizeFilename("datei.exe%00"));
		}

		@Test
		void doesNotModifySafeExtension() {
			assertEquals("datei.txt", FileUtil.sanitizeFilename("datei.txt"));
		}

		@Test
		void doesNotModifySafeExtensionWithUrlEncodedJunk() {
			// .png is not dangerous even with trailing %20
			assertEquals("datei.png%20", FileUtil.sanitizeFilename("datei.png%20"));
		}

		@Test
		void doesNotModifyFilenameWithoutExtension() {
			assertEquals("datei", FileUtil.sanitizeFilename("datei"));
		}

		@Test
		void appendsSuffixForTruncatedPercentEscape() {
			// Malformed %-sequence → treat as suspicious
			assertEquals("datei.exe%2_dangerous", FileUtil.sanitizeFilename("datei.exe%2"));
		}
	}

	@Nested
	class IsDangerousExtension {

		@Test
		void returnsTrueForExe() {
			assertTrue(FileUtil.isDangerousExtension("exe"));
		}

		@Test
		void returnsTrueForPhp() {
			assertTrue(FileUtil.isDangerousExtension("php"));
		}

		@Test
		void returnsTrueForJsp() {
			assertTrue(FileUtil.isDangerousExtension("jsp"));
		}

		@Test
		void returnsFalseForTxt() {
			assertFalse(FileUtil.isDangerousExtension("txt"));
		}

		@Test
		void returnsFalseForPdf() {
			assertFalse(FileUtil.isDangerousExtension("pdf"));
		}

		@Test
		void returnsFalseForEmptyString() {
			assertFalse(FileUtil.isDangerousExtension(""));
		}

		@Test
		void returnsFalseForNull() {
			assertFalse(FileUtil.isDangerousExtension(null));
		}

		@Test
		void isCaseInsensitive() {
			assertTrue(FileUtil.isDangerousExtension("EXE"));
			assertTrue(FileUtil.isDangerousExtension("Exe"));
			assertTrue(FileUtil.isDangerousExtension("PHP"));
		}
	}
}
