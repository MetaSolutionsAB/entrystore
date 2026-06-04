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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wraps JSON {@code GET} responses in a JSONP callback (<code>callback(&lt;json&gt;)</code>) when a
 * {@code ?callback=} parameter is present, porting the Restlet {@code JSCallbackFilter}. Gated by
 * {@code entrystore.jsonp} (default {@code true}). JSONP is legacy — CORS is the preferred mechanism.
 * <p>
 * Hardening over the original Restlet filter (which echoed the raw {@code callback} parameter into an
 * {@code application/javascript} body — a reflected-XSS / content-sniffing vector): the callback name
 * must be a JavaScript identifier or dotted member expression, invalid names are rejected with
 * {@code 400}, and {@code X-Content-Type-Options: nosniff} is set on the JSONP response. The wrapped
 * body no longer matches any {@code ETag}/{@code Last-Modified} the controller set for the plain JSON,
 * so the response is marked {@code Cache-Control: no-store} to prevent revalidation against a stale
 * validator.
 * <p>
 * Ordered to run outside {@link CacheControlFilter} so its cache-control headers are applied before
 * this filter writes the wrapped body, and inside Spring Security and {@code ModificationLockOutFilter}
 * so those short-circuit before any response buffering happens here. The relative order is expressed
 * via {@code @Order} below; if it matters and any of those filters' ordering changes, re-verify.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class JsonpCallbackFilter extends OncePerRequestFilter {

	private static final String CALLBACK_PARAM = "callback";
	// Fallback function name when ?callback= is present but blank. The literal happens to match
	// CALLBACK_PARAM but is independent of it (one is a query-param key, the other a JS identifier).
	private static final String DEFAULT_CALLBACK = "callback";
	private static final String JSONP_CONTENT_TYPE = "application/javascript";

	// JSON media types the original Restlet JSCallbackFilter wrapped. SPARQL results
	// (application/sparql-results+json) were never in this set and are additionally excluded as a
	// streaming endpoint (see isStreamingPath).
	private static final Set<String> JSON_MEDIA_TYPES = Set.of(
			MediaType.APPLICATION_JSON_VALUE,
			"application/ld+json",
			"application/rdf+json");

	// No-body HTTP statuses never carry an entity to wrap.
	private static final Set<Integer> NO_BODY_STATUSES = Set.of(
			HttpStatus.NO_CONTENT.value(),
			HttpStatus.NOT_MODIFIED.value());

	// A JSONP callback must be a JavaScript identifier or a dotted member expression of up to 32
	// segments (e.g. angular.callbacks._0). Anchored, ASCII-only; rejects "1foo", ".foo", "foo.",
	// "foo..bar" and any character that could break out of the application/javascript body.
	private static final int MAX_CALLBACK_LENGTH = 128;
	private static final Pattern VALID_CALLBACK = Pattern.compile(
			"^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,31}$");

	// Streaming endpoints: /sparql and /{ctx}/sparql. They return StreamingResponseBody via async
	// dispatch; buffering them to wrap would break the stream and risk OOM (and they emit
	// application/sparql-results+json, which was never JSONP-wrapped). Any future streaming endpoint
	// must be added here.
	private static final Pattern STREAMING_PATHS = Pattern.compile("/(?:[^/]+/)?sparql");

	private final boolean enabled;

	public JsonpCallbackFilter(@Value("${entrystore.jsonp:true}") boolean enabled) {
		this.enabled = enabled;
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

		// Fast path (no buffering) for the overwhelming majority of requests.
		if (!enabled || !"GET".equals(request.getMethod())) {
			filterChain.doFilter(request, response);
			return;
		}

		String rawCallback = request.getParameter(CALLBACK_PARAM);
		// No JSONP requested, or a streaming endpoint (e.g. /sparql) that must not be buffered:
		// pass straight through. isStreamingPath is only evaluated for the rare ?callback= request.
		if (rawCallback == null || isStreamingPath(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String callback = rawCallback.isBlank() ? DEFAULT_CALLBACK : rawCallback;
		if (callback.length() > MAX_CALLBACK_LENGTH || !VALID_CALLBACK.matcher(callback).matches()) {
			// Reject malicious callback syntax before any downstream work or buffering.
			HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.BAD_REQUEST.value())
					.path(request.getRequestURI())
					.error("Invalid JSONP callback name")
					.build());
			return;
		}

		ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
		filterChain.doFilter(request, wrapper);

		if (isAsyncStarted(request) || response.isCommitted() || !shouldWrap(wrapper)) {
			wrapper.copyBodyToResponse();
			return;
		}

		writeJsonpResponse(response, wrapper, callback);
	}

	private static boolean isStreamingPath(HttpServletRequest request) {
		String path = request.getServletPath();
		return path != null && STREAMING_PATHS.matcher(path).matches();
	}

	private static boolean shouldWrap(ContentCachingResponseWrapper wrapper) {
		// HttpUtil.normalizeMediaType strips any ";charset=" suffix, lowercases, and returns null for a
		// null/unparseable content type — all handled by the JSON_MEDIA_TYPES lookup below.
		return !NO_BODY_STATUSES.contains(wrapper.getStatus())
				&& JSON_MEDIA_TYPES.contains(HttpUtil.normalizeMediaType(wrapper.getContentType()));
	}

	private static void writeJsonpResponse(HttpServletResponse response,
										   ContentCachingResponseWrapper wrapper,
										   String callback) throws IOException {
		Charset charset = resolveCharset(wrapper.getContentType());
		byte[] body = wrapper.getContentAsByteArray();
		// callback( and ) are ASCII-safe; the body bytes pass through untouched, so any charset is
		// preserved regardless of what the JSON was encoded in.
		byte[] prefix = (callback + "(").getBytes(charset);
		byte[] suffix = ")".getBytes(charset);

		response.setContentType(JSONP_CONTENT_TYPE);
		response.setCharacterEncoding(charset.name());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setContentLengthLong((long) prefix.length + body.length + suffix.length);
		// The wrapped body no longer matches the controller's strong ETag / Last-Modified, so prevent
		// caches and clients from revalidating against a stale validator. Only set when absent so the
		// `private, no-store` that CacheControlFilter already stamps on authenticated responses wins.
		if (response.getHeader(HttpHeaders.CACHE_CONTROL) == null) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		}

		// Three writes avoid allocating a fourth body-sized array just to concatenate.
		ServletOutputStream out = response.getOutputStream();
		out.write(prefix);
		out.write(body);
		out.write(suffix);
	}

	private static Charset resolveCharset(String contentType) {
		try {
			Charset charset = MediaType.parseMediaType(contentType).getCharset();
			return charset != null ? charset : StandardCharsets.UTF_8;
		} catch (IllegalArgumentException e) {
			log.debug("Could not parse charset from content-type '{}', defaulting to UTF-8: {}",
					contentType, e.getMessage());
			return StandardCharsets.UTF_8;
		}
	}
}
