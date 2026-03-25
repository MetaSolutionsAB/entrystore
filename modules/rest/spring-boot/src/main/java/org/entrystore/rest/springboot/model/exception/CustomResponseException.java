package org.entrystore.rest.springboot.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return any HTTP Status response and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behavior:
 * - common response model, with "error" field set from the "message" field
 * - exception message logged in the logs at info level
 */
@Getter
public class CustomResponseException extends RuntimeException {

	private final HttpStatus status;

	public CustomResponseException(String message, HttpStatus status) {
		super(message);
		this.status = status;
	}
}
