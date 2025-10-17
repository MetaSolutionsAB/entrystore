package org.entrystore.rest.standalone.springboot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;

import java.util.HashMap;

public class JsonUtil {

	private static final ObjectMapper objectMapper = new ObjectMapper();
	static TypeReference<HashMap<String, String>> typeRef = new TypeReference<>() {
	};

	public static HashMap<String, String> jsonToMap(String json) {
		try {
			return objectMapper.readValue(json, typeRef);
		} catch (Exception e) {
			throw new BadRequestException("Bad request body. Couldn't parse json:" + json);
		}
	}
}
