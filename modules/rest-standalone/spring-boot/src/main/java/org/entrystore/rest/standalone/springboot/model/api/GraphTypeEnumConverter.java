package org.entrystore.rest.standalone.springboot.model.api;

import org.entrystore.GraphType;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class GraphTypeEnumConverter implements Converter<String, GraphType> {

	// Cache map of lower-case Enum name to Enum Value - e.g. Map.of("context", GraphType.Context)
	private static final Map<String, GraphType> ENUM_MAP = Arrays.stream(GraphType.values())
		.collect(Collectors.toMap(
			e -> e.name().toLowerCase(),
			Function.identity()));

	@Override
	public GraphType convert(String input) {
		GraphType result = ENUM_MAP.get(input.toLowerCase());
		if (result == null) {
			throw new BadRequestException("Unknown value for GraphType: '" + input + "'. Allowed values: " + ENUM_MAP.keySet());
		}
		return result;
	}
}
