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
import jakarta.validation.ValidationException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Generic exception handler for application-specific exceptions.
 * Builds a consistent {@link ErrorResponse} envelope for every handled exception type, including
 * Spring's own {@link org.springframework.web.ErrorResponse} subclasses, since
 * {@code ErrorMvcAutoConfiguration} is excluded from the application and the {@code /error}
 * dispatch path is not available as a fallback.
 */
@Slf4j
@ControllerAdvice
public class AppExceptionHandler {

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
	// Those now respond with a generic "Bad Request" error
	@ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<ErrorResponse> handleSpringBadRequestException(Exception ex,
																		 HttpServletRequest request) {
		log.debug("BadRequestException of type '{}': {}", ex.getClass().getName(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(HttpStatus.BAD_REQUEST.getReasonPhrase())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleEntityNotFoundException(EntityNotFoundException ex,
																	   HttpServletRequest request) {
		if (ex.getCause() != null) {
			log.debug("EntityNotFoundException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage(), ex);
		} else {
			log.debug("EntityNotFoundException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		}
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_FOUND.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
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
	@ExceptionHandler({AccessDeniedException.class, AuthorizationException.class})
	public ResponseEntity<ErrorResponse> handleAccessDeniedException(RuntimeException ex,
																	 HttpServletRequest request,
																	 Authentication authentication) {
		log.info("AccessDenied of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
		HttpStatus status = (authentication == null || authentication instanceof AnonymousAuthenticationToken) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
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
		HttpStatus status = (authentication == null || authentication instanceof AnonymousAuthenticationToken) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
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
				.status(HttpStatus.PAYLOAD_TOO_LARGE.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
	}

	@ExceptionHandler(CustomResponseException.class)
	public ResponseEntity<ErrorResponse> handleCustomResponseException(CustomResponseException ex,
																	   HttpServletRequest request) {
		log.info("CustomResponseException ({}): {}", ex.getStatus().value(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(ex.getStatus().value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return jsonResponse(responseBody);
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
