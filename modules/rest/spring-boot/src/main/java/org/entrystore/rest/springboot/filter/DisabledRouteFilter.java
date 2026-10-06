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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.configuration.AuthFeatureProperties;
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.EntryStoreCorsConfigurationSource;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers 404 on every route of a switched-off authentication feature, as 5.x left them unrouted. It runs before
 * the Spring Security filter chain, so neither CSRF, authentication, session checks nor handler mapping answer
 * first, whatever the method, content type or credentials. The body is the 404 envelope of
 * {@code AppExceptionHandler}.
 *
 * <p>Paths match as Spring MVC looks up handlers, with either path-matching strategy and below the
 * DispatcherServlet mapping. With CORS on, the CORS policy applies first, as the security chain's CORS filter
 * applies it, so cross-origin callers can read the 404 and a preflight is answered as on any other route.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
public class DisabledRouteFilter extends OncePerRequestFilter {

	private final List<PathPattern> disabledRoutes;
	private final ErrorResponseWriter errorResponseWriter;
	private final boolean corsEnabled;
	private final EntryStoreCorsConfigurationSource corsConfigurationSource;
	private final CorsProcessor corsProcessor = new DefaultCorsProcessor();

	public DisabledRouteFilter(AuthFeatureProperties authFeatures, ErrorResponseWriter errorResponseWriter,
							   CorsProperties corsProperties, EntryStoreCorsConfigurationSource corsConfigurationSource) {
		this.disabledRoutes = disabledRoutes(authFeatures).stream()
				.map(PathPatternParser.defaultInstance::parse)
				.toList();
		this.errorResponseWriter = errorResponseWriter;
		this.corsEnabled = corsProperties.enabled();
		this.corsConfigurationSource = corsConfigurationSource;
	}

	/** Path patterns of the switched-off features; {@code /x/**} also matches {@code /x} itself. */
	private static List<String> disabledRoutes(AuthFeatureProperties authFeatures) {
		var routes = new ArrayList<String>();
		if (!authFeatures.signup()) {
			routes.add("/auth/signup/**");
		}
		if (!authFeatures.passwordReset()) {
			routes.add("/auth/pwreset/**");
		}
		return routes;
	}

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		if (disabledRoutes.isEmpty()) {
			return true;
		}
		List<PathContainer> paths = lookupPaths(request);
		return disabledRoutes.stream().noneMatch(route -> paths.stream().anyMatch(route::matches));
	}

	/**
	 * The paths a handler may be looked up by: the parsed request path of the {@code PathPatternParser} strategy
	 * and the {@code UrlPathHelper} lookup path of the {@code AntPathMatcher} one. Both are relative to the context
	 * path and to a path-prefix DispatcherServlet mapping.
	 */
	private static List<PathContainer> lookupPaths(HttpServletRequest request) {
		RequestPath requestPath;
		if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
			requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
		} else {
			requestPath = ServletRequestPathUtils.parseAndCache(request);
			// Leaves the request as found, so that the DispatcherServlet parses it for its own dispatch
			ServletRequestPathUtils.clearParsedRequestPath(request);
		}
		return List.of(requestPath.pathWithinApplication(),
				PathContainer.parsePath(UrlPathHelper.defaultInstance.getLookupPathForRequest(request)));
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws IOException {
		if (corsEnabled) {
			var corsConfiguration = corsConfigurationSource.getCorsConfiguration(request);
			if (!corsProcessor.processRequest(corsConfiguration, request, response)
					|| CorsUtils.isPreFlightRequest(request)) {
				return;
			}
		}
		errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.NOT_FOUND.value())
				.path(request.getRequestURI())
				.error(HttpStatus.NOT_FOUND.getReasonPhrase())
				.build());
	}
}
