package org.entrystore.rest.springboot.model.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Custom Jackson serializer to handle a case when a field sometimes is a raw json, but can be also just a String.
 * e.g. entry's "resource" field is usually a json, but for String entry it is just a String,
 * for json we need to call write as raw value (no escaping in double quotes), for String we need to escape it.
 * Without this String-entry "resource" is an invalid json, e.g. {"resource": Some text}, should be {"resource": "Some text"}
 *
 */
public class RawOrStringSerializer extends JsonSerializer<String> {

	@Override
	public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {

		if (value == null) {
			gen.writeNull();
			return;
		}

		String trimmed = value.trim();

		// Check if it looks like a JSON Object or Array
		if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
				(trimmed.startsWith("[") && trimmed.endsWith("]"))) {

			gen.writeRawValue(value);
		} else {
			// Write as a standard String (adds quotes and escapes characters)
			gen.writeString(value);
		}
	}
}
