package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	// Caps both the per-username key size and the total number of tracked counters so an attacker
	// streaming arbitrary usernames cannot grow the lockout map without bound. The locked-until
	// map is unbounded by design (see {@link #lockoutMap}).
	private static final int MAX_USERNAME_LENGTH = 256;
	private static final long MAX_TRACKED_USERNAMES = 100_000L;

	private final Config config;
	private final PrincipalManager pm;

	// Pre-lockout counters live in a bounded, evictable Caffeine cache. expireAfterWrite (not access)
	// pins the lifetime to the write time so a continuously-probed counter cannot have its TTL
	// refreshed indefinitely; the entry ages out lockoutDuration*2 after the last failure, by which
	// point any in-progress attack window has already elapsed.
	private Cache<String, Integer> counterCache;

	// Lockout state lives in a separate, unevictable map. Splitting this out closes the cache-eviction
	// "unlock" attack: with a single bounded cache holding both counters and lockedUntil, an attacker
	// could flood the cache with junk usernames and evict an entry whose lockout was about to fire (or
	// already had). A locked entry will always be visible until it actually expires by wall clock.
	// Memory is bounded by the rate at which an attacker can successfully push entries to lockout
	// (maxAttempts failures each) over a single lockoutDuration window, plus the per-entry footprint
	// is just a String key and an Instant — under realistic attack rates this stays well under MB-scale.
	private final ConcurrentMap<String, Instant> lockoutMap = new ConcurrentHashMap<>();

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

		// If a previous lockout has expired by wall clock, clear it before recounting so the next
		// failure starts a fresh counter rather than tipping the entry immediately back to locked.
		Instant existingLockout = lockoutMap.get(key);
		if (existingLockout != null && Instant.now().isAfter(existingLockout)) {
			lockoutMap.remove(key, existingLockout);
			counterCache.invalidate(key);
		}

		Integer updated = counterCache.asMap().compute(key, (_, current) -> (current == null ? 0 : current) + 1);
		if (updated != null && updated >= maxAttempts) {
			Instant lockedUntil = Instant.now().plus(lockoutDuration);
			lockoutMap.put(key, lockedUntil);
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
		lockoutMap.remove(key);
	}

	public boolean isLockedOut(String username) {
		return getLockedUntil(username) != null;
	}

	public Instant getLockedUntil(String username) {
		if (username == null) {
			return null;
		}
		String key = username.toLowerCase(Locale.ROOT);
		Instant lockedUntil = lockoutMap.get(key);
		if (lockedUntil == null) {
			return null;
		}
		if (Instant.now().isAfter(lockedUntil)) {
			log.info("User [{}] stopped being locked out", HttpUtil.sanitizeForLog(key));
			lockoutMap.remove(key, lockedUntil);
			return null;
		}
		return lockedUntil;
	}
}
