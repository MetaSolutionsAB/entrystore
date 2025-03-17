package org.entrystore.rest.standalone.springboot.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.UserAuthRole;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@RequiredArgsConstructor
@Service
public class ESUserDetailsService implements UserDetailsService {

	private final PrincipalManager pm;

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

	private UserDetails mapESUserToUserDetails(User user) {

		return org.springframework.security.core.userdetails.User
			.withUsername(user.getName())
			.password(user.getSaltedHashedSecret())
			.disabled(user.isDisabled())
			.roles(isAdmin(user) ? UserAuthRole.ADMIN.name() : UserAuthRole.USER.name())
			.build();
	}

	private boolean isAdmin(User user) {
		return pm.getAdminUser().getURI().equals(user.getURI()) ||
			pm.getAdminGroup().isMember(user);
	}
}
