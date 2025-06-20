package org.entrystore.rest.standalone.springboot.model.serializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class RawJsonDeserializer extends JsonDeserializer<String> {

	@Override
	public String deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
		JsonNode node = parser.readValueAsTree();
		if (node.isTextual()) {
			// Already a plain string
			return node.textValue();
		} else {
			// Serialize object/array to JSON string
			return node.toString();
		}
	}
}
