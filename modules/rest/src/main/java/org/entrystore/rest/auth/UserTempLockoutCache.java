package org.entrystore.rest.auth;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserTempLockoutCache {

	private static final Logger log = LoggerFactory.getLogger(UserTempLockoutCache.class);

	private final int configAllowedFailedLoginAttempts;
	private final Duration configUserLockoutDuration;
	private final PrincipalManager pm;
	private final ConcurrentMap<String, UserTemporaryLockout> userTempLockoutMap = new ConcurrentHashMap<>();

	public UserTempLockoutCache(RepositoryManager rm, PrincipalManager pm) {
		Config config = rm.getConfiguration();
		this.pm = pm;
		this.configAllowedFailedLoginAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, -1);
		this.configUserLockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ZERO);
	}

	public void succeedLogin(String userName) {
		userTempLockoutMap.remove(userName);
	}

	public void failLogin(String userName) {
		if (configAllowedFailedLoginAttempts < 0 || configUserLockoutDuration.isZero()) {
			return;
		}

		URI userUri = pm.getPrincipalEntry(userName).getResourceURI();
		User user = pm.getUser(userUri);

		if (pm.isUserAdminOrAdminGroup(userUri)) {
			log.warn("Admin-user [{}] failed to login due to entering wrong password/secret", userName);
			return;
		}

		UserTemporaryLockout lockoutEntry =
				userTempLockoutMap.getOrDefault(userName, new UserTemporaryLockout(user, 1, null));

		if (lockoutEntry.disableUntil() != null && LocalDateTime.now().isAfter(lockoutEntry.disableUntil())) {
			new UserTemporaryLockout(user, 1, null);
		}

		if (lockoutEntry.failedLogins() < this.configAllowedFailedLoginAttempts) {
			lockoutEntry = new UserTemporaryLockout(user, lockoutEntry.failedLogins() + 1, null);
		} else {
			LocalDateTime lockedOutUntil = LocalDateTime.now().plus(configUserLockoutDuration);
			lockoutEntry = new UserTemporaryLockout(user, lockoutEntry.failedLogins() + 1, lockedOutUntil);
		}
		userTempLockoutMap.put(userName, lockoutEntry);
		pm.setAuthenticatedUserURI(null);
	}

	public boolean userIsLockedOut(String userName) {
		return getLockedOutUser(userName) != null;
	}

	public UserTemporaryLockout getLockedOutUser(String userName) {
		UserTemporaryLockout lockedOutUser = userTempLockoutMap.get(userName);
		if (lockedOutUser == null || lockedOutUser.disableUntil() == null) {
			return null;
		} else if (LocalDateTime.now().isAfter(lockedOutUser.disableUntil())) {
			log.info("User [{}] stopped being locked out", userName);
			userTempLockoutMap.remove(userName);
			return null;
		} else {
			return lockedOutUser;
		}
	}

	public List<UserTemporaryLockout> getLockedOutUsers() {
		List<UserTemporaryLockout> lockedOutUsers = userTempLockoutMap.entrySet().stream()
				.filter(failedLoginCount -> userIsLockedOut(failedLoginCount.getValue().user().getName()))
				.map(Map.Entry::getValue)
				.collect(Collectors.toList());
		return lockedOutUsers;
	}

	public record UserTemporaryLockout(User user, int failedLogins, LocalDateTime disableUntil) {}

}
