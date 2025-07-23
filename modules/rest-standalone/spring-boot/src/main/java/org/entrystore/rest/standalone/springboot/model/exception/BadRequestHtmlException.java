package org.entrystore.rest.standalone.springboot.model.exception;

import lombok.Getter;
import lombok.Setter;

/**
 * A custom exception to be used to return 400 (BAD_REQUEST) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at debug level
 */
@Getter
@Setter
public class BadRequestHtmlException extends RuntimeException {

	private String title;

	public BadRequestHtmlException(String message, String title) {
		super(message);
		setTitle(title);
	}
}
