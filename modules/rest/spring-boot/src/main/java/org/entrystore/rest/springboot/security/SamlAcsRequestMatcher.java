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
import org.springframework.http.HttpMethod;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Matches the requests that deliver a SAML response: Spring's {@code /login/saml2/sso/{registrationId}} and
 * {@code /login/saml2/sso}, and a POST of a {@code SAMLResponse} to {@code /auth/saml}, the assertion consumer
 * service of EntryStore 5.x that IdPs configured for 5.x still post to. The 5.x URL names the IdP in its
 * {@code idp} query parameter, which is exposed as the same {@code registrationId} variable the Spring path
 * carries, so the token converter resolves the registration from either form.
 *
 * <p>Used as both the processing matcher of {@code Saml2WebSsoAuthenticationFilter} and the converter's
 * matcher; {@link #matcher} must return the delegate's result, since the converter reads the variable from it.
 * Because it replaces the filter's matcher, a {@code loginProcessingUrl} set on the SAML login configurer has no
 * effect. A GET of {@code /auth/saml} is not matched: it starts a login in {@code AuthController}.
 */
public final class SamlAcsRequestMatcher implements RequestMatcher {

	/** The 5.x assertion consumer service path, relative to the context path. */
	public static final String LEGACY_ACS_PATH = "/auth/saml";
	/** The query parameter of the 5.x assertion consumer service URL that names the IdP. */
	public static final String LEGACY_IDP_PARAMETER = "idp";

	private final RequestMatcher delegate;

	public SamlAcsRequestMatcher() {
		var paths = PathPatternRequestMatcher.withDefaults();
		this.delegate = new OrRequestMatcher(
				paths.matcher(Saml2WebSsoAuthenticationFilter.DEFAULT_FILTER_PROCESSES_URI),
				paths.matcher("/login/saml2/sso"),
				new LegacyAcsRequestMatcher(paths.matcher(HttpMethod.POST, LEGACY_ACS_PATH)));
	}

	@Override
	public boolean matches(HttpServletRequest request) {
		return delegate.matches(request);
	}

	@Override
	public MatchResult matcher(HttpServletRequest request) {
		return delegate.matcher(request);
	}

	private record LegacyAcsRequestMatcher(RequestMatcher postToAcsPath) implements RequestMatcher {

		@Override
		public boolean matches(HttpServletRequest request) {
			return matcher(request).isMatch();
		}

		@Override
		public MatchResult matcher(HttpServletRequest request) {
			if (!postToAcsPath.matches(request) || request.getParameter(Saml2ParameterNames.SAML_RESPONSE) == null) {
				return MatchResult.notMatch();
			}
			String idp = request.getParameter(LEGACY_IDP_PARAMETER);
			return StringUtils.hasText(idp) ? MatchResult.match(Map.of("registrationId", idp)) : MatchResult.match();
		}
	}
}
