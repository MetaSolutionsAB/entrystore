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

package org.entrystore.rest.springboot.util;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Writes {@link ErrorResponse} bodies straight to the servlet response, for servlet filters and
 * Spring Security handlers where {@code AppExceptionHandler} ({@code @ControllerAdvice}) is not
 * available because they run before the DispatcherServlet.
 *
 * <p>Uses the Spring-managed {@link ObjectMapper} — the same one {@code AppExceptionHandler}
 * serializes with — so any {@code spring.jackson.*} customisation applies to every error body
 * regardless of which layer produced it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorResponseWriter {

	private final ObjectMapper objectMapper;

	/**
	 * Writes a JSON error response directly to the servlet response.
	 */
	public void writeErrorResponseAsJson(HttpServletResponse response, ErrorResponse errorResponse) throws IOException {
		if (response.isCommitted()) {
			log.warn("Cannot write error response — response already committed (status={})", errorResponse.status());
			return;
		}
		response.setStatus(errorResponse.status());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		var writer = response.getWriter();
		writer.write(objectMapper.writeValueAsString(errorResponse));
		writer.flush();
	}

	/**
	 * Redirects to the given URL if non-null, otherwise writes a 401 JSON error response.
	 * Safe to call from servlet filter context (no exceptions thrown to the filter chain).
	 *
	 * @param failureMessage message for the JSON fallback body (e.g. "CAS login failed")
	 */
	public void redirectOrWriteUnauthorized(HttpServletResponse response, String requestUri,
											String redirectUrl, String failureMessage) throws IOException {
		if (redirectUrl != null) {
			response.sendRedirect(redirectUrl);
		} else {
			writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.UNAUTHORIZED.value())
					.path(requestUri)
					.error(failureMessage != null ? failureMessage : "SSO login failed")
					.build());
		}
	}

	/**
	 * Writes the unified 401 JSON envelope used for every authentication failure
	 * (bad credentials, unknown user, disabled account, blacklisted user). Centralised
	 * here so the body stays identical across all call sites for a given request URI
	 * and no caller can accidentally introduce a discriminator. The timestamp field
	 * is explicitly nulled so the wall-clock delta between branches (immediate
	 * blacklist short-circuit vs. post-bcrypt bad-credentials path) does not leak
	 * through {@code body.timestamp − client.sentAt}; only the request URI varies.
	 */
	public void writeUnauthorizedAsJson(HttpServletResponse response, String requestUri) throws IOException {
		writeErrorResponseAsJson(response, ErrorResponse.builder()
				.timestamp(null)
				.status(HttpStatus.UNAUTHORIZED.value())
				.path(requestUri)
				.error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
				.build());
	}
}
