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

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndedSessionTolerantFlashMapManagerTest {

	private final EndedSessionTolerantFlashMapManager manager = new EndedSessionTolerantFlashMapManager();

	@Test
	void sessionThatAConcurrentRequestHasEnded_hasNoFlashMaps() {
		var endedSession = new MockHttpSession();
		endedSession.invalidate();
		// the request obtained the session before the concurrent request ended it
		var request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(endedSession);

		assertNull(manager.retrieveAndUpdate(request, new MockHttpServletResponse()));
	}

	@Test
	void flashMapOfASessionThatHasNotEnded_isStillRetrieved() {
		var flashMap = new FlashMap();
		flashMap.put("message", "saved");
		var session = new MockHttpSession();
		session.setAttribute(SessionFlashMapManager.class.getName() + ".FLASH_MAPS", new ArrayList<>(List.of(flashMap)));
		var request = new MockHttpServletRequest("GET", "/");
		request.setSession(session);

		assertEquals(flashMap, manager.retrieveAndUpdate(request, new MockHttpServletResponse()));
	}
}
