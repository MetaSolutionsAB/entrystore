package org.entrystore.rest.standalone.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.entrystore.rest.standalone.springboot.model.serializer.RawJsonDeserializer;

public record CreateEntryRequestBody(

	@JsonDeserialize(using = RawJsonDeserializer.class)
	String resource,

	@JsonDeserialize(using = RawJsonDeserializer.class)
	String metadata,

	@JsonProperty("cached-external-metadata")
	@JsonDeserialize(using = RawJsonDeserializer.class)
	String cachedExternalMetadata,

	@JsonDeserialize(using = RawJsonDeserializer.class)
	String info
) {
}
