package org.entrystore.rest.standalone.springboot.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An exception to be used to return HTML response by the service using "auth" template.
 */
@Getter
public class HtmlResponseException extends RuntimeException {

	private final String title;
	private final HttpStatus status;

	public HtmlResponseException(String message, String title, HttpStatus status) {
		super(message);
		this.title = title;
		this.status = status;
	}
}
