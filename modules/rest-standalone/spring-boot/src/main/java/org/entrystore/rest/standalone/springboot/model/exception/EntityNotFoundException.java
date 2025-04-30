package org.entrystore.rest.standalone.springboot.model.exception;

/**
 * A custom exception to be used to return 404 (NOT_FOUND) by the service and unify the behaviour in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message and 404 as code
 * - exception message logged in the logs at debug level
 */
public class EntityNotFoundException extends RuntimeException {

	public EntityNotFoundException(String message) {
		super(message);
	}
}
