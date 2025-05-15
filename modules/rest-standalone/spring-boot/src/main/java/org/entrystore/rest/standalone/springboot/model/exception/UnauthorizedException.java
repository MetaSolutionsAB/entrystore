package org.entrystore.rest.standalone.springboot.model.exception;

/**
 * A custom exception to be used in a service layer to indicate that Controller layer should
 * return 401 (UNAUTHORIZED). This unifies the behaviour in ControllerAdvice (ExceptionHandler) across all services.
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at info level
 */
public class UnauthorizedException extends RuntimeException {

	public UnauthorizedException(String message) {
		super(message);
	}
}
