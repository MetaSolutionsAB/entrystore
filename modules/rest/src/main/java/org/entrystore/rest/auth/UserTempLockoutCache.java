package org.entrystore.rest.auth;

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;

public class UserTempLockoutCache {

	private static final Logger log = LoggerFactory.getLogger(UserTempLockoutCache.class);

	private final int configAllowedFailedLoginAttempts;
	private final Duration configUserLockoutDuration;
	private final boolean configIncludeAdmin;
	private final PrincipalManager pm;
	private final ConcurrentMap<String, UserTemporaryLockout> userTempLockoutMap = new ConcurrentHashMap<>();

	public UserTempLockoutCache(RepositoryManager rm, PrincipalManager pm) {
		Config config = rm.getConfiguration();
		this.pm = pm;
		this.configAllowedFailedLoginAttempts = config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		this.configUserLockoutDuration = config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5));
		this.configIncludeAdmin = config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true);
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

		log.info("User [{}] failed login attempt due to providing wrong password", userName);

		if (pm.isUserAdminOrAdminGroup(userUri) && !configIncludeAdmin) {
			log.warn("Login attempt of user [{}] is not counted towards temporary lockout because of configuration for admin users", userName);
			return;
		}

		UserTemporaryLockout lockoutEntry = userTempLockoutMap.getOrDefault(userName, new UserTemporaryLockout(user, 1, null));

		if (lockoutEntry.disableUntil() != null && LocalDateTime.now().isAfter(lockoutEntry.disableUntil())) {
			new UserTemporaryLockout(user, 1, null);
		}

		if (lockoutEntry.failedLogins() < this.configAllowedFailedLoginAttempts) {
			lockoutEntry = new UserTemporaryLockout(user, lockoutEntry.failedLogins() + 1, null);
		} else {
			LocalDateTime lockedOutUntil = LocalDateTime.now().plus(configUserLockoutDuration);
			lockoutEntry = new UserTemporaryLockout(user, lockoutEntry.failedLogins() + 1, lockedOutUntil);
			log.warn("User [{}] failed too many login attempts and will be locked out until {}", userName, lockedOutUntil);
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