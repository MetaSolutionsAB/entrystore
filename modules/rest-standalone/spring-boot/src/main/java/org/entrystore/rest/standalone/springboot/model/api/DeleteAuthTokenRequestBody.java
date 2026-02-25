package org.entrystore.rest.standalone.springboot.model.api;

import jakarta.validation.constraints.NotBlank;

public record DeleteAuthTokenRequestBody(
		@NotBlank String token
		) {
}
