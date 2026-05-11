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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilTest {

	@Nested
	class SanitizeFilename {

		@Test
		void appendsSuffixForDangerousExtension() {
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe"));
		}

		@Test
		void appendsSuffixForUppercaseDangerousExtension() {
			// isDangerousExtension is case-insensitive; the normalized filename keeps original case
			assertEquals("datei.EXE_dangerous", FileUtil.sanitizeFilename("datei.EXE"));
		}

		@Test
		void appendsSuffixForUrlEncodedSpace() {
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%20"));
		}

		@Test
		void appendsSuffixForTrailingAsciiSpace() {
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe "));
		}

		@Test
		void appendsSuffixForTrailingNonBreakingSpace() {
			// NBSP (U+00A0) is a Zs space char but Character.isWhitespace returns false for it,
			// so String.strip() does NOT remove it. It is stripped by the loop's
			// Character.isSpaceChar(last) branch in FileUtil.sanitizeFilename.
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe "));
		}

		@Test
		void appendsSuffixForTrailingDot() {
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe."));
		}

		@Test
		void doesNotFlagSemicolonFollowedBySafeExtension() {
			// "datei.exe;.txt" — getExtension returns "txt" (safe), so no suffix.
			// The semicolon+extension pattern is a known remaining limitation.
			assertEquals("datei.exe;.txt", FileUtil.sanitizeFilename("datei.exe;.txt"));
		}

		@Test
		void appendsSuffixForUrlEncodedNullByte() {
			// %00 decodes to U+0000 (NUL); stripped by the loop's `last < 0x20` branch
			// (String.strip does not remove NUL).
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%00"));
		}

		@Test
		void doesNotModifySafeExtension() {
			assertEquals("datei.txt", FileUtil.sanitizeFilename("datei.txt"));
		}

		@Test
		void normalizesSafeExtensionWithUrlEncodedJunk() {
			// .png is not dangerous; trailing %20 decodes to space and gets stripped during normalization
			assertEquals("datei.png", FileUtil.sanitizeFilename("datei.png%20"));
		}

		@Test
		void doesNotModifyFilenameWithoutExtension() {
			assertEquals("datei", FileUtil.sanitizeFilename("datei"));
		}

		@Test
		void appendsSuffixForTruncatedPercentEscape() {
			// Malformed %-sequence → treat as suspicious; the raw input is returned verbatim
			// with the suffix appended (no normalization on the decode-failure path).
			assertEquals("datei.exe%2_dangerous", FileUtil.sanitizeFilename("datei.exe%2"));
		}

		@Test
		void appendsSuffixForTrailingPercent() {
			// Trailing bare '%' is also a truncated %-escape (decode failure path)
			assertEquals("datei.exe%_dangerous", FileUtil.sanitizeFilename("datei.exe%"));
		}

		@Test
		void appendsSuffixForMalformedPercentEscape() {
			// Non-hex %-escape; URLDecoder throws IllegalArgumentException → decode-failure path
			assertEquals("datei.exe%ZZ_dangerous", FileUtil.sanitizeFilename("datei.exe%ZZ"));
		}

		@Test
		void appendsSuffixForTrailingC1ControlChar() {
			// C1 controls (U+0080–U+009F) are not isWhitespace and not <0x20; they are stripped
			// by the explicit C1 range branch in isTrailingNoise.
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe"));
		}

		@Test
		void appendsSuffixForChainedTrailingJunk() {
			// Multiple URL-encoded spaces stripped in sequence by the trailing-noise loop
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%20%20%20"));
		}

		@Test
		void appendsSuffixForMixedTrailingJunk() {
			// Trailing dot + tab + 2 NBSPs — each removed by a different branch of isTrailingNoise
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe.\t  "));
		}

		@Test
		void appendsSuffixForUrlEncodedDot() {
			// %2E decodes to '.'; trailing dot is stripped, leaving exe as the resolved extension
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%2E"));
		}

		@Test
		void appendsSuffixForUrlEncodedSemicolon() {
			// %3B decodes to ';'; trailing semicolon is stripped
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%3B"));
		}

		@Test
		void appendsSuffixForUrlEncodedTab() {
			// %09 decodes to '\t' (U+0009 < U+0020); stripped by the ASCII-control branch
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%09"));
		}

		@Test
		void appendsSuffixForUrlEncodedForwardSlash() {
			// %2F decodes to '/'; trailing slash is stripped — without this, getExtension('/')
			// would return "" and bypass the dangerous-extension check.
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%2F"));
		}

		@Test
		void appendsSuffixForUrlEncodedBackslash() {
			// %5C decodes to '\\'; trailing backslash stripped (Windows path-separator vector)
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%5C"));
		}

		@Test
		void appendsSuffixForTrailingZeroWidthSpace() {
			// ZWSP (U+200B) is a Cf/FORMAT-category character; stripped by the FORMAT branch.
			// Many filesystems/browsers ignore zero-width chars when resolving filenames.
			assertEquals("evil.exe_dangerous", FileUtil.sanitizeFilename("evil.exe​"));
		}

		@Test
		void appendsSuffixForUrlEncodedZeroWidthSpace() {
			// %E2%80%8B is the UTF-8 encoding of ZWSP (U+200B)
			assertEquals("evil.exe_dangerous", FileUtil.sanitizeFilename("evil.exe%E2%80%8B"));
		}

		@Test
		void appendsSuffixForTrailingRtlOverride() {
			// RTL OVERRIDE (U+202E) is a Cf/FORMAT-category bidi character; stripped by the
			// FORMAT branch. Used in spoofs that visually rearrange filename characters.
			assertEquals("evil.exe_dangerous", FileUtil.sanitizeFilename("evil.exe‮"));
		}

		@Test
		void appendsSuffixForDoubleEncodedSpace() {
			// %2520 decodes to %20 on the first pass and to a literal space on the second.
			// decodeUntilStable iterates until the result is stable.
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe%2520"));
		}

		@Test
		void preservesPlusSignInSafeFilename() {
			// URLDecoder is a form-decoder and would normally fold '+' to space; sanitizeFilename
			// suppresses that by escaping '+' to %2B before each decode pass.
			assertEquals("a+b.txt", FileUtil.sanitizeFilename("a+b.txt"));
		}

		@Test
		void doesNotFlagMultiDotSafe() {
			// Last segment after the final dot is "bak" — safe — even though "exe" appears earlier
			assertEquals("archive.exe.bak", FileUtil.sanitizeFilename("archive.exe.bak"));
		}

		@Test
		void appendsSuffixForMultiDotDangerousAfterStrip() {
			// After stripping the trailing NBSP, last segment is "exe" — dangerous
			assertEquals("archive.bak.exe_dangerous", FileUtil.sanitizeFilename("archive.bak.exe "));
		}

		@Test
		void returnsEmptyForEmptyString() {
			assertEquals("", FileUtil.sanitizeFilename(""));
		}

		@Test
		void returnsEmptyForWhitespaceOnly() {
			assertEquals("", FileUtil.sanitizeFilename("   "));
		}

		@Test
		void returnsEmptyForNbspOnly() {
			assertEquals("", FileUtil.sanitizeFilename(" "));
		}

		@Test
		void returnsNullForNullInput() {
			assertNull(FileUtil.sanitizeFilename(null));
		}

		@Test
		void appendsSuffixForMultipleTrailingDots() {
			// Loops the trailing-noise scan across all three dots — protects against an
			// `if`→`while` mutation in stripTrailingNoise that the single-dot test wouldn't catch.
			assertEquals("datei.exe_dangerous", FileUtil.sanitizeFilename("datei.exe..."));
		}

		@Test
		void appendsSuffixForTrailingBom() {
			// BOM/ZWNBSP (U+FEFF) is a Cf/FORMAT-category character distinct from ZWSP;
			// stripped by the FORMAT branch of isTrailingNoise.
			assertEquals("evil.exe_dangerous", FileUtil.sanitizeFilename("evil.exe﻿"));
		}

		@Test
		void appendsSuffixForDecodeFailureOnSafeExtension() {
			// Decode-failure path appends _dangerous unconditionally, even when the resolved
			// extension would have been safe — a future re-ordering of the fall-through that
			// drops the suffix on the safe branch would silently regress this contract.
			// The original input (after strip + trailing-noise) is preserved so encoded
			// hostile forms remain visible in the stored name.
			assertEquals("datei.txt%2_dangerous", FileUtil.sanitizeFilename("datei.txt%2"));
		}

		@Test
		void appendsSuffixForQuadrupleEncodedSpace() {
			// %25252520 needs 4 decode passes to reach a literal space; the MAX_DECODE_ITERATIONS
			// cap is 3, so the loop exits with a residual %-escape ("datei.exe%20") and
			// decodeUntilStable fails closed. Decode-failure path preserves the raw input
			// (after strip + trailing-noise) and appends _dangerous.
			assertEquals("datei.exe%25252520_dangerous", FileUtil.sanitizeFilename("datei.exe%25252520"));
		}

		@Test
		void appendsSuffixForDoubleExtensionSpoof() {
			// "image.png.exe%20" — multi-dot with a dangerous segment AND encoded trailing
			// junk; pins decode + trailing-strip + dangerous-extension all-in-one.
			assertEquals("image.png.exe_dangerous", FileUtil.sanitizeFilename("image.png.exe%20"));
		}

		@Test
		void appendsSuffixForEmbeddedControlChar() {
			// %00 in the middle of the name decodes to U+0000, which survives the trailing-only
			// strip and would leak into Content-Disposition response headers (control chars
			// are invalid in HTTP header values per RFC 9110 §5.5). The embedded-control
			// check flags this; the decode-failure-style return preserves the raw encoded
			// form so the suffix is visible in logs and stored names.
			assertEquals("safe%00name.txt_dangerous", FileUtil.sanitizeFilename("safe%00name.txt"));
		}

		@Test
		void appendsSuffixForFullwidthExe() {
			// Fullwidth Latin (U+FF45 U+FF58 U+FF45 = 'ｅｘｅ') passes the dangerous-extension
			// check without NFKC; some filesystems / archive extractors normalize on save
			// and the file then materializes as report.exe. NFKC folds these to ASCII before
			// the extension check.
			assertEquals("report.exe_dangerous", FileUtil.sanitizeFilename("report.ｅｘｅ"));
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
