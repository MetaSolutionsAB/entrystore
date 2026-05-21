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

package org.entrystore.rest.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppExceptionHandlerTest {

	private final AppExceptionHandler handler = new AppExceptionHandler();

	@Test
	void handleRejectedExecution_returns503WithRetryMessage() {
		// Pins the bounded-executor → 503 contract: AbortPolicy on the mvc.async ThreadPoolTaskExecutor
		// throws RejectedExecutionException when the queue is full, and this handler must map it to a
		// 503 with a retry-friendly message. A regression that drops or relabels this @ExceptionHandler
		// would otherwise surface as 500 (caught by the generic Exception handler) and the client would
		// have no signal to back off.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/sparql");

		ResponseEntity<ErrorResponse> response = handler.handleRejectedExecution(
				new RejectedExecutionException("queue is full"), req);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(503, body.status());
		assertEquals("/sparql", body.path());
		assertEquals("Server temporarily overloaded; retry later", body.error());
	}

	@Test
	void handleAccessDeniedException_anonymousCaller_returns404ToPreventEnumeration() {
		// Pins the CWE-204 fix: an anonymous caller hitting a private entry must receive 404 with
		// `body.error()` equal to the bare reason phrase, indistinguishable from a missing entry.
		// The AuthorizationException is built with realistic principal/entry URIs so `ex.getMessage()`
		// genuinely carries those substrings; the assertions below pin that `body.error()` stays
		// exactly "Not Found" and never echoes them, which would fail if a future regression wires
		// `ex.getMessage()` into that field.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");
		Authentication anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		User user = Mockito.mock(User.class);
		Mockito.when(user.getURI()).thenReturn(URI.create("http://example.org/_principals/resource/alice"));
		Entry entry = Mockito.mock(Entry.class);
		Mockito.when(entry.getEntryURI()).thenReturn(URI.create("http://example.org/1/entry/42"));
		AuthorizationException ex = new AuthorizationException(user, entry, AccessProperty.ReadMetadata);

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, anonymous);

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(404, body.status());
		// Body field MUST be exactly the reason phrase — not just any non-null string. Below assertions
		// pin that none of the realistic internal substrings from ex.getMessage() leak.
		assertEquals("Not Found", body.error());
		assertFalse(body.error().contains("_principals"));
		assertFalse(body.error().contains("ReadMetadata"));
		assertFalse(body.error().contains("alice"));
	}

	@Test
	void handleAccessDeniedException_anonymousCallerWithSpringAccessDenied_returns401() {
		// Pins the boundary of the CWE-204 fix: in this codebase today, @PreAuthorize is used only
		// for coarse role-based admission (e.g. hasAnyRole('USER','ADMIN')) — per-entity ACL goes
		// through core AuthorizationException, which takes the 404 branch. The resulting Spring
		// AccessDeniedException must therefore keep the 401 WWW-Authenticate semantics for anonymous.
		// Note: @PreAuthorize CAN express per-entity SpEL (e.g. hasPermission(#id, ...)). If a future
		// endpoint adopts that, the mapping rule in handleAccessDeniedException must be revisited so
		// the per-entity AccessDeniedException doesn't re-open the enumeration oracle.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/management/loggers/ROOT");
		Authentication anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		AccessDeniedException ex = new AccessDeniedException("Access is denied");

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, anonymous);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(401, body.status());
		assertEquals("Unauthorized", body.error());
	}

	@Test
	void handleAccessDeniedException_anonymousCallerWithAuthorizationDenied_returns401() {
		// Spring Security 6 raises AuthorizationDeniedException (a subclass of AccessDeniedException)
		// from @PreAuthorize at the AuthorizationManager level. The handler's discriminator must treat
		// the whole AccessDeniedException family the same way — i.e. not match against the bare class
		// only. A regression that special-cased the exact class would slip this case to 404 (re-opening
		// the oracle on @PreAuthorize-guarded endpoints).
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/management/loggers/ROOT");
		Authentication anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		AuthorizationDeniedException ex = new AuthorizationDeniedException(
				"Access denied", (AuthorizationResult) () -> false);

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, anonymous);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(401, body.status());
		assertEquals("Unauthorized", body.error());
	}

	@Test
	void handleAccessDeniedException_authenticatedCaller_returns403() {
		// Authenticated callers have already proven identity, so existence disclosure is moot —
		// 403 is the correct status and CWE-204 doesn't apply. This guards against a regression
		// that would over-apply the anonymous→404 rewrite to authenticated callers.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");
		Authentication authenticated = new UsernamePasswordAuthenticationToken(
				"alice", "n/a",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		AuthorizationException ex = new AuthorizationException(null, null, AccessProperty.ReadMetadata);

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, authenticated);

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(403, body.status());
		assertEquals("Forbidden", body.error());
	}

	@Test
	void handleAccessDeniedException_authenticatedCallerWithSpringAccessDenied_returns403() {
		// Completes the 2×2 quadrant: authenticated × Spring AccessDeniedException. The split
		// logic in handleAccessDeniedException only branches on exception type for anonymous
		// callers; for authenticated callers it must always return 403 regardless of which
		// exception subclass fired. A regression that flipped this cell (e.g. to 401) would
		// otherwise slip through.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/management/loggers/ROOT");
		Authentication authenticated = new UsernamePasswordAuthenticationToken(
				"alice", "n/a",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		AccessDeniedException ex = new AccessDeniedException("Access is denied");

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, authenticated);

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(403, body.status());
		assertEquals("Forbidden", body.error());
	}

	@Test
	void handleAccessDeniedException_nullAuthentication_returns404() {
		// The handler's anonymous discriminator is `authentication == null || instanceof Anonymous…`,
		// so a null Authentication (e.g. filter-stage failure before SecurityContext is populated) must
		// take the same 404 branch as AnonymousAuthenticationToken. A regression dropping the null
		// short-circuit would NPE here.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");

		AuthorizationException ex = new AuthorizationException(null, null, AccessProperty.ReadMetadata);

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, null);

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(404, body.status());
		assertEquals("Not Found", body.error());
	}

	@Test
	void handleAccessDeniedException_emitsMaskedLogLineFor404AndStandardLineForOthers() {
		// Pins the differentiated log-line contract documented inline above the handler: ops/dashboards
		// depend on the "masked as 404" tag to separate enumeration probes from legitimate 404 traffic.
		// A refactor that collapses the two log branches back into one would silently break the tag and
		// degrade alerting; this test fails in that case.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");
		Authentication anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
		Authentication authenticated = new UsernamePasswordAuthenticationToken(
				"alice", "n/a",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		// Register a class-specific LoggerConfig so the appender only captures events emitted by
		// AppExceptionHandler's logger (not anything else logging at INFO during the test). The
		// `additive=false` flag prevents events from also propagating to the root logger.
		List<LogEvent> captured = new ArrayList<>();
		AbstractAppender appender = new AbstractAppender("captureForTest", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				captured.add(event.toImmutable());
			}
		};
		appender.start();
		try {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			String loggerName = AppExceptionHandler.class.getName();
			LoggerConfig scopedConfig = new LoggerConfig(loggerName, Level.INFO, false);
			scopedConfig.addAppender(appender, Level.INFO, null);
			context.getConfiguration().addLogger(loggerName, scopedConfig);
			try {
				context.updateLoggers();
				// Anonymous + core AuthorizationException → masked 404 log line.
				handler.handleAccessDeniedException(
						new AuthorizationException(null, null, AccessProperty.ReadMetadata), req, anonymous);
				// Anonymous + Spring AccessDeniedException → standard log line (401 path).
				handler.handleAccessDeniedException(new AccessDeniedException("Access is denied"), req, anonymous);
				// Authenticated + core AuthorizationException → standard log line (403 path).
				handler.handleAccessDeniedException(
						new AuthorizationException(null, null, AccessProperty.ReadMetadata), req, authenticated);
			} finally {
				context.getConfiguration().removeLogger(loggerName);
				context.updateLoggers();
			}
		} finally {
			appender.stop();
		}

		// Predicate-based counts decouple the assertion from emission order, so the handler is free
		// to reorder its two branches without touching the test. The "of type" substring is paired
		// with the exception class name so a reworded template that drops the URI/class fields would
		// degrade the ops signal AND fail the assertion.
		long masked = captured.stream()
				.filter(e -> e.getMessage().getFormattedMessage().contains("masked as 404 (anonymous, core ACL)"))
				.count();
		long standard = captured.stream()
				.filter(e -> e.getMessage().getFormattedMessage().contains("AccessDenied of type"))
				.filter(e -> e.getMessage().getFormattedMessage().contains("AuthorizationException")
						|| e.getMessage().getFormattedMessage().contains("AccessDeniedException"))
				.count();
		assertEquals(1L, masked, "Exactly one event must carry the masked-404 tag");
		assertEquals(2L, standard, "Exactly two events must take the standard branch with exception-class detail");
		assertEquals(3, captured.size(), "Total captured events must match the three handler invocations");
	}

	@Test
	void handleGenericException_unrelatedRuntimeException_returns500NotMappedTo503() {
		// Pins the boundary between handleRejectedExecution and handleGenericException: a
		// regression widening the @ExceptionHandler value on handleRejectedExecution from
		// RejectedExecutionException.class to a supertype (e.g. RuntimeException.class) would
		// silently start mapping all runtime errors to 503 with the back-off message. This test
		// fails iff that boundary is breached — a plain RuntimeException must still hit the
		// generic 500 path.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/sparql");

		ResponseEntity<ErrorResponse> response = handler.handleGenericException(
				new RuntimeException("some unrelated runtime error"), req);

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(500, body.status());
	}
}
