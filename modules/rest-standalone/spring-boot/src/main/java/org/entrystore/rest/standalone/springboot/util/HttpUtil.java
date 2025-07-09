package org.entrystore.rest.standalone.springboot.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

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
