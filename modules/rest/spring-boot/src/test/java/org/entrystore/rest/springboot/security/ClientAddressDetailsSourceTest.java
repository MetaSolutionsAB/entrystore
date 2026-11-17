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

package org.entrystore.rest.springboot.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientAddressDetailsSourceTest {

	@Test
	void requestWithASession_givesDetailsWithTheClientAddressButNoSessionId() {
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setRemoteAddr("192.0.2.1");
		request.getSession();

		var details = ClientAddressDetailsSource.INSTANCE.buildDetails(request);

		assertEquals("192.0.2.1", details.getRemoteAddress());
		assertNull(details.getSessionId());
	}
}
