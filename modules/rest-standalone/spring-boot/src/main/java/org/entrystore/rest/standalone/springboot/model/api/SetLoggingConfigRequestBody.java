package org.entrystore.rest.standalone.springboot.model.api;

import java.util.Map;

public record SetLoggingConfigRequestBody(
		String level,
		Map<String, String> packages
) {
}
