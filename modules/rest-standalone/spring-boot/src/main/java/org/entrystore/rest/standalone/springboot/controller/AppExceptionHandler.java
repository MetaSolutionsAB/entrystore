package org.entrystore.rest.standalone.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
public class AppExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidationExceptions(
		MethodArgumentNotValidException ex,
		HttpServletRequest request) {

		// Aggregate default messages from validation errors
		List<String> errorMessages = ex.getBindingResult()
			.getAllErrors()
			.stream()
			.map(DefaultMessageSourceResolvable::getDefaultMessage)
			.collect(Collectors.toList());

		// Build the response body
		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("timestamp", LocalDateTime.now());
		responseBody.put("status", HttpStatus.BAD_REQUEST.value());
		responseBody.put("path", request.getRequestURI());
		responseBody.put("errors", errorMessages);

		return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleValidationExceptions(
		HttpMessageNotReadableException ex,
		HttpServletRequest request) {

		// Aggregate default messages from validation errors
		List<String> errorMessages = new ArrayList<>();
		errorMessages.add(ex.getMessage());

		// Build the response body
		Map<String, Object> responseBody = new HashMap<>();
		responseBody.put("timestamp", LocalDateTime.now());
		responseBody.put("status", HttpStatus.BAD_REQUEST.value());
		responseBody.put("path", request.getRequestURI());
		responseBody.put("errors", errorMessages);

		return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
	}
}
