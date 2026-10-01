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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.security.SamlAcsRequestMatcher;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Raises Jetty's form-size limit to {@link #SAML_RESPONSE_MAX_SIZE} for POSTs to the SAML assertion consumer
 * service, leaving {@code server.jetty.max-http-form-post-size} (32 KB as shipped) on every other form POST. A
 * {@code SAMLResponse} from an IdP that sends many group claims (Entra ID, ADFS) exceeds 32 KB; 5.x accepted it.
 *
 * <p>Jetty reads the per-request limit when it first parses the form, so this filter runs first and matches by
 * method and path only. A larger configured global limit is kept. A body over the limit gets Jetty's 400.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanConfig("entrystore.auth.saml.enabled")
public class SamlAcsFormSizeFilter extends OncePerRequestFilter {

	private static final DataSize SAML_RESPONSE_MAX_SIZE = DataSize.ofKilobytes(512);

	private static final RequestMatcher ACS_POST = SamlAcsRequestMatcher.postToAnyAcsPath();

	private final int maxFormContentSize;

	public SamlAcsFormSizeFilter(@Value("${server.jetty.max-http-form-post-size:200000B}") DataSize formPostSizeLimit) {
		long bytes = Math.max(SAML_RESPONSE_MAX_SIZE.toBytes(), formPostSizeLimit.toBytes());
		this.maxFormContentSize = (int) Math.min(bytes, Integer.MAX_VALUE);
	}

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		return !ACS_POST.matches(request);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		request.setAttribute(ServletContextHandler.MAX_FORM_CONTENT_SIZE_KEY, maxFormContentSize);
		filterChain.doFilter(request, response);
	}
}
