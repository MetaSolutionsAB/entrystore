package org.entrystore.rest.standalone.springboot.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

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
}
