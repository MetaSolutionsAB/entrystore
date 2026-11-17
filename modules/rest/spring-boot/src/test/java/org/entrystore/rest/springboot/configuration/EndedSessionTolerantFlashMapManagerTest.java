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
import jakarta.servlet.http.HttpSession;
import org.entrystore.rest.springboot.util.JettySessionExceptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;
import org.springframework.web.util.WebUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndedSessionTolerantFlashMapManagerTest {

	private static final String FLASH_MAPS = SessionFlashMapManager.class.getName() + ".FLASH_MAPS";

	private final EndedSessionTolerantFlashMapManager manager = new EndedSessionTolerantFlashMapManager();

	@Test
	void sessionThatAConcurrentRequestHasEnded_hasNoFlashMaps() {
		var request = requestWhoseSessionThrowsOnRead(JettySessionExceptions.readOfInvalidatedSession());

		assertNull(manager.retrieveAndUpdate(request, new MockHttpServletResponse()));
	}

	@Test
	void otherFailureOfTheSession_propagates() {
		var failure = new IllegalStateException("other");
		var request = requestWhoseSessionThrowsOnRead(failure);

		var thrown = assertThrows(IllegalStateException.class,
				() -> manager.retrieveAndUpdate(request, new MockHttpServletResponse()));

		assertSame(failure, thrown);
	}

	@Test
	void flashMapOfASessionThatHasNotEnded_isStillRetrieved() {
		var flashMap = new FlashMap();
		flashMap.put("message", "saved");
		var session = new MockHttpSession();
		session.setAttribute(FLASH_MAPS, new ArrayList<>(List.of(flashMap)));
		var request = new MockHttpServletRequest("GET", "/");
		request.setSession(session);

		assertEquals(flashMap, manager.retrieveAndUpdate(request, new MockHttpServletResponse()));
	}

	@Test
	void sessionEndedBeforeItsMutexIsRead_stillGivesTheFlashMap() {
		var flashMap = new FlashMap();
		var session = sessionHolding(flashMap);
		when(session.getAttribute(WebUtils.SESSION_MUTEX_ATTRIBUTE))
				.thenAnswer(JettySessionExceptions.throwing(JettySessionExceptions.readOfInvalidatedSession()));

		assertSame(flashMap, manager.retrieveAndUpdate(requestWith(session), new MockHttpServletResponse()));
	}

	@Test
	void otherFailureOfReadingTheMutex_propagates() {
		var failure = new IllegalStateException("other");
		var session = sessionHolding(new FlashMap());
		when(session.getAttribute(WebUtils.SESSION_MUTEX_ATTRIBUTE)).thenThrow(failure);
		var request = requestWith(session);

		var thrown = assertThrows(IllegalStateException.class,
				() -> manager.retrieveAndUpdate(request, new MockHttpServletResponse()));

		assertSame(failure, thrown);
	}

	@Test
	void sessionEndedBeforeItsFlashMapsAreUpdated_stillGivesTheFlashMap() {
		var flashMap = new FlashMap();
		var session = sessionHolding(flashMap);
		doAnswer(JettySessionExceptions.throwing(JettySessionExceptions.writeOfInvalidatedSession()))
				.when(session).removeAttribute(FLASH_MAPS);

		assertSame(flashMap, manager.retrieveAndUpdate(requestWith(session), new MockHttpServletResponse()));
	}

	@Test
	void otherFailureOfUpdatingTheFlashMaps_propagates() {
		var failure = new IllegalStateException("other");
		var session = sessionHolding(new FlashMap());
		doThrow(failure).when(session).removeAttribute(FLASH_MAPS);
		var request = requestWith(session);

		var thrown = assertThrows(IllegalStateException.class,
				() -> manager.retrieveAndUpdate(request, new MockHttpServletResponse()));

		assertSame(failure, thrown);
	}

	/** @return a session holding the flash map, which matches every request and is removed once retrieved */
	private static HttpSession sessionHolding(FlashMap flashMap) {
		var session = mock(HttpSession.class);
		when(session.getAttribute(FLASH_MAPS)).thenReturn(new ArrayList<>(List.of(flashMap)));
		return session;
	}

	private static MockHttpServletRequest requestWith(HttpSession session) {
		var request = new MockHttpServletRequest("GET", "/");
		request.setSession(session);
		return request;
	}

	/** @return a request that obtained its session before the session failed */
	private static HttpServletRequest requestWhoseSessionThrowsOnRead(IllegalStateException failure) {
		var session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(JettySessionExceptions.throwing(failure));
		var request = mock(HttpServletRequest.class);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}
}
