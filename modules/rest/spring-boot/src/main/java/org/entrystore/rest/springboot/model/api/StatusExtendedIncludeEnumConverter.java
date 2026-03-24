package org.entrystore.rest.springboot.model.api;

import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StatusExtendedIncludeEnumConverter implements Converter<String, StatusExtendedIncludeEnum> {

	@Override
	public StatusExtendedIncludeEnum convert(@NonNull String input) {
		try {
			return StatusExtendedIncludeEnum.fromString(input);
		} catch (IllegalArgumentException ex) {
			throw new BadRequestException(ex.getMessage());
		}
	}

}
