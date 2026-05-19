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

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	// Caps both the per-username key size and the total number of tracked entries so an attacker
	// streaming arbitrary usernames cannot grow the lockout map without bound.
	private static final int MAX_USERNAME_LENGTH = 256;
	private static final long MAX_TRACKED_USERNAMES = 100_000L;

	private final Config config;
	private final PrincipalManager pm;

	private Cache<String, LoginAttemptInfo> attemptCache;

	private int maxAttempts;
	private Duration lockoutDuration;
	private boolean includeAdmin;

	@PostConstruct
	public void init() {
		this.maxAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		this.lockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5));
		this.includeAdmin = config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true);

		// expireAfterAccess ages out idle entries (including non-locked counters that would
		// otherwise persist forever); maximumSize caps the worst case under attacker traffic.
		Duration ttl = lockoutDuration.isZero() ? Duration.ofMinutes(5) : lockoutDuration.multipliedBy(2);
		this.attemptCache = Caffeine.newBuilder()
				.expireAfterAccess(ttl)
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

		attemptCache.asMap().compute(key, (_, current) -> {
			int previousFailures = 0;
			if (current != null && (current.lockedUntil() == null || !Instant.now().isAfter(current.lockedUntil()))) {
				previousFailures = current.failedAttempts();
			}

			int newCount = previousFailures + 1;
			if (newCount >= maxAttempts) {
				Instant lockedUntil = Instant.now().plus(lockoutDuration);
				log.warn("User [{}] failed too many login attempts and will be locked out until {}", HttpUtil.sanitizeForLog(key), lockedUntil);
				return new LoginAttemptInfo(newCount, lockedUntil);
			}
			return new LoginAttemptInfo(newCount, null);
		});
	}

	public void recordSuccess(String username) {
		if (username == null) {
			return;
		}
		attemptCache.invalidate(username.toLowerCase(Locale.ROOT));
	}

	public boolean isLockedOut(String username) {
		return getLockedUntil(username) != null;
	}

	public Instant getLockedUntil(String username) {
		if (username == null) {
			return null;
		}
		String key = username.toLowerCase(Locale.ROOT);
		LoginAttemptInfo info = attemptCache.getIfPresent(key);
		if (info == null || info.lockedUntil() == null) {
			return null;
		}
		if (Instant.now().isAfter(info.lockedUntil())) {
			log.info("User [{}] stopped being locked out", HttpUtil.sanitizeForLog(key));
			attemptCache.asMap().remove(key, info);
			return null;
		}
		return info.lockedUntil();
	}

	public record LoginAttemptInfo(int failedAttempts, Instant lockedUntil) {}
}
