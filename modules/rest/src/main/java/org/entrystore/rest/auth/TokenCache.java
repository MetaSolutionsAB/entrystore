/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Hannes Ebner
 */
public abstract class TokenCache<K, V> {

	final protected Map<K, V> tokenCache = new ConcurrentHashMap<>();

	public void putToken(K token, V value) {
		synchronized (tokenCache) {
			tokenCache.put(token, value);
		}
	}

	public V getTokenValue(K token) {
		cleanup();
		return tokenCache.get(token);
	}

	public void removeToken(K token) {
		cleanup();
		synchronized (tokenCache) {
			tokenCache.remove(token);
		}
	}

	public boolean hasToken(K token) {
		cleanup();
		return tokenCache.containsKey(token);
	}

	abstract void cleanup();

	public int size() {
		cleanup();
		return tokenCache.size();
	}

}