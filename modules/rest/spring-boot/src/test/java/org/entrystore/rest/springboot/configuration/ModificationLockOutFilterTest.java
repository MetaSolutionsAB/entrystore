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

import org.entrystore.repository.RepositoryManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModificationLockOutFilterTest {

	private final ModificationLockOutFilter filter;

	ModificationLockOutFilterTest() {
		RepositoryManager repositoryManager = mock(RepositoryManager.class);
		when(repositoryManager.hasModificationLockOut()).thenReturn(true);
		filter = new ModificationLockOutFilter(repositoryManager);
	}

	@Test
	void fiveXSamlAcsPost_isAllowedDuringLockout() throws Exception {
		var chain = new MockFilterChain();

		filter.doFilter(post("/auth/saml"), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest(), "an in-flight SSO login must be able to complete during maintenance");
	}

	@Test
	void otherPost_isRejectedDuringLockout() throws Exception {
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(post("/_principals/groups"), response, chain);

		assertNull(chain.getRequest());
		assertEquals(503, response.getStatus());
	}

	private static MockHttpServletRequest post(String servletPath) {
		var request = new MockHttpServletRequest("POST", "/store" + servletPath);
		request.setContextPath("/store");
		request.setServletPath(servletPath);
		return request;
	}
}
