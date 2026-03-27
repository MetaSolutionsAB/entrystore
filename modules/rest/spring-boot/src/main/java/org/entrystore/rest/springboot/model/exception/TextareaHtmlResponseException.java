package org.entrystore.rest.springboot.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used for scenarios where an HTML textarea response is required.
 * Exception handler should use the class fields to populate the response using Thymeleaf "textarea" template.
 * The expected behaviour:
 * - exception message logged in the logs at debug level
 * - "message" and "status" fields are populated in the "textarea" template
 * - given HTTP status code is returned by the endpoint
 */
@Getter
public class TextareaHtmlResponseException extends RuntimeException {

	private final HttpStatus status;

	public TextareaHtmlResponseException(String message, HttpStatus status) {
		super(message);
		this.status = status;
	}

	public TextareaHtmlResponseException(String message, HttpStatus status, Throwable cause) {
		super(message, cause);
		this.status = status;
	}
}
