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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes outbound HTTP requests against SSRF-validated targets, following redirects safely:
 * every hop's {@code Location} is resolved against the current URI and passed through the
 * caller-supplied validator before a new pinned connection is opened.
 * <p>
 * Redirect hops are drained (bounded by {@link #MAX_REDIRECT_DRAIN_BYTES}) rather than
 * disconnected, so the underlying socket returns to the JDK keep-alive pool and is reused for the
 * following hop — measured at -28% on the proxy redirect path (ENTRYSTORE-1090, D6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsrfSafeHttpClient {

	// Package-private: SsrfSafeHttpClientTest builds a body one byte past the cap.
	static final int MAX_REDIRECT_DRAIN_BYTES = 64 * 1024;

	private final SsrfValidator ssrfValidator;
	private final ProxyProperties proxyProperties;

	/**
	 * Handles the final (non-3xx) response of {@link #execute}. Any response body must be consumed
	 * inside the handler; see the {@code handlerConsumesBody} parameter of
	 * {@link #execute(SsrfValidator.ValidatedTarget, String, Map, Function, ResponseHandler, boolean)}
	 * for how that affects the connection's lifecycle.
	 */
	@FunctionalInterface
	public interface ResponseHandler<T> {
		T handle(int status, HttpURLConnection connection) throws IOException;
	}

	/**
	 * Executes {@code httpMethod} against a pre-validated target, following up to
	 * {@code entrystore.proxy.max-redirects} redirects (default 15). Failure mapping: redirect without
	 * Location, too many redirects, and IO or malformed-Location failures throw 502 Bad Gateway;
	 * connection timeouts and refusals throw 504 Gateway Timeout.
	 *
	 * <p>Note the loop bound is inclusive, so a cap of N opens up to N+1 connections: the initial
	 * request plus N redirect hops.
	 *
	 * @param initialTarget     the already-validated request target
	 * @param httpMethod        HTTP method to send on every hop
	 * @param requestHeaders    headers to set on every hop
	 * @param redirectValidator SSRF re-validation applied to each resolved redirect location
	 * @param responseHandler   converts the final response; see {@link ResponseHandler}
	 */
	public <T> T execute(SsrfValidator.ValidatedTarget initialTarget, String httpMethod,
						 Map<String, String> requestHeaders,
						 Function<String, SsrfValidator.ValidatedTarget> redirectValidator,
						 ResponseHandler<T> responseHandler) {
		return execute(initialTarget, httpMethod, requestHeaders, redirectValidator, responseHandler, false);
	}

	/**
	 * As {@link #execute(SsrfValidator.ValidatedTarget, String, Map, Function, ResponseHandler)},
	 * with explicit control over the final connection's lifecycle.
	 *
	 * @param handlerConsumesBody whether {@code responseHandler} reads the response body to EOF and
	 *                            closes the stream. When true, the connection is left alone so the
	 *                            JDK can pool it for reuse — calling {@code disconnect()} on a
	 *                            fully-consumed response would evict it from the keep-alive pool
	 *                            instead. When false, the connection is disconnected after the
	 *                            handler returns, because an unconsumed response is never pooled and
	 *                            would otherwise linger.
	 */
	public <T> T execute(SsrfValidator.ValidatedTarget initialTarget, String httpMethod,
						 Map<String, String> requestHeaders,
						 Function<String, SsrfValidator.ValidatedTarget> redirectValidator,
						 ResponseHandler<T> responseHandler,
						 boolean handlerConsumesBody) {

		SsrfValidator.ValidatedTarget target = initialTarget;
		int maxRedirects = proxyProperties.maxRedirects();
		// Inclusive bound, preserved deliberately: a cap of N allows the initial request plus N hops.
		for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
			HttpURLConnection conn = null;
			boolean pooled = false;
			try {
				conn = ssrfValidator.openPinnedConnection(target.uri(), target.resolved());
				conn.setRequestMethod(httpMethod);
				for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
					conn.setRequestProperty(header.getKey(), header.getValue());
				}

				int status = conn.getResponseCode();

				if (status >= 300 && status < 400) {
					String location = conn.getHeaderField("Location");
					if (location == null) {
						log.warn("Upstream returned {} redirect without Location header for URL: {}", status, target.uri());
						throw new CustomResponseException("Upstream returned redirect without Location header", HttpStatus.BAD_GATEWAY);
					}
					String resolvedLocation = target.uri().resolve(location).toString();
					// Resolve and re-validate before draining: an invalid hop should fail without
					// spending time reading a body we are about to abandon.
					SsrfValidator.ValidatedTarget next = redirectValidator.apply(resolvedLocation);
					pooled = drainForReuse(conn);
					target = next;
					continue;
				}

				T result = responseHandler.handle(status, conn);
				pooled = handlerConsumesBody;
				return result;

			} catch (SocketTimeoutException | ConnectException e) {
				log.debug("Request to {} timed out", target.uri());
				throw new CustomResponseException("Gateway timeout", HttpStatus.GATEWAY_TIMEOUT);
			} catch (IOException | URISyntaxException | IllegalArgumentException e) {
				// IllegalArgumentException: URI.resolve(location) on a malformed upstream Location header.
				log.debug("Request to {} failed: {}", target.uri(), e.getMessage());
				throw new CustomResponseException("Proxy request failed", HttpStatus.BAD_GATEWAY);
			} finally {
				// Sever the connection only when it cannot be pooled (error path, unconsumed body, or
				// a hop whose body could not be drained); pooled connections are reused by the JDK.
				if (conn != null && !pooled) {
					conn.disconnect();
				}
			}
		}

		// Phrased as the cap rather than as a loop count: with entrystore.proxy.max-redirects=0 — a
		// supported value meaning "do not follow redirects" — a single well-behaved 302 lands here, and
		// "more than 0 loops detected" would point whoever debugs the 502 at the upstream instead of at
		// their own configuration.
		log.warn("Upstream exceeded the configured redirect cap of {} (entrystore.proxy.max-redirects), aborting",
				maxRedirects);
		throw new CustomResponseException("Too many redirects", HttpStatus.BAD_GATEWAY);
	}

	/**
	 * Consumes a redirect hop's response body so the underlying connection returns to the JDK
	 * keep-alive pool for the following hop instead of being torn down. Bounded: a hop body larger
	 * than {@link #MAX_REDIRECT_DRAIN_BYTES} is not worth salvaging, and a drain failure leaves the
	 * connection in an unknown state — in both cases the caller severs it instead.
	 *
	 * @return true if the body was fully drained and the connection may be pooled
	 */
	private boolean drainForReuse(HttpURLConnection conn) {
		try (InputStream is = conn.getInputStream()) {
			byte[] buf = new byte[8192];
			int total = 0;
			int bytesRead;
			while ((bytesRead = is.read(buf)) != -1) {
				total += bytesRead;
				if (total > MAX_REDIRECT_DRAIN_BYTES) {
					log.debug("Redirect hop body exceeded {} bytes; severing connection instead of pooling it",
							MAX_REDIRECT_DRAIN_BYTES);
					return false;
				}
			}
			return true;
		} catch (IOException e) {
			log.debug("Failed to drain redirect hop body, severing connection: {}", e.getMessage());
			return false;
		}
	}
}
