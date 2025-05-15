package org.entrystore.rest.standalone.springboot.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.UserAuthRole;
import org.entrystore.rest.standalone.springboot.service.UserService;
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
