package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.ErrorResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
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

	@ExceptionHandler({BadRequestException.class, MethodArgumentTypeMismatchException.class})
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

	@ExceptionHandler(UnauthorizedException.class)
	public ResponseEntity<ErrorResponse> handleUnauthorizedException(UnauthorizedException ex,
																	 HttpServletRequest request) {
		log.info("UnauthorizedException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.UNAUTHORIZED.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler(DataConflictException.class)
	public ResponseEntity<ErrorResponse> handleDataConflictException(DataConflictException ex,
																	 HttpServletRequest request) {
		log.warn("DataConflictException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
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
		log.warn("NotImplementedException at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.NOT_IMPLEMENTED.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.status(responseBody.status()).body(responseBody);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(Exception ex,
																			   HttpServletRequest request) {
		log.debug("HttpMessageNotReadableException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.badRequest().body(responseBody);
	}

	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<ErrorResponse> handleForbiddenException(ForbiddenException ex,
																  HttpServletRequest request) {
		log.debug("ForbiddenException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.FORBIDDEN.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
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

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex,
																HttpServletRequest request) throws Exception {

		if (ex instanceof org.springframework.web.ErrorResponse) {
			// handled by Spring-boot so we don't need to here
			log.debug("General ErrorResponse Exception of '{}' at endpoint '{}': {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
			throw ex;
		}

		log.error("Unhandled general Exception of '{}' at endpoint '{}': {}", ex.getClass().getName(), request.getRequestURI(), ex.getMessage());
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
