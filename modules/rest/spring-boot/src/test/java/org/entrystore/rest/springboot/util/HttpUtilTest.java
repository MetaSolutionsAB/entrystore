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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpUtilTest {

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

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

	@Test
	void determineMediaType_formatTakesPrecedenceOverContentType() {
		assertEquals("text/turtle", HttpUtil.determineMediaType(
				MediaType.parseMediaType("text/turtle"), "application/json; charset=UTF-8"));
	}

	@Test
	void determineMediaType_formatWithParameters_preservedVerbatim() {
		// Pins the Javadoc contract: the format branch keeps media-type parameters, unlike the
		// content-type branch which strips them.
		assertEquals("text/turtle;charset=UTF-8", HttpUtil.determineMediaType(
				MediaType.parseMediaType("text/turtle;charset=UTF-8"), "application/json"));
	}

	@Test
	void determineMediaType_nullFormat_fallsBackToNormalizedContentType() {
		assertEquals("application/json", HttpUtil.determineMediaType(null, "application/json; charset=UTF-8"));
	}

	@Test
	void determineMediaType_nullFormatAndUnparseableContentType_returnsNull() {
		assertNull(HttpUtil.determineMediaType(null, "not a media type"));
	}

	@Test
	void determineMediaType_nullFormatAndNullContentType_returnsNull() {
		assertNull(HttpUtil.determineMediaType(null, null));
	}

	@Test
	void clearAuthenticatedSession_clearsContextAndInvalidatesSession() {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("u", "p", List.of()));
		var request = new MockHttpServletRequest();
		MockHttpSession session = (MockHttpSession) request.getSession(true);

		HttpUtil.clearAuthenticatedSession(request);

		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"SecurityContext must be cleared");
		assertTrue(session.isInvalid(), "Session must be invalidated");
	}

	@Test
	void clearAuthenticatedSession_withoutSession_clearsContextOnly() {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("u", "p", List.of()));
		var request = new MockHttpServletRequest();
		// No prior getSession(true) call — request.getSession(false) inside the method returns null.

		HttpUtil.clearAuthenticatedSession(request);

		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"SecurityContext must be cleared even when no session exists");
	}

	@Test
	void clearAuthenticatedSession_invalidateThrowsIllegalStateException_caughtAndContextStillCleared() {
		// Models a race where another request or async dispatch already invalidated the session
		// between getSession(false) returning a non-null reference and our call to invalidate().
		// Spring's MockHttpServletRequest hides this race (it returns null from getSession(false)
		// when the underlying MockHttpSession is invalid), so use Mockito to construct the
		// invariant directly: a non-null session whose invalidate() throws. Without the catch,
		// this IllegalStateException would propagate out of the filter and bypass the JSON
		// deny contract.
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("u", "p", List.of()));
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(request.getSession(false)).thenReturn(session);
		doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();

		HttpUtil.clearAuthenticatedSession(request);

		verify(session).invalidate();
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"SecurityContext must be cleared even when session.invalidate() throws IllegalStateException");
	}

	/**
	 * ENTRYSTORE-1055. Six controllers now delegate their Last-Modified and ETag headers here, including
	 * two that previously built them by hand, so the helper is the single point where a regression would
	 * silently drop validators from every one of those responses at once.
	 */
	@Test
	void updateResponseWithModificationDateAndETag_setsBothHeadersFromTheDate() {
		ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

		HttpUtil.updateResponseWithModificationDateAndETag(builder, new Date(1686594567000L));

		HttpHeaders headers = builder.build().getHeaders();
		assertEquals(1686594567000L, headers.getLastModified());
		// Strong ETag, epoch millis, quoted — finer-grained than Last-Modified, which is second-resolution.
		assertEquals("\"1686594567000\"", headers.getETag());
	}

	/**
	 * The null branch is the one RelationController gave up its own explicit guard for, so it has to omit
	 * both headers rather than throw or emit a bogus value. Reachable for any entry whose graph carries
	 * no dcterms:modified — legacy content, a restored backup, or a graph written externally.
	 */
	@Test
	void updateResponseWithModificationDateAndETag_nullDate_omitsBothHeadersInsteadOfThrowing() {
		ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

		HttpUtil.updateResponseWithModificationDateAndETag(builder, null);

		HttpHeaders headers = builder.build().getHeaders();
		assertEquals(-1, headers.getLastModified(), "absent Last-Modified reads as -1");
		assertNull(headers.getETag());
	}
}
