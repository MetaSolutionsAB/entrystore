package org.entrystore.rest.standalone.springboot.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;

import java.util.Date;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

	private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

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

		if (modifiedDate == null) {
			log.warn("Last-Modified header could not be set because the modification date is null");
			return responseBuilder;
		} else {
			return responseBuilder
					.lastModified(modifiedDate.getTime())
					.eTag(HttpUtil.createStrongETag(Long.toString(modifiedDate.getTime())));
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
}
