package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.ErrorResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.standalone.springboot.model.exception.ExpectationFailedHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.standalone.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.standalone.springboot.model.exception.PwResetEntityNotFoundHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.standalone.springboot.model.exception.UnauthorizedException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

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

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationExceptions(
			MethodArgumentNotValidException ex,
			HttpServletRequest request) {

		// Aggregate default messages from validation errors
		List<String> errorMessages = ex.getBindingResult()
				.getAllErrors()
				.stream()
				.map(DefaultMessageSourceResolvable::getDefaultMessage)
				.toList();

		// Build the response body
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.BAD_REQUEST.value())
				.path(request.getRequestURI())
				.error(errorMessages.toString())
				.build();

		return ResponseEntity.badRequest().body(responseBody);
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

	@ExceptionHandler(BadRequestHtmlException.class)
	public String handleBadRequestHtmlException(BadRequestHtmlException ex, Model model,
													   HttpServletResponse response) {
		log.debug("BadRequestHtmlException: {}", ex.getMessage());
		model.addAttribute("title", ex.getTitle());
		model.addAttribute("message", ex.getMessage());
		response.setStatus(HttpStatus.BAD_REQUEST.value());
		return "auth";
	}

	@ExceptionHandler(ExpectationFailedHtmlException.class)
	public String handleExpectationFailedHtmlException(ExpectationFailedHtmlException ex, Model model,
												 HttpServletResponse response) {
		log.debug("ExpectationFailedHtmlException: {}", ex.getMessage());
		model.addAttribute("title", ex.getTitle());
		model.addAttribute("message", ex.getMessage());
		response.setStatus(HttpStatus.EXPECTATION_FAILED.value());
		return "auth";
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

	@ExceptionHandler(PwResetEntityNotFoundHtmlException.class)
	public String handlePwResetBadRequestHtmlException(PwResetEntityNotFoundHtmlException ex, Model model,
													   HttpServletResponse response) {
		log.debug("PwResetEntityNotFoundHtmlException: {}", ex.getMessage());
		model.addAttribute("title", "Password reset");
		model.addAttribute("message", ex.getMessage());
		response.setStatus(HttpStatus.NOT_FOUND.value());
		return "auth";
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
																HttpServletRequest request) {
		log.error("Exception at endpoint '{}': {}", request.getRequestURI(), ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
				.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
				.path(request.getRequestURI())
				.error(ex.getMessage())
				.build();
		return ResponseEntity.internalServerError().body(responseBody);
	}
}
