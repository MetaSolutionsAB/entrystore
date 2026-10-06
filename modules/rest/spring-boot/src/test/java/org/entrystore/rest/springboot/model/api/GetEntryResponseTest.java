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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetEntryResponseTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"[draft] a [b]", "{\"a\":1}", "[1,2]", "plain", "say \"hi\"\n"})
	void textResource_isWrittenAsTheExactJsonString(String text) {
		var response = GetEntryResponse.builder().entryId("1").resource(new EntryResourceJson.Text(text)).build();

		JsonNode resource = mapper.readTree(mapper.writeValueAsString(response)).get("resource");

		assertTrue(resource.isString());
		assertEquals(text, resource.asString());
	}

	@Test
	void jsonResource_isEmbeddedAsJson() {
		var response = GetEntryResponse.builder().entryId("1")
				.resource(new EntryResourceJson.Json("{\"children\": [\"2\", \"3\"]}")).build();

		JsonNode resource = mapper.readTree(mapper.writeValueAsString(response)).get("resource");

		assertEquals(mapper.readTree("{\"children\": [\"2\", \"3\"]}"), resource);
	}
}
