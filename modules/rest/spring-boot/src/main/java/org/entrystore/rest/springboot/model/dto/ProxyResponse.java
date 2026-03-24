package org.entrystore.rest.springboot.model.dto;

public record ProxyResponse(int statusCode, String contentType, byte[] body) {

	public ProxyResponse {
		if (statusCode < 100 || statusCode > 599) {
			throw new IllegalArgumentException("HTTP status code must be between 100 and 599, got: " + statusCode);
		}
		if (body == null) {
			body = new byte[0];
		}
	}
}
