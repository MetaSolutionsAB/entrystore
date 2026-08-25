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

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.PermittedRedirectsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectUrlValidatorTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	private RedirectUrlValidator createValidator(String baseUrl) throws Exception {
		return createValidator(baseUrl, List.of());
	}

	private RedirectUrlValidator createValidator(String baseUrl, List<String> additionalPermitted) throws Exception {
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create(baseUrl).toURL());
		Map<String, String> indexed = new LinkedHashMap<>();
		for (int i = 0; i < additionalPermitted.size(); i++) {
			indexed.put(String.valueOf(i + 1), additionalPermitted.get(i));
		}
		RedirectUrlValidator validator = new RedirectUrlValidator(repositoryManager,
				new PermittedRedirectsProperties(indexed));
		validator.init();
		return validator;
	}

	@Test
	void isPermitted_shouldAcceptUrlMatchingBaseUrl() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertTrue(validator.isPermitted("http://localhost:8181/some/path"));
	}

	@Test
	void isPermitted_shouldRejectExternalUrl() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("https://evil.com/phishing"));
	}

	@Test
	void isPermitted_shouldRejectSimilarHostWithDifferentDomain() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("http://localhost:8181.evil.com/"));
	}

	@Test
	void isPermitted_shouldRejectDifferentScheme() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("https://localhost:8181/path"));
	}

	@Test
	void isPermitted_shouldAcceptConfiguredAdditionalUrl() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/", List.of("https://frontend.example.com/"));

		assertTrue(validator.isPermitted("https://frontend.example.com/callback"));
	}

	@Test
	void isPermitted_shouldRejectUserinfoBypass() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("http://localhost:8181%40@evil.com/"));
	}

	@Test
	void isPermitted_shouldRejectMalformedUri() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("http://[invalid"));
	}

	@Test
	void isPermitted_shouldRejectDifferentPort() throws Exception {
		RedirectUrlValidator validator = createValidator("http://localhost:8181/store/");

		assertFalse(validator.isPermitted("http://localhost:9999/path"));
	}

	@Test
	void isPermitted_shouldAcceptMultiSegmentBasePath() throws Exception {
		RedirectUrlValidator validator = createValidator("http://example.com/app/v2/");

		assertTrue(validator.isPermitted("http://example.com/app/v2/callback"));
	}

}
