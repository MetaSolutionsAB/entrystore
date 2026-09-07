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

import org.entrystore.rest.springboot.model.api.MetadataType;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class MetadataTypeConverter implements Converter<String, MetadataType> {

	@Override
	public MetadataType convert(@NonNull String input) {
		MetadataType type = MetadataType.fromString(input);
		if (type == null) {
			throw new BadRequestException("Unable to convert given value of '" + input + "' to metadata type. "
					+ "Allowed values: " + Arrays.stream(MetadataType.values()).map(MetadataType::getKey).toList());
		}
		return type;
	}

}
