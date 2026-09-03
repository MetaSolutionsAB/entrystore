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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.UrlUtils;
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
	 * Redirects to the given URL if non-null, otherwise writes a 401 JSON error response. Raises no
	 * application exception, so it is safe to use from a servlet filter or a Spring Security handler
	 * where {@code AppExceptionHandler} cannot see the failure; only {@code IOException} propagates.
	 *
	 * <p>Both branches bail out on an already-committed response. This is reachable: a
	 * last-resort {@code catch} in {@code AbstractSsoLoginSuccessHandler} calls this after a
	 * subclass's custom-success redirect may already have committed, and {@code sendRedirect} throws
	 * {@code IllegalStateException} in that case — which would escape the Security filter chain as a
	 * raw container 500 instead of the intended failure response.
	 *
	 * <p>A context-relative {@code redirectUrl} (e.g. the default {@code /auth/user}) is prefixed with the
	 * servlet context path, mirroring Spring Security's {@code DefaultRedirectStrategy}; the container
	 * would otherwise resolve it against the server root.
	 *
	 * @param failureMessage message for the JSON fallback body (e.g. "CAS login failed")
	 */
	public void redirectOrWriteUnauthorized(HttpServletRequest request, HttpServletResponse response,
											String redirectUrl, String failureMessage) throws IOException {
		if (redirectUrl != null) {
			if (response.isCommitted()) {
				log.warn("Cannot redirect to the SSO failure URL — response already committed");
				return;
			}
			response.sendRedirect(UrlUtils.isAbsoluteUrl(redirectUrl) ? redirectUrl : request.getContextPath() + redirectUrl);
		} else {
			writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.UNAUTHORIZED.value())
					.path(request.getRequestURI())
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
