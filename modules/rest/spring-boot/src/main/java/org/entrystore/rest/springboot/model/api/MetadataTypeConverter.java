package org.entrystore.rest.springboot.model.api;

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
			throw new BadRequestException("Unable to convert given value of '" + input + "' to metadata type. Allowed values: "
				+ Arrays.stream(MetadataType.values()).map(MetadataType::getKey).toList());
		}
		return type;
	}

}
