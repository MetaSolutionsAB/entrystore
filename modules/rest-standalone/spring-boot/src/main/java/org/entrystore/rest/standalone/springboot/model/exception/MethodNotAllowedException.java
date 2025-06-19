package org.entrystore.rest.standalone.springboot.model.exception;

/**
 * A custom exception to be used to return 405 (METHOD_NOT_ALLOWED) by a service and unify the behaviour in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message and 405 as code
 * - exception message logged in the logs at debug level
 */
public class MethodNotAllowedException extends RuntimeException {

	public MethodNotAllowedException(String message) {
		super(message);
	}
}
