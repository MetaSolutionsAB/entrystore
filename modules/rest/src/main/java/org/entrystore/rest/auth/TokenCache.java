package org.entrystore.rest.auth;

import java.util.HashMap;
import java.util.Map;

public class TokenCache {
	
	private static Map<String, UserInfo> tokenCache = new HashMap<String, UserInfo>();

	public static void addToken(String token, UserInfo userInfo) {
		tokenCache.put(token, userInfo);
	}
	
	public static UserInfo getUserInfo(String token) {
		return tokenCache.get(token);
	}
	
	public static void removeToken(String token) {
		tokenCache.remove(token);
	}
	
	public static boolean hasToken(String token) {
		return tokenCache.containsKey(token);
	}

}