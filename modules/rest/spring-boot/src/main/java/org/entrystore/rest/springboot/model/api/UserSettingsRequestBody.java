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

package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Body of a user-resource update: every member is an independent, optional instruction.
 *
 * <p>Components are {@link JsonNode} rather than {@code String} because this endpoint distinguishes
 * three states per member, and only a node carries all three: <em>absent</em> arrives as {@code null}
 * and leaves the setting alone, an explicit {@code null} arrives as a null node and is a malformed
 * instruction, and a value of the wrong JSON type is likewise malformed. {@code Optional<String>}
 * cannot express this — Jackson resolves both absent and explicit-null to {@code Optional.empty()},
 * so a caller sending {@code "language": null} would be indistinguishable from one that never
 * mentioned the field, silently clearing nothing instead of reporting a bad body.
 *
 * <p>A member that is present but not of the expected type is rejected with 400; only an absent member
 * leaves the setting untouched. The accessors below apply that rule once per type, so callers read a
 * member and get either a usable value or a rejection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSettingsRequestBody(
		@JsonProperty("name") JsonNode name,
		@JsonProperty("password") JsonNode password,
		@JsonProperty("currentPassword") JsonNode currentPassword,
		@JsonProperty("language") JsonNode language,
		@JsonProperty("homecontext") JsonNode homeContext,
		@JsonProperty("disabled") JsonNode disabled,
		@JsonProperty("customProperties") JsonNode customProperties) {

	/**
	 * Message for every malformed member. Client-visible wire string that existing clients and the
	 * resource integration tests assert verbatim — do not reword.
	 */
	public static final String SYNTAX_ERROR_MESSAGE = "Error in JSON syntax";

	public boolean hasName() {
		return name != null;
	}

	public boolean hasPassword() {
		return password != null;
	}

	public boolean hasCurrentPassword() {
		return currentPassword != null;
	}

	public boolean hasLanguage() {
		return language != null;
	}

	public boolean hasHomeContext() {
		return homeContext != null;
	}

	public boolean hasDisabled() {
		return disabled != null;
	}

	public boolean hasCustomProperties() {
		return customProperties != null;
	}

	public String nameValue() {
		return stringValue(name);
	}

	public String passwordValue() {
		return stringValue(password);
	}

	public String currentPasswordValue() {
		return stringValue(currentPassword);
	}

	public String languageValue() {
		return stringValue(language);
	}

	public String homeContextValue() {
		return stringValue(homeContext);
	}

	/**
	 * Wire contract: a JSON boolean, or the strings {@code "true"} / {@code "false"} case-insensitively.
	 * The string form is not a hypothetical — clients send {@code "disabled": "true"} and expect the
	 * account to end up disabled, so accepting only a JSON boolean turns those requests into silent
	 * no-ops.
	 *
	 * <p>Anything else is rejected rather than resolved to false. That is stricter than the
	 * {@code optBoolean("disabled", false)} this replaced, deliberately: this member is a security
	 * control, and defaulting a malformed value to false answers 200 UPDATED while leaving an account
	 * enabled — or, against an already-disabled account, silently re-enabling it.
	 */
	public boolean disabledValue() {
		if (disabled == null) {
			return false;
		}
		if (disabled.isBoolean()) {
			return disabled.booleanValue();
		}
		if (disabled.isString() && ("true".equalsIgnoreCase(disabled.stringValue())
				|| "false".equalsIgnoreCase(disabled.stringValue()))) {
			return Boolean.parseBoolean(disabled.stringValue());
		}
		throw new BadRequestException(SYNTAX_ERROR_MESSAGE);
	}

	/** Requires a JSON object whose values are all strings. */
	public Map<String, String> customPropertiesValue() {
		if (customProperties == null || !customProperties.isObject()) {
			throw new BadRequestException(SYNTAX_ERROR_MESSAGE);
		}
		Map<String, String> properties = new LinkedHashMap<>();
		customProperties.propertyStream()
				.forEach(property -> properties.put(property.getKey(), stringValue(property.getValue())));
		return properties;
	}

	/**
	 * Strict on purpose: a number or boolean where a string belongs is a client mistake, and coercing it
	 * would let {@code "language": 5} be stored as the language tag {@code "5"}.
	 */
	private static String stringValue(JsonNode node) {
		if (node == null || !node.isString()) {
			throw new BadRequestException(SYNTAX_ERROR_MESSAGE);
		}
		return node.stringValue();
	}
}
