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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilTest {

	@Test
	void sanitizeForLog_replacesCrLf() {
		assertEquals("a??b", HttpUtil.sanitizeForLog("a\r\nb"));
	}

	@Test
	void sanitizeForLog_replacesOtherControlChars() {
		// BEL (U+0007) and ESC (U+001B) are both control characters that must not appear
		// in log output (terminal-escape forgery, log injection).
		assertEquals("a?b?", HttpUtil.sanitizeForLog("ab"));
	}

	@Test
	void sanitizeForLog_preservesPlainAscii() {
		assertEquals("alice@example.com", HttpUtil.sanitizeForLog("alice@example.com"));
	}

	@Test
	void sanitizeForLog_truncatesAt128Chars() {
		String big = "x".repeat(200);
		String out = HttpUtil.sanitizeForLog(big);
		// 128 visible chars + the single-character ellipsis marker
		assertEquals(129, out.length());
		assertTrue(out.endsWith("…"));
	}

	@Test
	void sanitizeForLog_atBoundaryDoesNotTruncate() {
		String exact = "x".repeat(128);
		assertEquals(exact, HttpUtil.sanitizeForLog(exact));
	}

	@Test
	void sanitizeForLog_nullReturnsLiteral() {
		assertEquals("null", HttpUtil.sanitizeForLog(null));
	}

	@Test
	void sanitizeForLog_emptyReturnsEmpty() {
		assertEquals("", HttpUtil.sanitizeForLog(""));
	}

	@Test
	void sanitizeForLog_truncatesBeforeRegexScan() {
		// A multi-MB padded username on the credential-stuffing hot path must not force the
		// regex pass over its full length. Compare two inputs that share the first 128 chars
		// but differ wildly past that boundary — including control characters in the tail —
		// and assert the two sanitized outputs are byte-identical. If truncation happens
		// after the regex pass, the second input's control-character tail would alter the
		// output (and produce a much larger intermediate StringBuilder while it's at it).
		String prefix = "x".repeat(128);
		String plainTail = "y".repeat(200);
		String controlTail = "\r\n".repeat(50); // 200 control chars
		assertEquals(HttpUtil.sanitizeForLog(prefix + plainTail),
				HttpUtil.sanitizeForLog(prefix + controlTail));
	}
}
