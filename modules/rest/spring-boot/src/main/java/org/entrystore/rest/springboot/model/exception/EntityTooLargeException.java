package org.entrystore.rest.springboot.model.exception;

/**
 * A custom exception to be used to return 413 (ENTITY TOO LARGE) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
public class EntityTooLargeException extends RuntimeException {

	public EntityTooLargeException(String message) {
		super(message);
	}

	public EntityTooLargeException(String message, Throwable cause) {
		super(message, cause);
	}
}
