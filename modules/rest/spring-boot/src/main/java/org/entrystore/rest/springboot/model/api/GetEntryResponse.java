package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import tools.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import org.entrystore.rest.springboot.model.serializer.RawOrStringSerializer;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetEntryResponse(
		String entryId,
		String name,

		@JsonRawValue String quota,

		@JsonRawValue String info,

		@JsonProperty("cached-external-metadata") @JsonRawValue String cachedExternalMetadata,

		@JsonRawValue String metadata,
		@JsonRawValue String relations,
		@JsonRawValue String rights,

		@JsonSerialize(using = RawOrStringSerializer.class) String resource
) {
}
