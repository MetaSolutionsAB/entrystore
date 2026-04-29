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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
// Run before Spring Security so blocked writes don't pay BCrypt/session lookup cost during a backup.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ModificationLockOutFilter extends OncePerRequestFilter {

	private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");
	private static final Set<String> ALLOWED_AUTH_PATHS = Set.of("/auth/login", "/auth/cookie", "/auth/logout");
	// Spring Security's default SAML2 assertion-consumer path; the IdP POSTs the assertion here.
	// Must be allowed so in-flight SSO logins can complete during a maintenance window.
	private static final String SAML2_SSO_CALLBACK_PREFIX = "/login/saml2/sso/";
	private static final String MAINTENANCE_MESSAGE =
			"The service is being maintained and does not accept modification requests right now, please check back later";

	private final RepositoryManager repositoryManager;

	@Override
	protected void doFilterInternal(HttpServletRequest request,
									HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {

		if (!repositoryManager.hasModificationLockOut() || isAllowedDuringLockout(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		log.info("Modification lockout active, rejecting {} {}", request.getMethod(), request.getRequestURI());
		HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.SERVICE_UNAVAILABLE.value())
				.path(request.getRequestURI())
				.error(MAINTENANCE_MESSAGE)
				.build());
	}

	private static boolean isAllowedDuringLockout(HttpServletRequest request) {
		if (READ_ONLY_METHODS.contains(request.getMethod())) {
			return true;
		}
		// Match against the dispatcher's normalized servlet path so `/auth/login;jsessionid=…`
		// and `/auth/login/` both match the exact allowlist entry. `getRequestURI()` is the raw
		// pre-normalization form and would diverge from what Spring Security and the controllers see.
		String path = request.getServletPath();
		if (path == null) {
			return false;
		}
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return ALLOWED_AUTH_PATHS.contains(path) || path.startsWith(SAML2_SSO_CALLBACK_PREFIX);
	}
}
