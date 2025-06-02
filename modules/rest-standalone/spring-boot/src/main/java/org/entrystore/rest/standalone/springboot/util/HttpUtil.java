package org.entrystore.rest.standalone.springboot.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpUtil {

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


	public static boolean isLargerThan(HttpServletRequest r, long maxSize) {
		if (r == null) {
			return false;
		}
		long repSize = r.getContentLength();
		if (repSize == -1L) {
			log.warn("Size of representation is unknown");
			return true;
		} else return repSize > maxSize;
	}
}
