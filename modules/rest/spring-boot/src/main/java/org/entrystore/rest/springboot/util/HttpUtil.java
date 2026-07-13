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

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Date;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

	private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
	// Jackson 3 already serializes dates as ISO-8601 by default (WRITE_DATES_AS_TIMESTAMPS is now off);
	// only the property-ordering default changed (now alphabetical), so keep declaration order.
	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
			.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.build();

	/**
	 * Determines the media type based on the provided format parameter or content type header.
	 *
	 * @param format The format parameter from the request, bound via {@code MediaTypeConverter}.
	 * @param contentType The raw content-type header string from the request.
	 * @return The determined and normalized media type string, or null if neither can be determined.
	 */
	public static String determineMediaType(MediaType format, String contentType) {
		if (format != null) {
			return format.toString();
		}
		// content-type header often includes other data like character encoding, e.g.: 'application/json; charset=UTF-8'
		return normalizeMediaType(contentType);
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
	 * Returns the client IP.
	 * <p>
	 * When {@code trustForwardedFor} is {@code false} (the secure default), returns the TCP remote
	 * address as reported by the servlet container. When {@code true}, returns the leftmost address
	 * in {@code X-Forwarded-For} if it parses as a valid IP literal; otherwise falls back to the
	 * remote address.
	 * <p>
	 * SECURITY: enable {@code X-Forwarded-For} trust only when running behind a reverse proxy that
	 * overwrites or strips client-supplied {@code X-Forwarded-For} headers. With trust enabled and
	 * direct internet exposure, clients can spoof the header to defeat any per-IP logic that uses
	 * this value.
	 */
	public static String getClientIpAddress(HttpServletRequest request, boolean trustForwardedFor) {
		checkArgument(request != null, "request must not be null");
		if (trustForwardedFor) {
			String xff = request.getHeader(HEADER_X_FORWARDED_FOR);
			if (StringUtils.isNotBlank(xff)) {
				String[] clientIpArray = StringUtils.split(xff, ',');
				if (ArrayUtils.isNotEmpty(clientIpArray)) {
					String first = clientIpArray[0].trim();
					if (InetAddresses.isInetAddress(first)) {
						return first;
					}
				}
			}
		}
		return request.getRemoteAddr();
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

	private static final int LOG_VALUE_MAX_LENGTH = 128;
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	/**
	 * Writes the unified 401 JSON envelope used for every authentication failure
	 * (bad credentials, unknown user, disabled account, blacklisted user). Centralised
	 * here so the body stays identical across all call sites for a given request URI
	 * and no caller can accidentally introduce a discriminator. The timestamp field
	 * is explicitly nulled so the wall-clock delta between branches (immediate
	 * blacklist short-circuit vs. post-bcrypt bad-credentials path) does not leak
	 * through {@code body.timestamp − client.sentAt}; only the request URI varies.
	 */
	public static void writeUnauthorizedAsJson(HttpServletResponse response, HttpServletRequest request) throws IOException {
		writeErrorResponseAsJson(response, ErrorResponse.builder()
				.timestamp(null)
				.status(HttpStatus.UNAUTHORIZED.value())
				.path(request.getRequestURI())
				.error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
				.build());
	}

	/**
	 * Returns a representation of {@code value} that is safe to embed in a log line:
	 * control characters (including CR/LF) are replaced with {@code ?} so attacker-supplied
	 * input cannot forge synthetic log entries, and the result is truncated so a multi-MB
	 * username cannot blow up the log appender. Truncation happens before the regex pass so
	 * the matcher only ever scans at most {@link #LOG_VALUE_MAX_LENGTH} characters — an
	 * attacker padding a login parameter with megabytes of garbage cannot force a full-input
	 * scan + intermediate StringBuilder allocation on the credential-stuffing hot path.
	 * Returns the literal string {@code "null"} when the input is null so the call site does
	 * not have to guard.
	 */
	public static String sanitizeForLog(String value) {
		if (value == null) {
			return "null";
		}
		boolean truncated = value.length() > LOG_VALUE_MAX_LENGTH;
		String head = truncated ? value.substring(0, LOG_VALUE_MAX_LENGTH) : value;
		String stripped = CONTROL_CHARS.matcher(head).replaceAll("?");
		return truncated ? stripped + "…" : stripped;
	}

	/**
	 * Clears the {@link SecurityContextHolder} and invalidates the current HTTP session if one exists.
	 * Used on SSO reject paths: the authentication filter has already persisted the token to the
	 * SecurityContext before the success handler runs, so this undoes that persistence before the
	 * rejection is redirected back to the user.
	 */
	public static void clearAuthenticatedSession(HttpServletRequest request) {
		SecurityContextHolder.clearContext();
		HttpSession session = request.getSession(false);
		if (session != null) {
			try {
				session.invalidate();
			} catch (IllegalStateException alreadyInvalidated) {
				// Concurrent request (or the container) already invalidated this session — benign.
				log.debug("Session already invalidated");
			}
		}
	}
}
