package org.entrystore.rest.auth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
public abstract class TokenCache<K, V> {

	protected Map<K, V> tokenCache = Collections.synchronizedMap(new HashMap<K, V>());

	public void addToken(K token, V value) {
		tokenCache.put(token, value);
	}

	public V getTokenValue(K token) {
		cleanup();
		return tokenCache.get(token);
	}

	public void removeToken(K token) {
		cleanup();
		tokenCache.remove(token);
	}

	public boolean hasToken(K token) {
		cleanup();
		return tokenCache.containsKey(token);
	}

	abstract void cleanup();

}