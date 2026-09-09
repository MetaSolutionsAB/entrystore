/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.model.api.converter;

import org.entrystore.EntryType;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
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
