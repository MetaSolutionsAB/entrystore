package org.entrystore.rest.springboot.model.exception;

/**
 * A custom exception to be used to return 501 (SERVER_ERROR_NOT_IMPLEMENTED) by the service and unify the behaviour in ControllerAdvice (ExceptionHandler).
 * The expected behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at warn level
 */
public class NotImplementedException extends RuntimeException {

	public NotImplementedException(String message) {
		super(message);
	}
}
