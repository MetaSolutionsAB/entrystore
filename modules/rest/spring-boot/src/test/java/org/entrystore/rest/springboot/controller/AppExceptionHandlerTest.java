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
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.WebResourceUrls;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppExceptionHandlerTest {

	private final AppExceptionHandler handler = new AppExceptionHandler(new WebResourceUrls("http://localhost:8181/store/"));

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
	void handleCustomResponseException_uncommittedResponse_returnsTheStatusAndMessage() {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/proxy");

		ResponseEntity<ErrorResponse> response = handler.handleCustomResponseException(
				new CustomResponseException("Upstream too large", HttpStatus.BAD_GATEWAY), req,
				new MockHttpServletResponse());

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertEquals("Upstream too large", response.getBody().error());
	}

	@Test
	void handleCustomResponseException_committedResponse_rethrowsSoTheContainerAbortsTheConnection() {
		// Rendering the error would append a JSON body to the partial one and end the response normally.
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/proxy");
		MockHttpServletResponse committed = new MockHttpServletResponse();
		committed.setCommitted(true);
		CustomResponseException ex = new CustomResponseException("Upstream too large", HttpStatus.BAD_GATEWAY);

		CustomResponseException thrown = assertThrows(CustomResponseException.class,
				() -> handler.handleCustomResponseException(ex, req, committed));

		assertSame(ex, thrown);
	}

	@Test
	void handleAccessDeniedException_anonymousCaller_returns401NotAuthorizedAs5x() {
		// EntryScape opens its login dialog on 401 and matches "Not authorized" in the body. The exception is
		// built with realistic principal/entry URIs so the assertion also pins that its message never leaks.
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

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(401, body.status());
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAccessDeniedException_anonymousCallerWithSpringAccessDenied_returns401NotAuthorized() {
		// @PreAuthorize denials answer like core ACL denials, as 5.x answered every authorization denial.
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
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAccessDeniedException_anonymousCallerWithAuthorizationDenied_returns401NotAuthorized() {
		// Spring Security raises AuthorizationDeniedException, a subclass of AccessDeniedException, from
		// @PreAuthorize; it must be answered like the rest of the family.
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
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAccessDeniedException_authenticatedCaller_returns403NotAuthorized() {
		// Logging in would not help an authenticated caller, so it gets 403 rather than the anonymous 401, with the
		// same 5.x body, which EntryScape matches for every caller.
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
		assertEquals("Not authorized", body.error());
	}

	/**
	 * ENTRYSTORE-1055. The sibling handler's 2×2 quadrant above is fully pinned; this one was not pinned
	 * at all, which matters more now that {@code ForbiddenException} is the single type for every policy
	 * denial raised in application code. The ternary under test is the only thing suppressing call-site
	 * messages for unauthenticated callers, and no integration test covers it: {@code ContextIT} and
	 * {@code MessageIT} assert the status code without reading the body, and {@code ErrorResponseIT}'s
	 * guest case goes through {@code @PreAuthorize} into {@code handleAccessDeniedException} instead.
	 */
	@Test
	void handleForbiddenException_anonymousCaller_returns401WithReasonPhraseNotTheCallSiteMessage() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/_principals/groups");
		Authentication anonymous = new AnonymousAuthenticationToken(
				"key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		ResponseEntity<ErrorResponse> response = handler.handleForbiddenException(
				new ForbiddenException("Not allowed for not-admin user to create a group"), req, anonymous);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(401, body.status());
		assertEquals("Not authorized", body.error());
		// The call-site message names which guard fired and that the caller is merely non-admin rather
		// than unauthenticated; an unauthenticated prober must learn neither, from any field.
		assertFalse(body.toString().contains("not-admin"),
				"the call-site message must not reach an anonymous caller");
	}

	@Test
	void handleForbiddenException_authenticatedCaller_returns403WithTheCallSiteMessage() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/_principals/groups");
		Authentication authenticated = new UsernamePasswordAuthenticationToken(
				"alice", "n/a",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		ResponseEntity<ErrorResponse> response = handler.handleForbiddenException(
				new ForbiddenException("Not allowed for not-admin user to create a group"), req, authenticated);

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(403, body.status());
		assertEquals("Not allowed for not-admin user to create a group", body.error());
	}

	@Test
	void handleAccessDeniedException_authenticatedCallerWithSpringAccessDenied_returns403NotAuthorized() {
		// Completes the 2×2 quadrant: authenticated × Spring AccessDeniedException.
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
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAccessDeniedException_nullAuthentication_returns401() {
		// A null Authentication (e.g. a failure before the SecurityContext is populated) counts as anonymous.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");

		AuthorizationException ex = new AuthorizationException(null, null, AccessProperty.ReadMetadata);

		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(ex, req, null);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(401, body.status());
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAuthenticationException_anonymousDeniedByUrlRule_returns401NotAuthorized() {
		// ExceptionTranslationFilter raises this when an anonymous caller hits a URL-level role rule.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/management/status/extended");

		ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(
				new InsufficientAuthenticationException("Full authentication is required"), req);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals("Not authorized", body.error());
	}

	@Test
	void handleAuthenticationException_badCredentials_returns401WithReasonPhrase() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/auth/cookie");

		ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(
				new BadCredentialsException("Bad credentials"), req);

		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals("Unauthorized", body.error());
	}

	@Test
	void handleEntityNotFoundException_returns404WithTheCallSiteMessageToEveryCaller() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/42");

		ResponseEntity<ErrorResponse> response = handler.handleEntityNotFoundException(
				new EntityNotFoundException("No entry with id '42' found in context '1'"), req);

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(404, body.status());
		assertEquals("No entry with id '42' found in context '1'", body.error());
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
		assertEquals("Internal Server Error", body.error(), "The generic 500 must not echo the exception message");
	}

	@Test
	void handleGenericException_internalServerErrorException_bodyCarriesReasonPhraseNotMessage() {
		// InternalServerErrorException's javadoc promises the response "error" is the bare reason phrase and
		// never the exception message — which is precisely what lets call sites name internal details in the
		// message, e.g. GraphUtil.serializeGraph reporting the RDF writer class it failed to instantiate.
		// Nothing enforced that promise: the exception reaches this handler only because it has no dedicated
		// @ExceptionHandler, while eight sibling handlers in AppExceptionHandler do echo ex.getMessage(). A
		// future handler written in that prevailing style would leak the internal name with no failing test.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/1/entry/2");

		ResponseEntity<ErrorResponse> response = handler.handleGenericException(
				new InternalServerErrorException("Failed to instantiate RDF writer org.example.SecretWriter"), req);

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(500, body.status());
		assertEquals("Internal Server Error", body.error());
		assertFalse(body.error().contains("SecretWriter"),
				"The internal writer class name must not reach the client");
	}

	@Test
	void handleSpringBadRequestException_typeMismatch_craftsParameterSpecificMessage() throws Exception {
		// Pins the converter-binding path: an exception thrown by a Converter (e.g. MediaTypeConverter)
		// never survives binding — TypeConverterDelegate swallows it and falls back to spring-web's
		// MediaTypeEditor — so this handler is the only place that can tell the client which parameter
		// was invalid, using the parameter name and offending value from the exception itself.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/ctx/metadata/42");
		MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
				"notamediatype", MediaType.class, "format", formatParameter(),
				new InvalidMediaTypeException("notamediatype", "does not contain '/'"));

		ResponseEntity<ErrorResponse> response = handler.handleSpringBadRequestException(ex, req);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(400, body.status());
		assertEquals("Invalid value 'notamediatype' for parameter 'format'", body.error());
		assertEquals("/ctx/metadata/42", body.path());
	}

	@Test
	void handleSpringBadRequestException_typeMismatchWithControlCharacters_sanitizesEchoedValue() throws Exception {
		// The offending value is client input echoed into the response; mid-string CR/LF must not
		// survive (CWE-117 log forging via the handler's log line and the JSON body).
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/ctx/metadata/42");
		MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
				"text\nforged log line", MediaType.class, "format", formatParameter(),
				new InvalidMediaTypeException("text\nforged log line", "does not contain '/'"));

		ResponseEntity<ErrorResponse> response = handler.handleSpringBadRequestException(ex, req);

		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertFalse(body.error().contains("\n"));
		assertEquals("Invalid value 'text?forged log line' for parameter 'format'", body.error());
	}

	@Test
	void handleSpringBadRequestException_nonTypeMismatch_staysGeneric() {
		// The generic-body contract must hold for the other handled types: Spring/Jackson internals in
		// ex.getMessage() must not leak just because type mismatches get a crafted message.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/search");

		ResponseEntity<ErrorResponse> response = handler.handleSpringBadRequestException(
				new MissingServletRequestParameterException("query", "String"), req);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(400, body.status());
		assertEquals("Bad Request", body.error());
	}

	private MethodParameter formatParameter() throws NoSuchMethodException {
		return new MethodParameter(
				AppExceptionHandlerTest.class.getDeclaredMethod("bindingTarget", MediaType.class), 0);
	}

	@SuppressWarnings("unused")
	private void bindingTarget(MediaType format) {
	}

	/**
	 * Without a dedicated handler, MethodArgumentNotValidException reaches handleGenericException, which
	 * echoes {@code ex.getMessage()} because the exception implements Spring's ErrorResponse. That
	 * message is assembled from the failing method's {@code toGenericString()} and every
	 * {@code ObjectError.toString()}, so the client would receive the Java signature, the DTO class name
	 * and Spring's constraint codes. This asserts the message the client sees carries none of that.
	 */
	@Test
	void handleValidationFailure_reportsOnlyTheConstraintMessage() throws Exception {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/message");

		ResponseEntity<ErrorResponse> response = handler.handleValidationFailure(
				validationFailure(jsonTarget(), "subject", "must not be blank"), req);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals("must not be blank", body.error());
		assertEquals("/message", body.path());
		assertFalse(body.error().contains("jsonTarget"), "must not name the failing method");
		assertFalse(body.error().contains("ValidatedBody"), "must not name the body class");
		assertFalse(body.error().contains("codes ["), "must not expose Spring constraint codes");
	}

	/**
	 * Two fields failing at once must resolve to the field declared first, not to whichever the
	 * validator happened to return first — the sequential checks this replaced always reported the
	 * earlier field.
	 */
	@Test
	void handleValidationFailure_severalFields_reportsTheFirstDeclaredOne() throws Exception {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/message");

		ResponseEntity<ErrorResponse> response = handler.handleValidationFailure(
				validationFailure(jsonTarget(), "password", "password message", "email", "email message"), req);

		assertNotNull(response.getBody());
		assertEquals("email message", response.getBody().error());
	}

	/** Nothing usable in the binding result still has to reject the request, not pass it. */
	@Test
	void handleValidationFailure_noFieldErrors_fallsBackToTheReasonPhrase() throws Exception {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/message");

		ResponseEntity<ErrorResponse> response = handler.handleValidationFailure(
				validationFailure(jsonTarget()), req);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertNotNull(response.getBody());
		assertEquals("Bad Request", response.getBody().error());
	}

	/** field/message pairs, in the order Spring would hand them over. */
	private static MethodArgumentNotValidException validationFailure(MethodParameter parameter,
																	 String... fieldsAndMessages) {
		BeanPropertyBindingResult binding =
				new BeanPropertyBindingResult(null, parameter.getParameterType().getSimpleName());
		for (int i = 0; i < fieldsAndMessages.length; i += 2) {
			binding.addError(new FieldError(binding.getObjectName(), fieldsAndMessages[i],
					null, false, null, null, fieldsAndMessages[i + 1]));
		}
		return new MethodArgumentNotValidException(parameter, binding);
	}

	private MethodParameter jsonTarget() throws NoSuchMethodException {
		return new MethodParameter(
				AppExceptionHandlerTest.class.getDeclaredMethod("jsonTargetMethod", ValidatedBody.class), 0);
	}

	private record ValidatedBody(String email, String password, String subject) {
	}

	@SuppressWarnings("unused")
	private void jsonTargetMethod(ValidatedBody body) {
	}

}
