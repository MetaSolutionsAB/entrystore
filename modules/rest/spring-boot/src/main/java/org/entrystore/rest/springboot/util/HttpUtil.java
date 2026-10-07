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
import jakarta.servlet.http.HttpSession;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.filter.CacheControlFilter;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

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

	/**
	 * Sets the headers of an entry or resource representation that let a GET or HEAD be revalidated with 304:
	 * Last-Modified and ETag from the entry's modification date, and {@code Vary} on the credentials, since the
	 * representation depends on the user, plus on Accept where it selects the representation. A response that
	 * {@code CacheControlFilter} did not mark as authenticated gets {@link CacheControlFilter#CACHE_CONTROL_ANONYMOUS},
	 * so guests revalidate too. Spring adds these {@code Vary} values to those already on the response, such as the
	 * CORS ones.
	 */
	public static void setRevalidationHeaders(HttpHeaders headers, HttpServletResponse response, Date modifiedDate,
											  boolean variesWithAccept) {
		setLastModifiedAndETag(headers, modifiedDate);
		headers.setVary(variesWithAccept
				? List.of(HttpHeaders.ACCEPT, HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION)
				: List.of(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION));
		if (response.getHeader(HttpHeaders.CACHE_CONTROL) == null) {
			headers.setCacheControl(CacheControlFilter.CACHE_CONTROL_ANONYMOUS);
		}
	}

	/**
	 * Like {@link #setRevalidationHeaders}, for an entry representation reduced to what a caller who may read neither
	 * the entry's metadata nor its resource may see. Its ETag is the date ETag with {@code -r} appended, so a caller
	 * who gains or loses read access while the entry's modification date stays is not answered with 304.
	 */
	public static void setReducedRevalidationHeaders(HttpHeaders headers, HttpServletResponse response,
													 Date modifiedDate, boolean variesWithAccept) {
		setRevalidationHeaders(headers, response, modifiedDate, variesWithAccept);
		if (modifiedDate != null) {
			headers.setETag(createStrongETag(modifiedDate.getTime() + "-r"));
		}
	}

	/**
	 * Like {@link #setRevalidationHeaders}, but with an ETag computed from {@code content}, for a representation that
	 * embeds other entries or the caller's rights and so can change while the entry's modification date does not.
	 * Last-Modified stays the entry's date: a client revalidating with If-Modified-Since alone can still get a 304
	 * for changed content, as in 5.x, while browsers send If-None-Match, which takes precedence.
	 */
	public static void setContentRevalidationHeaders(HttpHeaders headers, HttpServletResponse response,
													 Date modifiedDate, byte[] content, boolean variesWithAccept) {
		setRevalidationHeaders(headers, response, modifiedDate, variesWithAccept);
		headers.setETag(createStrongETag(DigestUtils.md5DigestAsHex(content)));
	}

	/**
	 * Whether a {@code Range} request may be answered from the current representation (RFC 9110, section 13.1.5):
	 * without {@code If-Range}, or when it names the current strong ETag or exactly the second of Last-Modified.
	 * Otherwise the full representation must be sent.
	 *
	 * @param ifRange      the {@code If-Range} header, or null
	 * @param modifiedDate the date the response's validators are built from, see {@link #setLastModifiedAndETag}
	 */
	public static boolean ifRangeMatches(String ifRange, Date modifiedDate) {
		if (ifRange == null) {
			return true;
		}
		if (modifiedDate == null) {
			return false;
		}
		String value = ifRange.trim();
		if (value.startsWith("\"") || value.startsWith("W/")) {
			return value.equals(createStrongETag(Long.toString(modifiedDate.getTime())));
		}
		try {
			long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
			return date == modifiedDate.getTime() / 1000 * 1000;
		} catch (DateTimeParseException e) {
			return false;
		}
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

	public static void checkRequestSize(HttpServletRequest request, int maxRequestSize) {
		if (HttpUtil.isLargerThan(request, maxRequestSize)) {
			throw new EntityTooLargeException("The size of the representation is larger than " + maxRequestSize + "bytes or unknown, request blocked.");
		}
	}

	/** How much of a rejected request body {@link #discardRejectedBody} reads at most. */
	public static final long MAX_DRAIN_BYTES = 4L * 1024 * 1024;

	/**
	 * Reads and discards up to {@link #MAX_DRAIN_BYTES} of a rejected request body, so that a client that reads the
	 * response only once it has sent a moderately oversized body, like {@code HttpURLConnection} or a piped Node
	 * stream, gets the error response instead of a connection reset. Skipped after {@code Expect: 100-continue},
	 * whose client has not sent the body and would be asked for it.
	 */
	public static void discardRejectedBody(HttpServletRequest request) {
		if ("100-continue".equalsIgnoreCase(request.getHeader(HttpHeaders.EXPECT))) {
			return;
		}
		try {
			InputStream in = request.getInputStream();
			byte[] buf = new byte[8192];
			long drained = 0;
			int read;
			while (drained < MAX_DRAIN_BYTES && (read = in.read(buf)) != -1) {
				drained += read;
			}
		} catch (IOException e) {
			log.debug("Client went away while its rejected request body was discarded: {}", e.getMessage());
		}
	}

	/** Whether the request declares a {@code multipart/form-data} body. */
	public static boolean isMultipart(HttpServletRequest request) {
		String contentType = request.getContentType();
		return contentType != null && contentType.regionMatches(true, 0, MediaType.MULTIPART_FORM_DATA_VALUE, 0,
				MediaType.MULTIPART_FORM_DATA_VALUE.length());
	}

	/**
	 * The first value of query parameter {@code name}, an empty string for a bare {@code ?name}, or null.
	 * Unlike {@code getParameter} it never reads the body, which for a multipart request would make Jetty
	 * parse the whole upload to temporary files before any access check. A pair with malformed
	 * percent-encoding is skipped, so it neither fails the request nor hides the other pairs.
	 */
	public static String getQueryParameter(HttpServletRequest request, String name) {
		String query = request.getQueryString();
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			try {
				String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
				if (key.equals(name)) {
					return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				}
			} catch (IllegalArgumentException e) {
				log.debug("Skipping query parameter with malformed encoding: {}", sanitizeForLog(pair));
			}
		}
		return null;
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
