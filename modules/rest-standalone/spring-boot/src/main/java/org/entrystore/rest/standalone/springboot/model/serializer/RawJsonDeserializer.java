package org.entrystore.rest.standalone.springboot.model.serializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class RawJsonDeserializer extends JsonDeserializer<String> {

	@Override
	public String deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
		return parser.getCodec().readTree(parser).toString(); // keep raw JSON string
	}
}
