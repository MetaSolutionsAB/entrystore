package org.entrystore.rest.standalone.springboot.model.exception;

import lombok.Getter;

import java.net.URI;

/**
 * A custom exception to be used to return 303 (SEE_OTHER) by the service and unify the behaviour in ControllerAdvice (ExceptionHandler).
 * The expected behaviour:
 * - given url set as redirect location header
 * - 303 HTTP status code returned
 */
@Getter
public class RedirectSeeOtherException extends RuntimeException {

	private final URI location;

	public RedirectSeeOtherException(URI location) {
		super(location.toString());
		this.location = location;
	}
}
