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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Date;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

	private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	/**
	 * Determines the media type based on the provided format parameter or content type header.
	 *
	 * @param format The format parameter from the request, which might have '+' replaced with spaces by Spring Boot.
	 * @param contentType The raw content-type header string from the request.
	 * @return The determined and normalized media type string, or null if neither can be determined.
	 */
	public static String determineMediaType(String format, String contentType) {
		// for 'format' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (format != null) {
			return format.trim().replace(' ', '+');
		} else {
			// content-type header often includes other data like character encoding, e.g.: 'application/json; charset=UTF-8'
			return normalizeMediaType(contentType);
		}
	}

	/**
	 * Normalizes a content-type header string by parsing it and returning the type and subtype
	 * in lowercase, e.g., "application/json". If the contentType is null or cannot be parsed,
	 * null is returned.
	 *
	 * @param contentType The raw content-type header string as received in the request.
	 * @return The normalized media type string, or null if parsing fails or input is null.
	 */
	public static String normalizeMediaType(String contentType) {
		if (contentType != null) {
			try {
				MediaType mt = MediaType.parseMediaType(contentType);
				return mt.getType() + "/" + mt.getSubtype();
			} catch (IllegalArgumentException e) {
				log.debug("Could not parse content-type header value of '{}'. Error: {}", contentType, e.getMessage());
			}
		}
		return null;
	}

	/**
	 * Creates a weak ETag string.
	 * Example: W/"1686594567200"
	 */
	public static String createWeakETag(String tag) {
		return "W/" + createStrongETag(tag);
	}

	/**
	 * Creates a strong ETag string.
	 * Example: "1686594567200"
	 */
	public static String createStrongETag(String tag) {
		return "\"" + tag + "\"";
	}

	/**
	 * Sets Last-Modified and ETag headers on the provided {@link HttpHeaders} instance.
	 * If the modification date is null, a warning is logged and the headers are not updated.
	 *
	 * @param headers      the headers to update
	 * @param modifiedDate the modification date used for generating the headers
	 */
	public static void setLastModifiedAndETag(HttpHeaders headers, Date modifiedDate) {
		if (modifiedDate == null) {
			log.warn("Last-Modified header could not be set because the modification date is null");
		} else {
			headers.setLastModified(modifiedDate.getTime());
			headers.setETag(createStrongETag(Long.toString(modifiedDate.getTime())));
		}
	}

	/**
	 * Updates the response headers with the last modification date and a strong ETag
	 * based on the provided modification date.
	 * If the modification date is null a warning is logged, and
	 * the headers are not updated.
	 *
	 * @param responseBuilder the response builder used to set the headers
	 * @param modifiedDate    the modification date used for generating the headers
	 */
	public static ResponseEntity.HeadersBuilder<?> updateResponseWithModificationDateAndETag(
			ResponseEntity.HeadersBuilder<?> responseBuilder,
			Date modifiedDate) {

		responseBuilder.headers(headers -> setLastModifiedAndETag(headers, modifiedDate));
		return responseBuilder;
	}

	public static boolean isLargerThan(HttpServletRequest request, long maxSize) {
		if (request == null) {
			return false;
		}
		long repSize = request.getContentLength();
		if (repSize == -1L) {
			log.warn("Size of representation is unknown");
			return true;
		}

		return repSize > maxSize;
	}

	/**
	 * Returns the client IP, or a comma separated list of IPs.
	 */
	public static String getClientIpAddress(HttpServletRequest request) {
		checkArgument(request != null, "request must not be null");
		String ip = request.getRemoteAddr();
		if (StringUtils.isBlank(ip)) {
			String s = request.getHeader(HEADER_X_FORWARDED_FOR);
			String[] clientIpArray = StringUtils.split(s, ',');
			if (ArrayUtils.isNotEmpty(clientIpArray)) {
				return clientIpArray[0];
			}
		}

		return ip;
	}

	/**
	 * Writes a JSON error response directly to the servlet response.
	 * Use this in servlet filters where {@code AppExceptionHandler} ({@code @ControllerAdvice})
	 * is not available.
	 */
	public static void writeErrorResponseAsJson(HttpServletResponse response, ErrorResponse errorResponse) throws IOException {
		if (response.isCommitted()) {
			log.warn("Cannot write error response — response already committed (status={})", errorResponse.status());
			return;
		}
		response.setStatus(errorResponse.status());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		var writer = response.getWriter();
		writer.write(OBJECT_MAPPER.writeValueAsString(errorResponse));
		writer.flush();
	}

	/**
	 * Redirects to the given URL if non-null, otherwise writes a 401 JSON error response.
	 * Safe to call from servlet filter context (no exceptions thrown to the filter chain).
	 *
	 * @param failureMessage message for the JSON fallback body (e.g. "CAS login failed")
	 */
	public static void redirectOrWriteUnauthorized(HttpServletResponse response, String requestUri,
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

	public static void checkRequestSize(HttpServletRequest request, int maxRequestSize) {
		if (HttpUtil.isLargerThan(request, maxRequestSize)) {
			throw new EntityTooLargeException("The size of the representation is larger than " + maxRequestSize + "bytes or unknown, request blocked.");
		}
	}
}
