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
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Wraps JSON {@code GET} responses in a JSONP callback (<code>callback(&lt;json&gt;)</code>) when a
 * {@code ?callback=} parameter is present, porting the Restlet {@code JSCallbackFilter}. Gated by
 * {@code entrystore.jsonp} (default {@code true}). JSONP is legacy — CORS is the preferred mechanism.
 * <p>
 * No body is buffered: {@link JsonpResponseWrapper} decides at the first write of the body whether to wrap it, and
 * writes it through between the parentheses of the callback.
 * <p>
 * A request that asks for JSONP is served as guest whatever session cookie or HTTP Basic credentials it carries, see
 * {@link IgnoreAuthFilter} and {@link org.entrystore.rest.springboot.configuration.IgnoreAuthHandlerCustomizer}, so
 * the wrapped body holds only what a guest may read. A request whose response is not wrapped is served as guest as
 * well: any {@code /sparql} request, and files, other RDF formats and {@code /proxy} responses that are not JSON.
 * <p>
 * Hardening over the original Restlet filter (which echoed the raw {@code callback} parameter into an
 * {@code application/javascript} body — a reflected-XSS / content-sniffing vector): the callback name
 * must be a JavaScript identifier or dotted member expression, invalid names are rejected with
 * {@code 400}, and {@code X-Content-Type-Options: nosniff} is set on the JSONP response. The wrapped
 * body no longer matches any {@code ETag}/{@code Last-Modified} the controller set for the plain JSON,
 * so the response is marked {@code Cache-Control: no-store} to prevent revalidation against a stale
 * validator.
 * <p>
 * Ordered to run outside {@link CacheControlFilter}, so the {@code Cache-Control} it sets is in place when the
 * response is decided. The relative order is expressed via {@code @Order} below; if that filter's ordering changes,
 * re-verify.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class JsonpCallbackFilter extends OncePerRequestFilter {

	private static final String CALLBACK_PARAM = "callback";
	// Fallback function name when ?callback= is present but blank. The literal happens to match
	// CALLBACK_PARAM but is independent of it (one is a query-param key, the other a JS identifier).
	private static final String DEFAULT_CALLBACK = "callback";

	// A JSONP callback must be a JavaScript identifier or a dotted member expression of up to 32
	// segments (e.g. angular.callbacks._0). Anchored, ASCII-only; rejects "1foo", ".foo", "foo.",
	// "foo..bar" and any character that could break out of the application/javascript body.
	private static final int MAX_CALLBACK_LENGTH = 128;
	private static final Pattern VALID_CALLBACK = Pattern.compile(
			"^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,31}$");

	// /sparql and /{ctx}/sparql return StreamingResponseBody, which may write after this filter returns, so a wrapped
	// body could not be closed (SPARQL JSON was never wrapped). Add any future async endpoint here.
	private static final Pattern ASYNC_PATHS = Pattern.compile("/(?:[^/]+/)?sparql");

	private final boolean enabled;
	private final ErrorResponseWriter errorResponseWriter;

	public JsonpCallbackFilter(@Value("${entrystore.jsonp:true}") boolean enabled,
							   ErrorResponseWriter errorResponseWriter) {
		this.enabled = enabled;
		this.errorResponseWriter = errorResponseWriter;
	}

	/**
	 * Returns the {@code callback} parameter of a request that asks for JSONP, which only a GET can. Only the query
	 * string is read, as 5.x did: getParameter would parse a form or multipart body. {@link IgnoreAuthFilter} serves
	 * every request this returns non-null for as guest, so every request this filter may wrap is served as guest.
	 *
	 * @return the decoded, not yet validated value, empty for a bare {@code ?callback}, or null if the request asks
	 * for no JSONP
	 */
	static @Nullable String requestedCallback(HttpServletRequest request) {
		return "GET".equals(request.getMethod()) ? HttpUtil.getQueryParameter(request, CALLBACK_PARAM) : null;
	}

	// Defense-in-depth: never re-run on the ASYNC dispatch of a request that went async.
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return true;
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {

		String rawCallback = enabled ? requestedCallback(request) : null;
		// JSONP off or not requested (the overwhelming majority), or an async endpoint (e.g. /sparql) that is never
		// wrapped: pass straight through. isAsyncPath is only evaluated for a ?callback= request.
		if (rawCallback == null || isAsyncPath(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String callback = rawCallback.isBlank() ? DEFAULT_CALLBACK : rawCallback;
		if (callback.length() > MAX_CALLBACK_LENGTH || !VALID_CALLBACK.matcher(callback).matches()) {
			// Reject malicious callback syntax before any downstream work.
			errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.BAD_REQUEST.value())
					.path(request.getRequestURI())
					.error("Invalid JSONP callback name")
					.build());
			return;
		}

		JsonpResponseWrapper wrapper = new JsonpResponseWrapper(response, callback);
		filterChain.doFilter(request, wrapper);
		if (isAsyncStarted(request)) {
			wrapper.passThrough();
		} else {
			wrapper.finish();
		}
	}

	private static boolean isAsyncPath(HttpServletRequest request) {
		String path = request.getServletPath();
		return path != null && ASYNC_PATHS.matcher(path).matches();
	}
}
