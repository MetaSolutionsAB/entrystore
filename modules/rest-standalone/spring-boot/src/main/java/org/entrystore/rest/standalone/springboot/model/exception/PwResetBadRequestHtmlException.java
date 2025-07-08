package org.entrystore.rest.standalone.springboot.model.exception;

/**
 * A custom exception to be used to return 400 (BAD_REQUEST) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
public class PwResetBadRequestHtmlException extends RuntimeException {

	public PwResetBadRequestHtmlException(String message) {
		super(message);
	}
}
