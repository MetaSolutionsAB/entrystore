package org.entrystore.rest.springboot.model.api;

import java.util.Map;

public record SetLoggingConfigRequestBody(
		String level,
		Map<String, String> packages
) {
}
