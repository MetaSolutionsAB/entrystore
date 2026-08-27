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

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

	private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

	/**
	 * Determines the media type based on the provided format parameter or content type header.
	 *
	 * @param format The format parameter from the request, bound via {@code MediaTypeConverter}.
	 * @param contentType The raw content-type header string from the request.
	 * @return The format parameter's string form if present (parameters preserved), otherwise the
	 *         normalized content type (type/subtype only), or null if neither can be determined.
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
	 * If the modification date is null, a debug message is logged and the headers are not updated.
	 *
	 * <p>Debug rather than warn: an entry whose graph carries no {@code dcterms:modified} is a data
	 * property rather than a server fault, and the unauthenticated read callers (the relation, lookup
	 * and metadata GETs) sit on endpoints anyone can request in a loop — at warn, one such entry lets
	 * an anonymous client drive unbounded log volume. The write callers (the context and group POSTs,
	 * the entry and metadata PUTs) share this helper and are knowingly silenced along with them, even
	 * though for those a null date after a successful write is a server-side invariant violation
	 * rather than a data property.
	 *
	 * @param headers      the headers to update
	 * @param modifiedDate the modification date used for generating the headers
	 */
	public static void setLastModifiedAndETag(HttpHeaders headers, Date modifiedDate) {
		if (modifiedDate == null) {
			// debug, not warn — rationale in the Javadoc above.
			log.debug("Last-Modified and ETag omitted because the modification date is null");
		} else {
			headers.setLastModified(modifiedDate.getTime());
			headers.setETag(createStrongETag(Long.toString(modifiedDate.getTime())));
		}
	}

	/**
	 * Updates the response headers with the last modification date and a strong ETag
	 * based on the provided modification date.
	 * A null modification date is logged at debug and the headers are not updated, exactly as in
	 * {@link #setLastModifiedAndETag(HttpHeaders, Date)}, which this method delegates to and whose
	 * Javadoc carries the rationale for the log level.
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

	public static void checkRequestSize(HttpServletRequest request, int maxRequestSize) {
		if (HttpUtil.isLargerThan(request, maxRequestSize)) {
			throw new EntityTooLargeException("The size of the representation is larger than " + maxRequestSize + "bytes or unknown, request blocked.");
		}
	}

	private static final int LOG_VALUE_MAX_LENGTH = 128;
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

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
