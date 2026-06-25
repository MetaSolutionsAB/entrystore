package org.entrystore.rest.springboot.model.serializer;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class RawJsonDeserializer extends ValueDeserializer<String> {

	@Override
	public String deserialize(JsonParser parser, DeserializationContext ctx) {
		JsonNode node = parser.readValueAsTree();
		if (node.isString()) {
			// Already a plain string
			return node.asString();
		} else {
			// Serialize object/array to JSON string
			return node.toString();
		}
	}
}
