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
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Answers 413 to a multipart request whose Content-Length exceeds {@code spring.servlet.multipart.max-request-size},
 * before anything parses the body. Otherwise the first {@code getParameter} call in the filter chain makes Jetty parse
 * the body and fail mid-way with a 400 and a closed connection, which a client still sending sees as a reset.
 *
 * <p>The body is read and discarded after the 413 is sent, so a client that reads the response only once it has sent
 * everything, like {@code HttpURLConnection}, still gets it. A client that sent {@code Expect: 100-continue} has not
 * sent the body, so nothing is read. A body without Content-Length, or a part above
 * {@code spring.servlet.multipart.max-file-size}, is still rejected by Jetty's parser with 400.
 */
@Slf4j
@Component
// After RequestResponseLoggingFilter, so the rejection is logged, and before every filter that reads parameters.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MultipartRequestSizeFilter extends OncePerRequestFilter {

	private final long maxRequestSize;

	private final ErrorResponseWriter errorResponseWriter;

	public MultipartRequestSizeFilter(ObjectProvider<MultipartConfigElement> multipartConfig,
									  ErrorResponseWriter errorResponseWriter) {
		MultipartConfigElement config = multipartConfig.getIfAvailable();
		this.maxRequestSize = (config != null) ? config.getMaxRequestSize() : -1;
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		return maxRequestSize < 0 || request.getContentLengthLong() <= maxRequestSize || !isMultipart(request);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.CONTENT_TOO_LARGE.value())
				.path(request.getRequestURI())
				.error("Request size of " + request.getContentLengthLong() + " bytes exceeds the maximum allowed size of "
						+ maxRequestSize + " bytes")
				.build());
		if (!"100-continue".equalsIgnoreCase(request.getHeader(HttpHeaders.EXPECT))) {
			try {
				request.getInputStream().transferTo(OutputStream.nullOutputStream());
			} catch (IOException e) {
				log.debug("Client went away while its rejected request body was discarded: {}", e.getMessage());
			}
		}
	}

	private static boolean isMultipart(HttpServletRequest request) {
		String contentType = request.getContentType();
		return contentType != null && contentType.regionMatches(true, 0, MediaType.MULTIPART_FORM_DATA_VALUE, 0,
				MediaType.MULTIPART_FORM_DATA_VALUE.length());
	}
}
