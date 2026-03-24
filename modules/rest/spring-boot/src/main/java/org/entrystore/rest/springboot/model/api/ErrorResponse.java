package org.entrystore.rest.springboot.model.api;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record ErrorResponse(
	LocalDateTime timestamp,
	int status,
	String path,
	String error
) {
	public static class ErrorResponseBuilder {
		ErrorResponseBuilder() {
			timestamp = LocalDateTime.now();
		}
	}
}
