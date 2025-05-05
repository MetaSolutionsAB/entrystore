package org.entrystore.rest.standalone.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record GetEntryResponse(
	String entryId,
	String name,
	String quota,
	String info,
	@JsonProperty("cached-external-metadata") String cachedExternalMetadata,
	String metadata,
	String relations,
	String rights,
	String resource
) {
}
