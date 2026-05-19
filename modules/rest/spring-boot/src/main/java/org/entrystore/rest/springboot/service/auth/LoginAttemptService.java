package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	// Caps both the per-username key size and the total number of tracked entries (counters and
	// lockouts each) so an attacker streaming arbitrary usernames cannot grow either cache without
	// bound.
	private static final int MAX_USERNAME_LENGTH = 256;
	private static final long MAX_TRACKED_USERNAMES = 100_000L;

	private final Config config;
	private final PrincipalManager pm;

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

	@PostConstruct
	public void init() {
		this.maxAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		this.lockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5));
		this.includeAdmin = config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true);

		Duration ttl = lockoutDuration.isZero() ? Duration.ofMinutes(5) : lockoutDuration.multipliedBy(2);
		this.counterCache = Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(MAX_TRACKED_USERNAMES)
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
				.build();
	}

	public void recordFailure(String username) {
		if (maxAttempts <= 0 || lockoutDuration.isZero()) {
			return;
		}
		if (username == null) {
			return;
		}
		if (username.length() > MAX_USERNAME_LENGTH) {
			log.debug("Skipping login attempt tracking for oversized username (len={})", username.length());
			return;
		}
		String key = username.toLowerCase(Locale.ROOT);

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

		// Clear any expired lockout so the next failure starts a fresh counter rather than re-locking
		// the entry immediately. The Expiry would also evict the stale entry asynchronously, but we
		// remove it deterministically here so the compute below can observe a clean slate.
		Instant existingLockout = lockoutCache.getIfPresent(key);
		if (existingLockout != null && Instant.now().isAfter(existingLockout)) {
			lockoutCache.invalidate(key);
			counterCache.invalidate(key);
		}

		Integer updated = counterCache.asMap().compute(key, (_, current) -> (current == null ? 0 : current) + 1);
		if (updated != null && updated >= maxAttempts) {
			Instant lockedUntil = Instant.now().plus(lockoutDuration);
			lockoutCache.put(key, lockedUntil);
			counterCache.invalidate(key);
			log.warn("User [{}] failed too many login attempts and will be locked out until {}", HttpUtil.sanitizeForLog(key), lockedUntil);
		}
	}

	public void recordSuccess(String username) {
		if (username == null) {
			return;
		}
		String key = username.toLowerCase(Locale.ROOT);
		counterCache.invalidate(key);
		lockoutCache.invalidate(key);
	}

	public boolean isLockedOut(String username) {
		return getLockedUntil(username) != null;
	}

	public Instant getLockedUntil(String username) {
		if (username == null) {
			return null;
		}
		String key = username.toLowerCase(Locale.ROOT);
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
