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

package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService implements CaffeineCacheSource {

	// Caps both the per-username key size and the total number of tracked entries (counters and
	// lockouts each) so an attacker streaming arbitrary usernames cannot grow either cache without
	// bound.
	private static final int MAX_USERNAME_LENGTH = 256;
	private static final long MAX_TRACKED_USERNAMES = 100_000L;

	private final Config config;
	private final PrincipalManager pm;
	private final MeterRegistry meterRegistry;

	// Pre-lockout counters live in a bounded, evictable Caffeine cache. expireAfterWrite caps the
	// lifetime relative to the last failure rather than the last read, so a probe that only reads
	// (e.g., isLockedOut) cannot extend a counter's life. A counter cannot live indefinitely under
	// repeated failures either: at maxAttempts failures it is invalidated and the lockout is recorded
	// in lockoutCache; maximumSize is the worst-case bound under a distributed username-rotation
	// attack that never tips any single counter to maxAttempts.
	private Cache<String, Integer> counterCache;

	// Lockout state lives in a separate Caffeine cache with a per-entry Expiry keyed off the
	// lockedUntil instant, so an entry auto-evicts the moment its lockout actually expires by wall
	// clock — no scheduled sweeper needed and no unbounded growth from username rotation. Splitting
	// the cache closes the cache-eviction "unlock" attack: a flood of junk-username failures can
	// evict counters (safe — eviction just resets the count) but cannot evict an active lockout
	// (Caffeine's expireAfter only evicts entries whose TTL has actually elapsed). The maximumSize
	// cap is therefore safe to apply: the only entries it would evict early are those past their
	// lockedUntil instant.
	private Cache<String, Instant> lockoutCache;

	private int maxAttempts;
	private Duration lockoutDuration;
	private boolean includeAdmin;

	// Increments whenever a call site's try/catch around recordFailure swallows a RuntimeException
	// (currently Caffeine misuse / cache faults). Exposed so operators can alert on degraded
	// lockout tracking via `rate(auth_loginattempt_record_failure_error_total[5m]) > 0` — without
	// it, the only signal is one log line per fault and sustained degradation would re-open the
	// enumeration oracle (response stays 401, lockout never trips) without any monitoring hook.
	@Getter
	private Counter recordFailureErrorCounter;

	@PostConstruct
	public void init() {
		this.maxAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		this.lockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5));
		this.includeAdmin = config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true);

		Duration ttl = lockoutDuration.isZero() ? Duration.ofMinutes(5) : lockoutDuration.multipliedBy(2);
		this.counterCache = Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(MAX_TRACKED_USERNAMES)
				.recordStats()
				.build();

		this.lockoutCache = Caffeine.newBuilder()
				.expireAfter(new Expiry<String, Instant>() {
					@Override
					public long expireAfterCreate(String key, Instant lockedUntil, long currentTime) {
						return Math.max(0L, Duration.between(Instant.now(), lockedUntil).toNanos());
					}

					@Override
					public long expireAfterUpdate(String key, Instant lockedUntil, long currentTime, long currentDuration) {
						return expireAfterCreate(key, lockedUntil, currentTime);
					}

					@Override
					public long expireAfterRead(String key, Instant lockedUntil, long currentTime, long currentDuration) {
						return currentDuration;
					}
				})
				.maximumSize(MAX_TRACKED_USERNAMES)
				.recordStats()
				.build();

		this.recordFailureErrorCounter = Counter.builder("auth.loginattempt.record_failure_error")
				.description("Login-attempt bookkeeping failures swallowed by call-site try/catch — sustained increments mean lockout tracking is degraded and the enumeration oracle may be open")
				.register(meterRegistry);
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		// Both caches are built unconditionally in init(), which runs before this bean is injected
		// into the CacheManager, so neither is null here.
		return Map.of(
				"login-attempt-counters", counterCache,
				"login-lockouts", lockoutCache);
	}

	/**
	 * Applies the input-policy gate uniformly across all four entry points: null is rejected,
	 * oversized inputs are skipped (and logged at INFO so the bypass is grep-able for forensic
	 * review), and the key is lower-cased so cache lookups are case-insensitive. Returning null
	 * means "do not process this username".
	 */
	private String normalize(String username) {
		if (username == null) {
			return null;
		}
		if (username.length() > MAX_USERNAME_LENGTH) {
			// INFO (not DEBUG) so the bypass is visible in default-level production logs: an
			// attacker padding usernames to escape lockout tracking otherwise leaves no
			// operator-visible signal.
			log.info("Skipping login attempt tracking for oversized username (len={})", username.length());
			return null;
		}
		return username.toLowerCase(Locale.ROOT);
	}

	public void recordFailure(String username) {
		if (maxAttempts <= 0 || lockoutDuration.isZero()) {
			return;
		}
		String key = normalize(username);
		if (key == null) {
			return;
		}

		// Failures are tracked for every submitted username, whether or not it resolves to an actual
		// user. Otherwise a never-existing username would be visibly absent from the lockout, leaking
		// account state. The principal lookup only happens when admin exclusion is configured.
		if (!includeAdmin) {
			Entry userEntry = pm.getPrincipalEntry(key);
			if (userEntry != null && pm.isUserAdminOrAdminGroup(userEntry.getResourceURI())) {
				log.warn("Login attempt of user [{}] is not counted towards temporary lockout because of configuration for admin users", HttpUtil.sanitizeForLog(key));
				return;
			}
		}

		log.info("Failed login attempt for [{}]", HttpUtil.sanitizeForLog(key));

		// Belt-and-braces: the lockoutCache's per-entry Expiry already auto-evicts past-lockedUntil
		// entries asynchronously. This explicit clear handles the microsecond window before async
		// eviction completes and guards against wall-clock skew between Expiry evaluation and
		// Instant.now(). Without it, the very next failure after expiry could re-lock the entry
		// immediately rather than starting a fresh counter.
		Instant existingLockout = lockoutCache.getIfPresent(key);
		if (existingLockout != null && Instant.now().isAfter(existingLockout)) {
			lockoutCache.invalidate(key);
			counterCache.invalidate(key);
		}

		Integer updated = counterCache.asMap().compute(key, (_, current) -> (current == null ? 0 : current) + 1);
		// Caffeine's compute returns null only if the remapping function returns null; ours always
		// returns a non-null Integer, so the guard is defense against an API contract change.
		if (updated != null && updated >= maxAttempts) {
			Instant lockedUntil = Instant.now().plus(lockoutDuration);
			lockoutCache.put(key, lockedUntil);
			counterCache.invalidate(key);
			log.warn("User [{}] failed too many login attempts and will be locked out until {}", HttpUtil.sanitizeForLog(key), lockedUntil);
		}
	}

	public void recordSuccess(String username) {
		String key = normalize(username);
		if (key == null) {
			return;
		}
		counterCache.invalidate(key);
		lockoutCache.invalidate(key);
	}

	public boolean isLockedOut(String username) {
		return getLockedUntil(username) != null;
	}

	public Instant getLockedUntil(String username) {
		String key = normalize(username);
		if (key == null) {
			return null;
		}
		Instant lockedUntil = lockoutCache.getIfPresent(key);
		if (lockedUntil == null) {
			return null;
		}
		if (Instant.now().isAfter(lockedUntil)) {
			log.info("User [{}] stopped being locked out", HttpUtil.sanitizeForLog(key));
			lockoutCache.invalidate(key);
			return null;
		}
		return lockedUntil;
	}
}
