package org.entrystore.rest.standalone.springboot.model.exception;

/**
 * A custom exception to be used to return 500 (INTERNAL_SERVER_ERROR) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
public class InternalServerErrorException extends RuntimeException {

	public InternalServerErrorException(String message) {
		super(message);
	}
}
