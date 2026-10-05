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

package org.entrystore.transforms.rowstore;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class CSV2RowStoreTransformTest {

	static Stream<Arguments> aliases() {
		return Stream.of(
				arguments("plain alias", "my-alias", "[\"my-alias\"]"),
				arguments("quote", "a\"b", "[\"a\\\"b\"]"),
				arguments("backslash", "a\\b", "[\"a\\\\b\"]"),
				arguments("newline and tab", "a\nb\tc", "[\"a\\u000ab\\u0009c\"]"),
				arguments("non-ASCII kept as is", "åäö-日本", "[\"åäö-日本\"]"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("aliases")
	void toJsonArrayEscapesAliasAsJsonString(String description, String alias, String expectedJson) {
		assertEquals(expectedJson, CSV2RowStoreTransform.toJsonArray(alias));
	}

	@ParameterizedTest(name = "{0} vs {1} -> {2}")
	@CsvSource({
			"https://dev.entryscape.com/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, true",
			"HTTPS://Dev.EntryScape.com/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, true",
			"https://dev.entryscape.com:443/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, true",
			"http://rowstore:80/datasets/abc, http://rowstore/, true",
			"http://dev.entryscape.com/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"https://dev.entryscape.com:8443/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"https://169.254.169.254/latest/meta-data, https://dev.entryscape.com/rowstore/, false",
			"https://dev.entryscape.com@evil.example.com/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"https://dev.entryscape.com.evil.example.com/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"https://dev.entryscape.com/other/path, https://dev.entryscape.com/rowstore/, true",
			"//evil.example.com/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"https://dév.entryscape.com/rowstore/datasets/abc, https://dev.entryscape.com/rowstore/, false",
			"http://[::1]:8282/datasets/abc, http://localhost:8282/, false"
	})
	void isSameOriginComparesSchemeHostAndPort(String datasetUrl, String rowstoreUrl, boolean expected) {
		assertEquals(expected, CSV2RowStoreTransform.isSameOrigin(URI.create(datasetUrl), URI.create(rowstoreUrl)));
	}
}
