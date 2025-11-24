package org.entrystore.rest.standalone.springboot.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.standalone.springboot.service.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * ESUserDetailsService is a Spring Security {@link UserDetailsService} implementation
 * responsible for loading user-specific data during authentication. This class is
 * tightly coupled with the security mechanism of the application and provides
 * a way to retrieve and map user details from the application's data store.
 * <p>
 * The `loadUserByUsername` method will be called during form login to check if
 * a given logging-in user has correct credentials.
 * <p>
 * It performs the following primary functionalities:
 * - Fetches the user details from the data store (PrincipalManager) based on the username.
 * - Maps the retrieved user information to a Spring Security compatible {@link UserDetails} object.
 * <p>
 * Dependencies:
 * - {@link PrincipalManager}: Manages user and principal data.
 * - {@link UserService}: Provides utility methods for user-related operations including
 * admin role checks.
 * <p>
 * Exception Handling:
 * - Throws {@link UsernameNotFoundException} if the user is not found in the data store
 * or their credentials are invalid.
 *
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ESUserDetailsService implements UserDetailsService {

	private final PrincipalManager pm;
	private final UserService userService;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

		final URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(username);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSaltedHashedSecret() != null) {
					return mapESUserToUserDetails(user);
				} else {
					log.error("No secret found for user: '{}'", username);
				}
			} else {
				log.info("User Entry not found for username: '{}'", username);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		throw new UsernameNotFoundException("User not found " + username);
	}

	/**
	 * Loads Entrystore User by username using PrincipalManager
	 *
	 * @param username User entity name to be loaded
	 * @return Entrystore User
	 */
	public User loadUser(String username) {

		final URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(username);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				return ((User) userEntry.getResource());
			} else {
				log.info("User Entry not found for username: '{}'", username);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		return null;
	}

	private UserDetails mapESUserToUserDetails(User user) {

		UserDetails userDetails = org.springframework.security.core.userdetails.User
				.withUsername(user.getName())
				.password(user.getSaltedHashedSecret())
				.disabled(user.isDisabled())
				.roles(userService.isAdmin(user) ? UserAuthRole.ADMIN.name() : UserAuthRole.USER.name())
				.build();

		return new ESUserDetails(userDetails, user);
	}
}
