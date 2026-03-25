package org.entrystore.rest.springboot.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return 417 (CLIENT_ERROR) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
public class ExpectationFailedHtmlException extends HtmlResponseException {

	public ExpectationFailedHtmlException(String message, String title) {
		super(message, title, HttpStatus.EXPECTATION_FAILED);
	}
}
