package org.entrystore.rest.standalone.springboot.model.api;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record ErrorResponse(
	LocalDateTime timestamp,
	int status,
	String path,
	String errors
) {
	public static class ErrorResponseBuilder {
		ErrorResponseBuilder() {
			timestamp = LocalDateTime.now();
		}
	}
}
