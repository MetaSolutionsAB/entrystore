package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.AuthorizationException;
import org.entrystore.rest.standalone.springboot.model.api.ErrorResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.model.exception.HtmlResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.standalone.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.standalone.springboot.model.exception.TextareaHtmlResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.UnauthorizedException;
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
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Generic Exception handler to handle application specific exceptions.
 * If an exception is thrown that implements org.springframework.web.ErrorResponse, then it will fall into generic method
 * handler for Exception.class, however ErrorResponse is handled by Spring-boot, so we just re-throw it. Only log as error
 * with 500 response all other exception types.
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

	@ExceptionHandler({BadRequestException.class, MethodArgumentTypeMismatchException.class, ValidationException.class,
			UsernameNotFoundException.class, HttpMessageNotReadableException.class})
	public ResponseEntity<ErrorResponse> handleBadRequestException(RuntimeException ex,
																   HttpServletRequest request) {
		log.debug("BadRequestException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.badRequest().body(responseBody);
	}

	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleEntityNotFoundException(EntityNotFoundException ex,
																	   HttpServletRequest request) {
		log.debug("EntityNotFoundException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_FOUND.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.status(responseBody.status()).body(responseBody);
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
		return ResponseEntity.status(responseBody.status()).body(responseBody);
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
		return ResponseEntity.status(responseBody.status()).body(responseBody);
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
		return ResponseEntity.status(responseBody.status()).body(responseBody);
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
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler({AuthorizationException.class, UnauthorizedException.class, ForbiddenException.class, AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class})
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
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler(EntityTooLargeException.class)
	public ResponseEntity<ErrorResponse> handleEntityTooLargeException(EntityTooLargeException ex,
																	   HttpServletRequest request) {
		log.debug("EntityTooLargeException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.PAYLOAD_TOO_LARGE.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler(CustomResponseException.class)
	public ResponseEntity<ErrorResponse> handleCustomResponseException(CustomResponseException ex,
																	   HttpServletRequest request) {
		log.info("CustomResponseException ({}): {}", ex.getStatus().value(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(ex.getStatus().value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex,
																HttpServletRequest request) throws Exception {

		if (ex instanceof org.springframework.web.ErrorResponse) {
			// handled by Spring-boot so we don't need to here
			log.debug("General ErrorResponse Exception of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
			throw ex;
		}

		log.error("Unhandled general Exception of type '{}' at endpoint '{}'. Error: {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage(), ex);
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.internalServerError().body(responseBody);
	}

	@ExceptionHandler(HtmlResponseException.class)
	public String handleHtmlException(HtmlResponseException ex,
									  Model model,
									  HttpServletResponse response) {

		log.debug("HtmlResponseException: {}", ex.getMessage());
		model.addAttribute("title", ex.getTitle());
		model.addAttribute("message", ex.getMessage());
		response.setStatus(ex.getStatus().value());
		return "auth";
	}

	@ExceptionHandler(TextareaHtmlResponseException.class)
	public String handleTextareaHtmlResponseException(TextareaHtmlResponseException ex,
													  Model model,
													  HttpServletResponse response) {

		log.debug("TextareaHtmlResponseException: {}", ex.getMessage());

		String textAreaVal = "status:" + ex.getStatus().value() + "\n" + ex.getMessage();
		model.addAttribute("textareaValue", textAreaVal);

		response.setStatus(ex.getStatus().value());
		response.setContentType(MediaType.TEXT_HTML_VALUE);
		return "textarea";
	}
}
