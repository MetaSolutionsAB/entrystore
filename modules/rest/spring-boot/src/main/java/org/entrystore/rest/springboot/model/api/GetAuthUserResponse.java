package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetAuthUserResponse(
		String id,
		@JsonProperty("homecontext") String homeContext,
		String user,
		String uri,
		String language,
		Map<String, Double> clientAcceptLanguage,
		@JsonProperty("external-id") String externalId,
		LocalDateTime authTokenExpires
) {
}
