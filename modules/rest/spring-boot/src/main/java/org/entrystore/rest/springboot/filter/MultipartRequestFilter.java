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
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.EntryStoreCorsConfigurationSource;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;

/**
 * Screens multipart requests before anything can read their body, because reading any request parameter makes
 * Jetty parse the whole upload to temporary files, and many parameter readers run before the access check.
 * <ul>
 * <li>A multipart request to a route other than those in {@link #MULTIPART_ROUTES} is answered 415.</li>
 * <li>One whose Content-Length exceeds {@code spring.servlet.multipart.max-request-size} is answered 413, for
 * {@code /echo} in the textarea it answers every failure with. The
 * first 4 MB of its body are then read and discarded, so a client that reads the
 * response only once it has sent a moderately oversized body, like {@code HttpURLConnection}, still gets the 413;
 * beyond that, or after {@code Expect: 100-continue}, Jetty closes the connection.</li>
 * </ul>
 * Paths match as Spring MVC looks up handlers, relative to the context path and DispatcherServlet mapping, and a
 * route is only allowed when the lookup paths of both path-matching strategies match it. With
 * CORS on, the CORS policy applies first, so cross-origin callers can read the error.
 */
@Slf4j
@Component
// After RequestResponseLoggingFilter, so the rejection is logged, and before every filter that reads parameters.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MultipartRequestFilter extends OncePerRequestFilter {

	/** The routes of the handlers marked {@code @AcceptsMultipart}; {@code MultipartHandlerInterceptor} checks the handler. */
	private static final List<Route> MULTIPART_ROUTES = List.of(
			new Route(HttpMethod.PUT, "/*/resource/*", false),
			new Route(HttpMethod.POST, "/*/import", false),
			// /echo answers every failure in a textarea, as 5.x did, because EntryScape reads it from an iframe.
			new Route(HttpMethod.POST, "/echo", true));

	private record Route(HttpMethod method, PathPattern pattern, boolean textareaErrors) {
		Route(HttpMethod method, String pattern, boolean textareaErrors) {
			this(method, PathPatternParser.defaultInstance.parse(pattern), textareaErrors);
		}
	}

	private final long maxRequestSize;
	private final ErrorResponseWriter errorResponseWriter;
	private final boolean corsEnabled;
	private final EntryStoreCorsConfigurationSource corsConfigurationSource;
	private final CorsProcessor corsProcessor = new DefaultCorsProcessor();

	public MultipartRequestFilter(ObjectProvider<MultipartConfigElement> multipartConfig,
								  ErrorResponseWriter errorResponseWriter, CorsProperties corsProperties,
								  EntryStoreCorsConfigurationSource corsConfigurationSource) {
		MultipartConfigElement config = multipartConfig.getIfAvailable();
		this.maxRequestSize = (config != null) ? config.getMaxRequestSize() : -1;
		this.errorResponseWriter = errorResponseWriter;
		this.corsEnabled = corsProperties.enabled();
		this.corsConfigurationSource = corsConfigurationSource;
	}

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		return !HttpUtil.isMultipart(request);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		Route route = multipartRoute(request);
		boolean tooLarge = maxRequestSize >= 0 && request.getContentLengthLong() > maxRequestSize;
		if (route != null && !tooLarge) {
			filterChain.doFilter(request, response);
			return;
		}
		// Backstop: a preflight carries no body, so it only lands here if a client sends a multipart Content-Type.
		if (corsEnabled) {
			var corsConfiguration = corsConfigurationSource.getCorsConfiguration(request);
			if (!corsProcessor.processRequest(corsConfiguration, request, response)
					|| CorsUtils.isPreFlightRequest(request)) {
				return;
			}
		}
		if (route == null) {
			errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value())
					.path(request.getRequestURI())
					.error("This resource does not accept multipart/form-data")
					.build());
			return;
		}
		String error = "Request size of " + request.getContentLengthLong() + " bytes exceeds the maximum allowed size of "
				+ maxRequestSize + " bytes";
		if (route.textareaErrors()) {
			writeTextarea(response, HttpStatus.CONTENT_TOO_LARGE, error);
		} else {
			errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.CONTENT_TOO_LARGE.value())
					.path(request.getRequestURI())
					.error(error)
					.build());
		}
		HttpUtil.discardRejectedBody(request);
	}

	/** Writes what {@code AppExceptionHandler} renders for a {@code TextareaHtmlResponseException}. */
	private static void writeTextarea(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType("text/html;charset=UTF-8");
		var writer = response.getWriter();
		writer.write("<textarea>" + HtmlUtils.htmlEscape("status:" + status.value() + "\n" + message) + "</textarea>");
		writer.flush();
	}

	/** The multipart route the request is for, or null. */
	private static Route multipartRoute(HttpServletRequest request) {
		List<PathContainer> paths = lookupPaths(request);
		// Both lookup paths must match, a backstop against a path that the two strategies resolve differently.
		return MULTIPART_ROUTES.stream().filter(route -> route.method().matches(request.getMethod())
				&& paths.stream().allMatch(route.pattern()::matches)).findFirst().orElse(null);
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
}
