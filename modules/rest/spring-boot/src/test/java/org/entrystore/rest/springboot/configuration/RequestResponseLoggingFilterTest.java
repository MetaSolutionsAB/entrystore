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

package org.entrystore.rest.springboot.configuration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestResponseLoggingFilterTest {

	private final RequestResponseLoggingFilter filter = new RequestResponseLoggingFilter();

	@ParameterizedTest(name = "shouldNotFilter({0}) == {1}")
	@CsvSource({
			"/favicon.ico,       true",
			"/management,        false",
			"/management/,       false",
			"/management/metrics,false",
			"/management/shutdown,false",
			"/actuator/health,   false",
			"/actuator,          false",
			"/store/entry/1,     false",
			"/search,            false",
			"/auth/login,        false",
			"/,                  false"
	})
	void shouldNotFilter_matchesManagementAndFaviconOnly(String pathString, boolean expected) {
		var request = new MockHttpServletRequest();
		request.setServletPath(pathString);

		assertEquals(expected, filter.shouldNotFilter(request));
	}
}
