package org.entrystore.rest.standalone.springboot.model.api;

import org.entrystore.EntryType;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EntryTypeEnumConverter implements Converter<String, EntryType> {

	// Cache map of lower-case Enum name to Enum Value - e.g. Map.of("reference", EntryType.Reference)
	private static final Map<String, EntryType> ENUM_MAP = Arrays.stream(EntryType.values())
		.collect(Collectors.toMap(
			e -> e.name().toLowerCase(),
			Function.identity()));

	@Override
	public EntryType convert(String input) {
		EntryType result = ENUM_MAP.get(input.toLowerCase());
		if (result == null) {
			throw new BadRequestException("Unknown value for EntryType: '" + input + "'. Allowed values: " + ENUM_MAP.keySet());
		}
		return result;
	}
}
