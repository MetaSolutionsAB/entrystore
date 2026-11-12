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

import jakarta.servlet.http.HttpSession;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoSessionInfosTest {

	// A login lasts 3600 s from the last request
	private final AuthTokenCookies authTokenCookies = AuthTokenCookiesTest.authTokenCookies("3600", true);
	private final SsoSessionInfos sessionInfos = new SsoSessionInfos(authTokenCookies);

	@Test
	void recordLogin_recordsTheLoginUnderTheSessionId() {
		MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
		authTokenCookies.applySessionLifetime(login, null);

		sessionInfos.recordLogin(login, "Alice@Example.org");

		SessionInfo recorded = sessionInfos.get(login.getSession().getId());
		// lower-cased, as a form login records it
		assertEquals("alice@example.org", recorded.userName());
		assertEquals("192.0.2.1", recorded.lastUsedIpAddress());
		assertEquals("Browser/1", recorded.lastUsedUserAgent());
		assertEquals(3600, recorded.loginTokenMaxAge());
		assertNotNull(recorded.loginTime());
		assertEquals(recorded.loginTime(), recorded.lastAccessTime());
		assertTrue(recorded.loginExpiration().isAfter(recorded.loginTime()));
	}

	@Test
	void refresh_recordedSession_reportsTheRequestAndKeepsTheLogin() {
		MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
		authTokenCookies.applySessionLifetime(login, null);
		sessionInfos.recordLogin(login, "alice");
		HttpSession session = login.getSession();
		SessionInfo atLogin = sessionInfos.get(session.getId());
		MockHttpServletRequest later = requestFrom("192.0.2.2", "Browser/2");
		later.setSession(session);
		session.setMaxInactiveInterval(60);

		sessionInfos.refresh(later);

		SessionInfo refreshed = sessionInfos.get(session.getId());
		assertEquals("192.0.2.2", refreshed.lastUsedIpAddress());
		assertEquals("Browser/2", refreshed.lastUsedUserAgent());
		assertEquals(60, refreshed.loginTokenMaxAge());
		// the login now ends 60 s, not 3600 s, after the last request
		assertTrue(refreshed.loginExpiration().isBefore(atLogin.loginExpiration()));
		assertEquals("alice", refreshed.userName());
		assertEquals(atLogin.loginTime(), refreshed.loginTime());
	}

	@Test
	void refresh_sessionWithoutRecordedInfo_recordsNone() {
		// e.g. the session of a form login, whose info its principal carries
		var request = new MockHttpServletRequest();
		String sessionId = request.getSession().getId();

		sessionInfos.refresh(request);

		assertNull(sessionInfos.get(sessionId));
	}

	@Test
	void refresh_requestWithoutSession_createsNone() {
		var request = new MockHttpServletRequest();

		sessionInfos.refresh(request);

		assertNull(request.getSession(false));
	}

	@Test
	void refresh_sessionEndedByAConcurrentRequest_isIgnored() {
		// With a fixed login end the expiry is a session attribute, which an invalidated session refuses to read
		var fixedLifetime = new SsoSessionInfos(AuthTokenCookiesTest.authTokenCookies("3600", false));
		var session = new MockHttpSession();
		session.invalidate();
		// the session was looked up before the concurrent request invalidated it
		var racingRequest = new MockHttpServletRequest() {
			@Override
			public HttpSession getSession(boolean create) {
				return session;
			}
		};

		assertDoesNotThrow(() -> fixedLifetime.refresh(racingRequest));
	}

	@Test
	void sessionDestroyed_removesItsInfo() {
		MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
		sessionInfos.recordLogin(login, "alice");
		HttpSession session = login.getSession();

		sessionInfos.onSessionDestroyed(new HttpSessionDestroyedEvent(session));

		assertNull(sessionInfos.get(session.getId()));
	}

	@Test
	void sessionIdChanged_removesTheInfoOfTheOldId() {
		// A second login on the same session: its id changes before the new login is recorded
		MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
		sessionInfos.recordLogin(login, "alice");
		String oldSessionId = login.getSession().getId();
		login.changeSessionId();

		sessionInfos.onSessionIdChanged(new HttpSessionIdChangedEvent(login.getSession(), oldSessionId));

		assertNull(sessionInfos.get(oldSessionId));
		assertNull(sessionInfos.get(login.getSession().getId()));
	}

	@Test
	void sessionDestroyedEventPublishedInTheContext_removesItsInfo() {
		contextWithSessionInfos().run(context -> {
			SsoSessionInfos registered = context.getBean(SsoSessionInfos.class);
			MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
			registered.recordLogin(login, "alice");
			HttpSession session = login.getSession();

			context.publishEvent(new HttpSessionDestroyedEvent(session));

			assertNull(registered.get(session.getId()));
		});
	}

	@Test
	void sessionIdChangedEventPublishedInTheContext_removesTheInfoOfTheOldId() {
		contextWithSessionInfos().run(context -> {
			SsoSessionInfos registered = context.getBean(SsoSessionInfos.class);
			MockHttpServletRequest login = requestFrom("192.0.2.1", "Browser/1");
			registered.recordLogin(login, "alice");
			String oldSessionId = login.getSession().getId();
			login.changeSessionId();

			context.publishEvent(new HttpSessionIdChangedEvent(login.getSession(), oldSessionId));

			assertNull(registered.get(oldSessionId));
		});
	}

	/** A context that delivers published session events to the bean's listeners, as the application's does. */
	private ApplicationContextRunner contextWithSessionInfos() {
		return new ApplicationContextRunner()
				.withBean(AuthTokenCookies.class, () -> authTokenCookies)
				.withUserConfiguration(SsoSessionInfos.class);
	}

	private static MockHttpServletRequest requestFrom(String address, String userAgent) {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr(address);
		request.addHeader("User-Agent", userAgent);
		return request;
	}
}
