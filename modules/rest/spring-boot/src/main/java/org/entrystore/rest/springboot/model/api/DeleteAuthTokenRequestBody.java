package org.entrystore.rest.springboot.model.api;

import jakarta.validation.constraints.NotBlank;

public record DeleteAuthTokenRequestBody(
		@NotBlank String token
		) {
}
