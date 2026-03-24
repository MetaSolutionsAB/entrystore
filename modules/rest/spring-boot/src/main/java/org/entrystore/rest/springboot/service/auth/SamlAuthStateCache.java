package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * A cache service for storing and retrieving SAML authentication relay state. This service
 * is designed to handle temporary storage of authentication states associated with
 * unique identifiers, expiring entries after a fixed duration.
 *
 */
@Service
public class SamlAuthStateCache {

	private final Cache<String, AuthState> requestCache = Caffeine.newBuilder()
			.expireAfterWrite(2, TimeUnit.MINUTES)
			.build();

	public AuthState getAuthState(String id) {
		return requestCache.getIfPresent(id);
	}

	public void storeAuthState(String id, AuthState authState) {
		requestCache.put(id, authState);
	}
}
