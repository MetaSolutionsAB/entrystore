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

package org.entrystore.rest.springboot.configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheRegistryConfigurationTest {

	private final CacheRegistryConfiguration config = new CacheRegistryConfiguration();

	@Test
	void registersEveryCacheFromEverySource() {
		Cache<?, ?> alpha = Caffeine.newBuilder().build();
		Cache<?, ?> beta = Caffeine.newBuilder().build();
		Cache<?, ?> gamma = Caffeine.newBuilder().build();
		CaffeineCacheSource first = () -> Map.of("alpha", alpha);
		CaffeineCacheSource second = () -> Map.of("beta", beta, "gamma", gamma);

		CacheManager manager = config.cacheManager(List.of(first, second), plainEncoder());

		assertEquals(3, manager.getCacheNames().size());
		assertTrue(manager.getCacheNames().containsAll(List.of("alpha", "beta", "gamma")));
		assertNotNull(manager.getCache("alpha"));
		assertNotNull(manager.getCache("beta"));
		assertNotNull(manager.getCache("gamma"));
	}

	@Test
	void sourceWithNoLiveCacheContributesNothing() {
		Cache<?, ?> live = Caffeine.newBuilder().build();
		CaffeineCacheSource enabled = () -> Map.of("live", live);
		// Mirrors a rate limiter disabled by configuration: it holds no cache and returns an empty map.
		CaffeineCacheSource disabled = Map::of;

		CacheManager manager = config.cacheManager(List.of(enabled, disabled), plainEncoder());

		assertEquals(1, manager.getCacheNames().size());
		assertTrue(manager.getCacheNames().contains("live"));
	}

	@Test
	void doesNotServeCachesThatWereNeverRegistered() {
		Cache<?, ?> alpha = Caffeine.newBuilder().build();
		CaffeineCacheSource source = () -> Map.of("alpha", alpha);

		CacheManager manager = config.cacheManager(List.of(source), plainEncoder());

		// Non-dynamic: an unknown name must not be auto-created into an empty cache.
		assertNull(manager.getCache("never-registered"));
	}

	@Test
	void registersCacheFromPasswordEncoderEvenThoughItIsInjectedAsPasswordEncoder() {
		// CachingPasswordEncoder is published under type PasswordEncoder, so it arrives via the
		// passwordEncoder parameter rather than the List<CaffeineCacheSource>.
		CachingEncoderDouble encoder = new CachingEncoderDouble();

		CacheManager manager = config.cacheManager(List.of(), encoder);

		assertEquals(1, manager.getCacheNames().size());
		assertNotNull(manager.getCache("password-verification"));
	}

	@Test
	void plainPasswordEncoderAddsNoCacheAndDoesNotFail() {
		Cache<?, ?> alpha = Caffeine.newBuilder().build();
		CaffeineCacheSource source = () -> Map.of("alpha", alpha);

		CacheManager manager = config.cacheManager(List.of(source), plainEncoder());

		assertEquals(1, manager.getCacheNames().size());
		assertTrue(manager.getCacheNames().contains("alpha"));
	}

	@Test
	void duplicateCacheNameKeepsTheFirstRegistrationAndDoesNotThrow() {
		Cache<Object, Object> winner = Caffeine.newBuilder().build();
		Cache<Object, Object> loser = Caffeine.newBuilder().build();
		winner.put("k", "from-winner");
		loser.put("k", "from-loser");
		CaffeineCacheSource firstWins = () -> Map.of("collision", winner);
		CaffeineCacheSource secondLoses = () -> Map.of("collision", loser);

		CacheManager manager = config.cacheManager(List.of(firstWins, secondLoses), plainEncoder());

		assertEquals(1, manager.getCacheNames().size());
		// The retained cache is the first source's, so the value seen through the manager is the winner's.
		assertEquals("from-winner", manager.getCache("collision").get("k").get());
	}

	@Test
	void throwsWhenSourceReturnsNullMap() {
		CaffeineCacheSource nullSource = () -> null;

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> config.cacheManager(List.of(nullSource), plainEncoder()));
		assertTrue(ex.getMessage().contains("caffeineCaches()"));
	}

	@Test
	void throwsWhenSourceMapContainsNullCache() {
		Map<String, Cache<?, ?>> withNull = new HashMap<>();
		withNull.put("broken", null);
		CaffeineCacheSource source = () -> withNull;

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> config.cacheManager(List.of(source), plainEncoder()));
		assertTrue(ex.getMessage().contains("Null cache for name 'broken'"));
	}

	@Test
	void passwordEncoderAlreadyInSourceListIsNotRegisteredTwice() {
		CachingEncoderDouble encoder = new CachingEncoderDouble();

		CacheManager manager = config.cacheManager(List.of(encoder), encoder);

		assertEquals(1, manager.getCacheNames().size());
		assertTrue(manager.getCacheNames().contains("password-verification"));
	}

	private static PasswordEncoder plainEncoder() {
		return new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return rawPassword.toString();
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return true;
			}
		};
	}

	private static final class CachingEncoderDouble implements PasswordEncoder, CaffeineCacheSource {

		private final Cache<Object, Object> verifications = Caffeine.newBuilder().build();

		@Override
		public Map<String, Cache<?, ?>> caffeineCaches() {
			return Map.of("password-verification", verifications);
		}

		@Override
		public String encode(CharSequence rawPassword) {
			return rawPassword.toString();
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			return true;
		}
	}
}
