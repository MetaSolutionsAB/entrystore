package org.entrystore.rest.springboot.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return 404 (NOT_FOUND) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as an exception message and 404 as a code
 * - exception message logged in the logs at debug level
 */
public class PwResetEntityNotFoundHtmlException extends HtmlResponseException {

	public PwResetEntityNotFoundHtmlException(String message, String title) {

		super(message, title, HttpStatus.NOT_FOUND);
	}
}
