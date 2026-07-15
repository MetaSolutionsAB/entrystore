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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.configuration.CacheControlFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.DefaultRedirectStrategy;

import java.io.IOException;

/**
 * Redirect strategy that stamps {@code Cache-Control: private, no-store} on the
 * response before delegating to {@link DefaultRedirectStrategy#sendRedirect}.
 * <p>
 * Used by the SAML, CAS and OIDC login-success handlers, where the 302 carrying the
 * session {@code Set-Cookie} header would otherwise commit the response before
 * {@code CacheControlFilter}'s post-chain check can run, leaving the response
 * with no {@code Cache-Control} and exposing the session cookie to caching by
 * a misconfigured intermediary (CWE-525). Yields to any {@code Cache-Control}
 * an earlier filter or handler has already set, matching the filter's contract.
 */
public final class CacheAwareRedirectStrategy extends DefaultRedirectStrategy {

	@Override
	public void sendRedirect(HttpServletRequest request, HttpServletResponse response, String url) throws IOException {
		if (!response.isCommitted() && response.getHeader(HttpHeaders.CACHE_CONTROL) == null) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControlFilter.CACHE_CONTROL_AUTHENTICATED);
		}
		super.sendRedirect(request, response, url);
	}
}
