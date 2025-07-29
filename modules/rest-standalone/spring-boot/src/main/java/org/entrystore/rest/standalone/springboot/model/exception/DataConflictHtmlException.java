package org.entrystore.rest.standalone.springboot.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used in a service layer to indicate that the Controller layer should
 * return 409 (CLIENT_ERROR_CONFLICT). This unifies the behavior in ControllerAdvice (ExceptionHandler) across all services.
 * Expected behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at DEBUG level
 */
public class DataConflictHtmlException extends HtmlResponseException {

	public DataConflictHtmlException(String message, String title) {
		super(message, title, HttpStatus.CONFLICT);
	}
}
