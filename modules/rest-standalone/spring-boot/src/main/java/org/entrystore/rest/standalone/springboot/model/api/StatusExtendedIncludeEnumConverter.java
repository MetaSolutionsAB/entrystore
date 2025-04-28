package org.entrystore.rest.standalone.springboot.model.api;

import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StatusExtendedIncludeEnumConverter implements Converter<String, StatusExtendedIncludeEnum> {

	@Override
	public StatusExtendedIncludeEnum convert(String input) {
		try {
			return StatusExtendedIncludeEnum.fromString(input);
		} catch (IllegalArgumentException ex) {
			throw new BadRequestException(ex.getMessage());
		}
	}

}
