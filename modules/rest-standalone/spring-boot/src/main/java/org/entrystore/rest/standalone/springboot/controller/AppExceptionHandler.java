package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.entrystore.rest.standalone.springboot.model.api.ErrorResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;
import java.util.List;

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
		ErrorResponse responseBody = new ErrorResponse(
			LocalDateTime.now(),
			HttpStatus.BAD_REQUEST.value(),
			request.getRequestURI(),
			errorMessages.toString());

		return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableAndIllegalArgumentException(
		Exception ex,
		HttpServletRequest request) {

		// Build the response body
		ErrorResponse responseBody = new ErrorResponse(
			LocalDateTime.now(),
			HttpStatus.BAD_REQUEST.value(),
			request.getRequestURI(),
			ex.getMessage());

		return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
	}
}
