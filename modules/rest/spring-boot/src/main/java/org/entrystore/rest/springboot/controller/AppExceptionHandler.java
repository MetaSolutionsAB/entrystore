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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.AuthorizationException;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.HtmlResponseException;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.springboot.model.exception.TextareaHtmlResponseException;
import org.entrystore.rest.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.ValidationErrorMessages;
import org.entrystore.rest.springboot.util.WebResourceUrls;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.ui.Model;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.RejectedExecutionException;

/**
 * Generic exception handler for application-specific exceptions.
 * Builds a consistent {@link ErrorResponse} envelope for every handled exception type, including
 * Spring's own {@link org.springframework.web.ErrorResponse} subclasses, since
 * {@code ErrorMvcAutoConfiguration} is excluded from the application and the {@code /error}
 * dispatch path is not available as a fallback.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class AppExceptionHandler {

	private final WebResourceUrls webResourceUrls;

	@ExceptionHandler(RedirectTemporaryException.class)
	public ResponseEntity<Void> handleUrlRedirectException(RedirectTemporaryException ex) {

		return ResponseEntity
				.status(HttpStatus.TEMPORARY_REDIRECT.value())
				.location(ex.getLocation())
				.build();
	}

	@ExceptionHandler(RedirectSeeOtherException.class)
	public ResponseEntity<Void> handleRedirectSeeOtherException(RedirectSeeOtherException ex) {

		return ResponseEntity
				.status(HttpStatus.SEE_OTHER)
				.location(ex.getLocation())
				.build();
	}

	@ExceptionHandler({BadRequestException.class, ValidationException.class, UsernameNotFoundException.class})
	public ResponseEntity<ErrorResponse> handleBadRequestException(RuntimeException ex,
																   HttpServletRequest request) {
		if (ex.getCause() != null) {
			log.info("BadRequestException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("BadRequestException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	// Separate BadRequest handler for HttpMessageNotReadableException and MethodArgumentTypeMismatchException since those leak Spring/Jackson internals
	// Those now respond with a generic "Bad Request" error. For MethodArgumentTypeMismatchException a
	// parameter-specific message is crafted here from the parameter name and the (sanitized) offending
	// value — both client-known, nothing internal. It cannot be crafted anywhere earlier: an exception
	// thrown by a Converter never survives binding, since TypeConverterDelegate swallows it and falls
	// back to a by-convention PropertyEditor (spring-web's MediaTypeEditor for MediaType params).
	@ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<ErrorResponse> handleSpringBadRequestException(Exception ex,
																		 HttpServletRequest request) {
		log.debug("BadRequestException of type '{}': {}", ex.getClass().getName(), ex.getMessage());
		String error = HttpStatus.BAD_REQUEST.getReasonPhrase();
		if (ex instanceof MethodArgumentTypeMismatchException typeMismatch) {
			error = "Invalid value '%s' for parameter '%s'".formatted(
					HttpUtil.sanitizeForLog(String.valueOf(typeMismatch.getValue())), typeMismatch.getName());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(error)
				.build();
		return jsonResponse(responseBody);
	}

	// Anonymous callers receive the bare reason phrase to prevent CWE-204 entry-existence enumeration
	// (the message would carry "No entry with id 'X' found in context 'Y'" and let a guest distinguish
	// missing-entry from private-entry via handleAccessDeniedException's 404). Authenticated callers
	// receive the call-site-crafted message: EntityNotFoundException messages don't carry internal
	// state (no principal URIs, no hostnames), only entry/context IDs the caller already knows.
	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleEntityNotFoundException(EntityNotFoundException ex,
																	   HttpServletRequest request,
																	   Authentication authentication) {
		if (ex.getCause() != null) {
			log.debug("EntityNotFoundException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("EntityNotFoundException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_FOUND.value())
				.path(request.getRequestURI())
				.error(isAnonymous(authentication) ? HttpStatus.NOT_FOUND.getReasonPhrase() : ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException ex,
																		HttpServletRequest request) {
		log.debug("NoResourceFoundException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_FOUND.value())
				.path(request.getRequestURI())
				.error(HttpStatus.NOT_FOUND.getReasonPhrase())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(MethodNotAllowedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotAllowedException(MethodNotAllowedException ex,
																		 HttpServletRequest request) {
		log.debug("MethodNotAllowedException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.METHOD_NOT_ALLOWED.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(DataConflictException.class)
	public ResponseEntity<ErrorResponse> handleDataConflictException(DataConflictException ex,
																	 HttpServletRequest request) {
		log.warn("DataConflictException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.CONFLICT.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(NotImplementedException.class)
	public ResponseEntity<ErrorResponse> handleNotImplementedException(NotImplementedException ex,
																	   HttpServletRequest request) {
		log.warn("NotImplementedException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_IMPLEMENTED.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler({AuthenticationException.class})
	public ResponseEntity<ErrorResponse> handleUnauthorizedException(RuntimeException ex,
																	 HttpServletRequest request) {
		log.info("UnauthorizedException at endpoint '{}'. Error: {}", request.getRequestURI(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.UNAUTHORIZED.value())
				.path(request.getRequestURI())
				.error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
				.build();
		return jsonResponse(responseBody);
	}

	// Handles Spring Security's AccessDeniedException and the core AuthorizationException.
	// Both carry internal state in their messages (principal URI, entry URI, ACL bit for core,
	// or a non-informative "Access Denied" for Spring), so the HTTP body is always the reason
	// phrase; the original message is retained on the server-side log line for debugging.
	//
	// Status mapping splits on the exception type for anonymous callers:
	// - Core `AuthorizationException` (raised by core ACL checks — `PrincipalManager`, `ContextImpl`, etc.) → 404 Not Found,
	//   to prevent CWE-204 existence enumeration: without this, a guest could distinguish
	//   "entry exists but is private" (401) from "entry does not exist" (404).
	// - Spring's `AccessDeniedException` (raised by Spring method security — `@PreAuthorize`,
	//   `@PostAuthorize`, `@Secured`, or `AuthorizationManager` checks) → 401 Unauthorized,
	//   preserving the standard "you must authenticate" semantics for endpoints that explicitly
	//   require a role. In this codebase today these guards are coarse role-based admission and
	//   don't gate per-entity existence, so they don't open an enumeration oracle. If a future
	//   contributor adds per-entity SpEL (e.g. `@PreAuthorize("hasPermission(#id, 'read')")`),
	//   the resulting AccessDeniedException would re-open CWE-204 on that endpoint and this
	//   mapping must be revisited — see
	//   AppExceptionHandlerTest#handleAccessDeniedException_anonymousCallerWithSpringAccessDenied_returns401.
	// Authenticated callers keep 403 in both cases — they've already proven identity, so
	// existence disclosure is moot.
	//
	// The 404 branch also emits a distinguishable log message ("AccessDenied masked as 404 …") so
	// operators can separate enumeration probes from legitimate missing-entry traffic in dashboards;
	// that contract is pinned by
	// AppExceptionHandlerTest#handleAccessDeniedException_emitsMaskedLogLineFor404AndStandardLineForOthers.
	@ExceptionHandler({AccessDeniedException.class, AuthorizationException.class})
	public ResponseEntity<ErrorResponse> handleAccessDeniedException(RuntimeException ex,
																	 HttpServletRequest request,
																	 Authentication authentication) {
		HttpStatus status;
		if (isAnonymous(authentication)) {
			status = (ex instanceof AuthorizationException) ? HttpStatus.NOT_FOUND : HttpStatus.UNAUTHORIZED;
		} else {
			status = HttpStatus.FORBIDDEN;
		}
		if (status == HttpStatus.NOT_FOUND) {
			// Stays at INFO because the log line itself fires per anonymous request and is therefore
			// attacker-floodable. CWE-204 enumeration-scan detection belongs in an aggregated signal
			// (e.g. a Micrometer counter on this branch tag), not in a per-event log level.
			log.info("AccessDenied masked as 404 (anonymous, core ACL) at endpoint '{}'. Original: {}",
					request.getRequestURI(), ex.getMessage());
		} else {
			log.info("AccessDenied of type '{}' at endpoint '{}'. Error: {}",
					ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(status.value())
				.path(request.getRequestURI())
				.error(status.getReasonPhrase())
				.build();
		return jsonResponse(responseBody);
	}

	// Handles application-specific authentication/authorization exceptions whose messages are
	// intentionally user-facing (hand-crafted at call sites in SolrManagementService, AuthService,
	// ResourceService, ProxyService, etc.), so the message is surfaced in the HTTP body for 403.
	@ExceptionHandler({UnauthorizedException.class, ForbiddenException.class, AuthenticationCredentialsNotFoundException.class})
	public ResponseEntity<ErrorResponse> handleForbiddenException(RuntimeException ex,
																  HttpServletRequest request,
																  Authentication authentication) {
		log.info("ForbiddenException of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
		HttpStatus status = isAnonymous(authentication) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(status.value())
				.path(request.getRequestURI())
				.error((status == HttpStatus.UNAUTHORIZED) ? status.getReasonPhrase() : ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(EntityTooLargeException.class)
	public ResponseEntity<ErrorResponse> handleEntityTooLargeException(EntityTooLargeException ex,
																	   HttpServletRequest request) {
		if (ex.getCause() != null) {
			log.debug("EntityTooLargeException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("EntityTooLargeException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.CONTENT_TOO_LARGE.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(CustomResponseException.class)
	public ResponseEntity<ErrorResponse> handleCustomResponseException(CustomResponseException ex,
																	   HttpServletRequest request) {
		log.info("CustomResponseException ({}) at endpoint '{}': {}", ex.getStatus().value(), request.getRequestURI(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(ex.getStatus().value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(RejectedExecutionException.class)
	public ResponseEntity<ErrorResponse> handleRejectedExecution(RejectedExecutionException ex,
																 HttpServletRequest request) {
		// Thrown by AbortPolicy when the bounded MVC async executor (mvc.async.*) overflows.
		// 503 lets the client back off and retry rather than treating it as a server fault.
		log.warn("Async dispatch rejected (overflow) at endpoint '{}': {}",
				request.getRequestURI(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.SERVICE_UNAVAILABLE.value())
				.path(request.getRequestURI())
				.error("Server temporarily overloaded; retry later")
				.build();
		return jsonResponse(responseBody);
	}

	// Both Bean Validation failures land here so that neither echoes an internal identifier.
	//
	// MethodArgumentNotValidException (an invalid @Valid request body) used to fall through to
	// handleGenericException, which surfaces ex.getMessage() because the exception implements Spring's
	// ErrorResponse; that message is built from the failing method's toGenericString() plus every
	// ObjectError's toString(), so the caller received the Java signature, the request-body class name,
	// the rejected value and Spring's constraint codes. POST /message is among the affected endpoints and
	// anonymous callers reach it: validation fails during argument resolution, before MessageService's
	// guest check runs.
	//
	// ConstraintViolationException (an invalid @Validated method parameter, as on SearchController's
	// @RequestParam @Size) reached handleBadRequestException instead, since it extends ValidationException
	// — and that handler also echoes ex.getMessage(), which for this type is
	// "<methodName>.<paramName>: <message>". Anonymous callers of GET /search were therefore handed the
	// controller method name. Reporting the violation's own message drops the prefix and keeps only what
	// the caller told us.
	//
	// The browser-facing auth endpoints do not arrive here: they validate through RequestBodyValidator so
	// their HTML rendering and their request-size limit both keep working, so there is no HTML branch to
	// maintain. Putting @Valid on a view-rendering endpoint would answer JSON where the flow answers
	// HTML — validate it through RequestBodyValidator instead.
	@ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
	public ResponseEntity<ErrorResponse> handleValidationFailure(Exception ex,
																 HttpServletRequest request) {

		String target;
		String message;
		if (ex instanceof MethodArgumentNotValidException bodyFailure) {
			Class<?> bodyType = bodyFailure.getParameter().getParameterType();
			target = bodyType.getSimpleName();
			// Global errors as a fallback: SpringValidatorAdapter routes any violation with an empty
			// property path — a class-level or cross-field constraint — to getGlobalErrors(), which
			// getFieldErrors() never contains, so without this such a failure reports only "Bad Request"
			// and its message is recorded nowhere.
			message = ValidationErrorMessages.firstFromFieldErrors(bodyType, bodyFailure.getFieldErrors())
					.or(() -> bodyFailure.getGlobalErrors().stream()
							.map(ObjectError::getDefaultMessage)
							.filter(StringUtils::isNotBlank)
							.findFirst())
					.orElse(HttpStatus.BAD_REQUEST.getReasonPhrase());
		} else {
			ConstraintViolationException parameterFailure = (ConstraintViolationException) ex;
			target = "request parameters";
			message = ValidationErrorMessages
					.firstFromViolations(Object.class, parameterFailure.getConstraintViolations())
					.orElse(HttpStatus.BAD_REQUEST.getReasonPhrase());
		}
		log.debug("Validation failed for {} at endpoint '{}': {}",
				target, request.getRequestURI(), message);

		return jsonResponse(ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(message)
				.build());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex,
																HttpServletRequest request) {

		if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
			int code = errorResponse.getStatusCode().value();
			HttpStatus status = HttpStatus.resolve(code);
			String reasonPhrase = status != null ? status.getReasonPhrase() : "Error";
			// Surface the exception message when present (Spring's own ErrorResponse subclasses
			// carry structured problem details there — e.g. supported media types for 406/415 —
			// and their messages do not leak internals). Fall back to the reason phrase otherwise.
			String errorMessage = StringUtils.isNotBlank(ex.getMessage()) ? ex.getMessage() : reasonPhrase;
			log.debug("General ErrorResponse Exception of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
			ErrorResponse responseBody = ErrorResponse.builder()
					.status(code)
					.path(request.getRequestURI())
					.error(errorMessage)
					.build();
			return jsonResponse(responseBody);
		}

		log.error("Unhandled general Exception of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
				.path(request.getRequestURI())
				.error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
				.build();
		return jsonResponse(responseBody);
	}

	private static ResponseEntity<ErrorResponse> jsonResponse(ErrorResponse body) {
		return ResponseEntity.status(body.status()).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	private static boolean isAnonymous(Authentication authentication) {
		return authentication == null || authentication instanceof AnonymousAuthenticationToken;
	}

	@ExceptionHandler(HtmlResponseException.class)
	public String handleHtmlException(HtmlResponseException ex,
									  Model model,
									  HttpServletRequest request,
									  HttpServletResponse response) {

		if (ex.getCause() != null) {
			log.info("HtmlResponseException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("HtmlResponseException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}
		// @ModelAttribute methods are not invoked for @ExceptionHandler views, so the path the
		// controller would normally contribute has to be set here as well.
		model.addAttribute("stylesheetPath", webResourceUrls.getStylesheetPath());
		model.addAttribute("title", ex.getTitle());
		model.addAttribute("message", ex.getMessage());
		String linkUrl = ex.getLinkUrl();
		if (linkUrl != null && (linkUrl.startsWith("http://") || linkUrl.startsWith("https://"))) {
			model.addAttribute("linkUrl", linkUrl);
		}
		response.setStatus(ex.getStatus().value());
		return "auth";
	}

	@ExceptionHandler(TextareaHtmlResponseException.class)
	public String handleTextareaHtmlResponseException(TextareaHtmlResponseException ex,
													  Model model,
													  HttpServletRequest request,
													  HttpServletResponse response) {

		if (ex.getCause() != null) {
			log.info("TextareaHtmlResponseException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("TextareaHtmlResponseException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}

		String textAreaVal = "status:" + ex.getStatus().value() + "\n" + ex.getMessage();
		model.addAttribute("textareaValue", textAreaVal);

		response.setStatus(ex.getStatus().value());
		response.setContentType(MediaType.TEXT_HTML_VALUE);
		return "textarea";
	}
}
