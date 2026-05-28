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

package org.entrystore.rest.springboot.service;

import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private Config esConfig;

	@Test
	void buildRequestUri_redactsSensitiveQueryParameters() throws Exception {
		// Verifies the redactor is wired into buildRequestUri so the Atom/RSS self-link
		// emitted by SearchService.searchFeed cannot echo a sensitive token back into a
		// publicly-visible response body. Regression sentinel for ENTRYSTORE-1009.
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, principalManager, esConfig);

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		request.setQueryString("q=hello&confirm=secret-token-abc");

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search?q=hello&confirm=***", uri);
		assertFalse(uri.contains("secret-token-abc"),
				"sensitive token must not appear in the constructed self-link: " + uri);
	}

	@Test
	void buildRequestUri_withNoQueryString_omitsQuestionMark() throws Exception {
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, principalManager, esConfig);

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		// no setQueryString — request.getQueryString() returns null

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search", uri);
	}

	@Test
	void buildRequestUri_withPercentEncodedSensitiveName_redactsValue() throws Exception {
		// Phishing-link bypass scenario from the round-1 review (F1): the controller binds
		// %63onfirm to its `confirm` @RequestParam, so the request flow runs with the real
		// token; the self-link must not echo the plaintext value either.
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, principalManager, esConfig);

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		request.setQueryString("%63onfirm=secret-token-abc");

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search?%63onfirm=***", uri);
	}
}
