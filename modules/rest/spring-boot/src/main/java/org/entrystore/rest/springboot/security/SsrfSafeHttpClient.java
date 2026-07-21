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
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes outbound HTTP requests against SSRF-validated targets, following redirects safely:
 * every hop's {@code Location} is resolved against the current URI and passed through the
 * caller-supplied validator before a new pinned connection is opened, and each connection is
 * disconnected before the next hop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsrfSafeHttpClient {

	static final int MAX_REDIRECTS = 15;

	private final SsrfValidator ssrfValidator;

	/**
	 * Handles the final (non-3xx) response of {@link #execute}. The connection is disconnected
	 * after the handler returns, so any response body must be consumed inside the handler.
	 */
	@FunctionalInterface
	public interface ResponseHandler<T> {
		T handle(int status, HttpURLConnection connection) throws IOException;
	}

	/**
	 * Executes {@code httpMethod} against a pre-validated target, following up to
	 * {@value #MAX_REDIRECTS} redirects. Failure mapping: redirect without Location, too many
	 * redirects, and IO or malformed-Location failures throw 502 Bad Gateway; connection timeouts
	 * and refusals throw 504 Gateway Timeout.
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

		SsrfValidator.ValidatedTarget target = initialTarget;
		for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
			HttpURLConnection conn = null;
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
					target = redirectValidator.apply(resolvedLocation);
					continue;
				}

				return responseHandler.handle(status, conn);

			} catch (SocketTimeoutException | ConnectException e) {
				log.debug("Request to {} timed out", target.uri());
				throw new CustomResponseException("Gateway timeout", HttpStatus.GATEWAY_TIMEOUT);
			} catch (IOException | URISyntaxException | IllegalArgumentException e) {
				// IllegalArgumentException: URI.resolve(location) on a malformed upstream Location header.
				log.debug("Request to {} failed: {}", target.uri(), e.getMessage());
				throw new CustomResponseException("Proxy request failed", HttpStatus.BAD_GATEWAY);
			} finally {
				if (conn != null) {
					conn.disconnect();
				}
			}
		}

		log.warn("More than {} redirect loops detected, aborting", MAX_REDIRECTS);
		throw new CustomResponseException("Too many redirects", HttpStatus.BAD_GATEWAY);
	}
}
