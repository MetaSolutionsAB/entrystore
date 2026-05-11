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

package org.entrystore.rest.springboot.util;

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceJsonSerializerTest {

	@Mock
	private PrincipalManager pm;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private LoginAttemptService loginAttemptService;

	@Mock
	private User user;

	private ResourceJsonSerializer serializer;

	@BeforeEach
	void setUp() {
		serializer = new ResourceJsonSerializer(pm, repositoryManager, loginAttemptService);
		when(user.getCustomProperties()).thenReturn(Map.of());
	}

	@Test
	void serializeResourceUser_lockedOut_includesDisabledUntilAndOmitsDisabled() {
		Instant lockedUntil = Instant.parse("2026-05-11T10:30:00Z");
		when(user.getName()).thenReturn("alice");
		when(loginAttemptService.getLockedUntil("alice")).thenReturn(lockedUntil);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabledUntil"));
		// Round-trip through the JSON wire format to pin the ISO-8601 string clients consume.
		JSONObject roundTripped = new JSONObject(result.toString());
		assertEquals(lockedUntil.toString(), roundTripped.getString("disabledUntil"));
		assertFalse(result.has("disabled"));
	}

	@Test
	void serializeResourceUser_notLockedOut_omitsDisabledUntil() {
		when(user.getName()).thenReturn("bob");
		when(loginAttemptService.getLockedUntil("bob")).thenReturn(null);

		JSONObject result = serializer.serializeResourceUser(user);

		assertFalse(result.has("disabledUntil"));
	}

	@Test
	void serializeResourceUser_looksUpLockoutWithLowercasedUsername() {
		Instant lockedUntilForLowercase = Instant.parse("2026-05-11T10:30:00Z");
		when(user.getName()).thenReturn("Alice");
		// Stub ONLY the lowercase key — a regression dropping .toLowerCase() would call
		// getLockedUntil("Alice"), miss the stub, get the default null, and omit the field.
		when(loginAttemptService.getLockedUntil("alice")).thenReturn(lockedUntilForLowercase);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabledUntil"));
		assertEquals(lockedUntilForLowercase.toString(), new JSONObject(result.toString()).getString("disabledUntil"));
	}

	@Test
	void serializeResourceUser_disabledFlagIndependentOfDisabledUntil() {
		when(user.getName()).thenReturn("charlie");
		when(user.isDisabled()).thenReturn(true);
		when(loginAttemptService.getLockedUntil("charlie")).thenReturn(null);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabled"));
		assertEquals(true, result.get("disabled"));
		assertFalse(result.has("disabledUntil"));
	}
}
