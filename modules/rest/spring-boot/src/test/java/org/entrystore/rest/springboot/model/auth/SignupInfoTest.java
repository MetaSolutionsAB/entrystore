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

package org.entrystore.rest.springboot.model.auth;

import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupInfoTest {

	@Mock
	private RepositoryManager repositoryManager;

	@Mock
	private Config config;

	@BeforeEach
	void setUp() throws Exception {
		// Reset the static permittedBaseUrls field between tests
		Field field = SignupInfo.class.getDeclaredField("permittedBaseUrls");
		field.setAccessible(true);
		field.set(null, null);
	}

	private SignupInfo createSignupInfo(String baseUrl) throws Exception {
		return createSignupInfo(baseUrl, new ArrayList<>());
	}

	private SignupInfo createSignupInfo(String baseUrl, List<String> additionalPermitted) throws Exception {
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL(baseUrl));
		when(repositoryManager.getConfiguration()).thenReturn(config);
		when(config.getStringList(any(), any())).thenReturn(additionalPermitted);
		return new SignupInfo(repositoryManager);
	}

	@Test
	void isPermittedRedirectUrl_shouldAcceptUrlMatchingBaseUrl() throws Exception {
		createSignupInfo("http://localhost:8181/store/");

		assertTrue(SignupInfo.isPermittedRedirectUrl("http://localhost:8181/some/path"));
	}

	@Test
	void isPermittedRedirectUrl_shouldRejectExternalUrl() throws Exception {
		createSignupInfo("http://localhost:8181/store/");

		assertFalse(SignupInfo.isPermittedRedirectUrl("https://evil.com/phishing"));
	}

	@Test
	void isPermittedRedirectUrl_shouldRejectSimilarHostWithDifferentDomain() throws Exception {
		createSignupInfo("http://localhost:8181/store/");

		assertFalse(SignupInfo.isPermittedRedirectUrl("http://localhost:8181.evil.com/"));
	}

	@Test
	void isPermittedRedirectUrl_shouldRejectDifferentScheme() throws Exception {
		createSignupInfo("http://localhost:8181/store/");

		assertFalse(SignupInfo.isPermittedRedirectUrl("https://localhost:8181/path"));
	}

	@Test
	void isPermittedRedirectUrl_shouldAcceptConfiguredAdditionalUrl() throws Exception {
		createSignupInfo("http://localhost:8181/store/", List.of("https://frontend.example.com/"));

		assertTrue(SignupInfo.isPermittedRedirectUrl("https://frontend.example.com/callback"));
	}

	@Test
	void isPermittedRedirectUrl_shouldRejectUserinfoBypass() throws Exception {
		createSignupInfo("http://localhost:8181/store/");

		assertFalse(SignupInfo.isPermittedRedirectUrl("http://localhost:8181%40@evil.com/"));
	}

	@Test
	void isPermittedRedirectUrl_shouldRejectWhenNotInitialized() {
		// permittedBaseUrls is null (reset in setUp, no SignupInfo created)
		assertFalse(SignupInfo.isPermittedRedirectUrl("http://localhost:8181/path"));
	}

	@Test
	void setUrlSuccess_shouldStorePermittedUrl() throws Exception {
		SignupInfo info = createSignupInfo("http://localhost:8181/store/");

		info.setUrlSuccess("http://localhost:8181/success");

		assertEquals("http://localhost:8181/success", info.getUrlSuccess());
	}

	@Test
	void setUrlSuccess_shouldIgnoreNonPermittedUrl() throws Exception {
		SignupInfo info = createSignupInfo("http://localhost:8181/store/");

		info.setUrlSuccess("https://evil.com/phishing");

		assertNull(info.getUrlSuccess());
	}

	@Test
	void setUrlFailure_shouldStorePermittedUrl() throws Exception {
		SignupInfo info = createSignupInfo("http://localhost:8181/store/");

		info.setUrlFailure("http://localhost:8181/failure");

		assertEquals("http://localhost:8181/failure", info.getUrlFailure());
	}

	@Test
	void setUrlFailure_shouldIgnoreNonPermittedUrl() throws Exception {
		SignupInfo info = createSignupInfo("http://localhost:8181/store/");

		info.setUrlFailure("https://evil.com/phishing");

		assertNull(info.getUrlFailure());
	}
}
