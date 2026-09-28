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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/** Resolves SAML authentication callbacks and shares the legacy POST routes with CSRF configuration. */
@Component
public final class SamlAcsRequestMatcher implements RequestMatcher {

	private final String legacyPath;
	private final RequestMatcher modern = new OrRequestMatcher(
			PathPatternRequestMatcher.withDefaults().matcher("/login/saml2/sso/{registrationId}"),
			PathPatternRequestMatcher.withDefaults().matcher("/login/saml2/sso"));

	public SamlAcsRequestMatcher(
			@Value("${entrystore.auth.saml.assertion-consumer-service.url:}") String legacyAcsUrl) {
		legacyPath = legacyPath(legacyAcsUrl);
	}

	private static String legacyPath(String url) {
		try {
			return url.isBlank() ? null : URI.create(url).getPath();
		} catch (IllegalArgumentException ex) {
			// The compatibility processor validates active translations; modern overrides may leave an unused value.
			return null;
		}
	}

	public boolean isLegacy(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) return false;
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.equals("/auth/saml") || (legacyPath != null
				&& (request.getRequestURI().equals(legacyPath) || path.equals(legacyPath)));
	}

	@Override
	public boolean matches(HttpServletRequest request) {
		return matcher(request).isMatch();
	}

	@Override
	public MatchResult matcher(HttpServletRequest request) {
		if (!isLegacy(request)) return modern.matcher(request);
		String id = request.getParameter("idp");
		return MatchResult.match(id == null ? Map.of() : Map.of("registrationId", id));
	}
}
