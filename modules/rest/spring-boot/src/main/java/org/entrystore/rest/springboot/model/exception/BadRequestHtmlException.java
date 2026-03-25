package org.entrystore.rest.springboot.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return 400 (BAD_REQUEST) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
public class BadRequestHtmlException extends HtmlResponseException {

	public BadRequestHtmlException(String message, String title) {
		super(message, title, HttpStatus.BAD_REQUEST);
	}
}
