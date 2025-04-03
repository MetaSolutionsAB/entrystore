package org.entrystore.rest.standalone.springboot.model.api;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StatusExtendedIncludeEnumConverter implements Converter<String, StatusExtendedIncludeEnum> {

	@Override
	public StatusExtendedIncludeEnum convert(String source) {
		return StatusExtendedIncludeEnum.fromString(source);
	}

}
