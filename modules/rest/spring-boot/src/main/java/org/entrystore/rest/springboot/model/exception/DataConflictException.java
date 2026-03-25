package org.entrystore.rest.springboot.model.exception;

/**
 * A custom exception to be used in a service layer to indicate that Controller layer should
 * return 409 (CLIENT_ERROR_CONFLICT). This unifies the behaviour in ControllerAdvice (ExceptionHandler) across all services.
 * Expected behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at DEBUG level
 */
public class DataConflictException extends RuntimeException {

	public DataConflictException(String message) {
		super(message);
	}

	public DataConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
