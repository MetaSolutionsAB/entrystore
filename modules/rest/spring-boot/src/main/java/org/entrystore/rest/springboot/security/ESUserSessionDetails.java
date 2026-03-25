package org.entrystore.rest.springboot.security;

import lombok.Getter;
import lombok.Setter;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@Setter
public class ESUserSessionDetails extends org.springframework.security.core.userdetails.User {

	private final User esUser;
	private SessionInfo sessionInfo;

	public ESUserSessionDetails(UserDetails userDt, User esUser, SessionInfo sessionInfo) {

		super(userDt.getUsername(), userDt.getPassword(), userDt.isEnabled(), userDt.isAccountNonExpired(),
				userDt.isCredentialsNonExpired(), userDt.isAccountNonLocked(), userDt.getAuthorities());
		this.esUser = esUser;
		this.sessionInfo = sessionInfo;
	}
}
