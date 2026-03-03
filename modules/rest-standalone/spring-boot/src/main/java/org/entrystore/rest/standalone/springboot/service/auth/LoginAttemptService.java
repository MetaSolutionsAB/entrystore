package org.entrystore.rest.standalone.springboot.service.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	private final Config config;
	private final PrincipalManager pm;

	private final ConcurrentMap<String, LoginAttemptInfo> attemptMap = new ConcurrentHashMap<>();

	private int maxAttempts;
	private Duration lockoutDuration;
	private boolean includeAdmin;

	@PostConstruct
	public void init() {
		this.maxAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		this.lockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5));
		this.includeAdmin = config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true);
	}

	public void recordFailure(String username) {
		if (maxAttempts <= 0 || lockoutDuration.isZero()) {
			return;
		}

		Entry userEntry = pm.getPrincipalEntry(username);
		if (userEntry == null) {
			log.warn("Login attempt failed, user does not exist: {}", username);
			return;
		}

		log.info("User [{}] failed login attempt due to providing wrong password", username);

		if (!includeAdmin && pm.isUserAdminOrAdminGroup(userEntry.getResourceURI())) {
			log.warn("Login attempt of user [{}] is not counted towards temporary lockout because of configuration for admin users", username);
			return;
		}

		attemptMap.compute(username, (key, current) -> {
			int previousFailures = 0;
			if (current != null && (current.lockedUntil() == null || !Instant.now().isAfter(current.lockedUntil()))) {
				previousFailures = current.failedAttempts();
			}

			int newCount = previousFailures + 1;
			if (newCount >= maxAttempts) {
				Instant lockedUntil = Instant.now().plus(lockoutDuration);
				log.warn("User [{}] failed too many login attempts and will be locked out until {}", username, lockedUntil);
				return new LoginAttemptInfo(newCount, lockedUntil);
			}
			return new LoginAttemptInfo(newCount, null);
		});
	}

	public void recordSuccess(String username) {
		attemptMap.remove(username);
	}

	public boolean isLockedOut(String username) {
		return getLockedUntil(username) != null;
	}

	public Instant getLockedUntil(String username) {
		LoginAttemptInfo info = attemptMap.get(username);
		if (info == null || info.lockedUntil() == null) {
			return null;
		}
		if (Instant.now().isAfter(info.lockedUntil())) {
			log.info("User [{}] stopped being locked out", username);
			attemptMap.remove(username, info);
			return null;
		}
		return info.lockedUntil();
	}

	public record LoginAttemptInfo(int failedAttempts, Instant lockedUntil) {}
}
