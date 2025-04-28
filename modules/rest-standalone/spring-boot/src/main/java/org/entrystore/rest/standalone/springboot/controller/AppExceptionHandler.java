package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.ErrorResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@Slf4j
@ControllerAdvice
public class AppExceptionHandler {

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
			.errors(errorMessages.toString())
			.build();

		return ResponseEntity.badRequest().body(responseBody);
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex,
																   HttpServletRequest request) {
		log.debug("BadRequestException: {}", ex.getMessage());
		ErrorResponse responseBody = ErrorResponse.builder()
			.status(HttpStatus.BAD_REQUEST.value())
			.path(request.getRequestURI())
			.errors(ex.getMessage())
			.build();
		return ResponseEntity.badRequest().body(responseBody);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(Exception ex,
																			   HttpServletRequest request) {
		ErrorResponse responseBody = ErrorResponse.builder()
			.status(HttpStatus.BAD_REQUEST.value())
			.path(request.getRequestURI())
			.errors(ex.getMessage())
			.build();
		return ResponseEntity.badRequest().body(responseBody);
	}
}
