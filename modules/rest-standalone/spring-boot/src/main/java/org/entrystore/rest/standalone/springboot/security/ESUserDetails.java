package org.entrystore.rest.standalone.springboot.security;

import lombok.Getter;
import org.entrystore.User;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
public class ESUserDetails extends org.springframework.security.core.userdetails.User {

	private final User esUser;

	public ESUserDetails(UserDetails userDt, User esUser) {

		super(userDt.getUsername(), userDt.getPassword(), userDt.isEnabled(), userDt.isAccountNonExpired(),
			userDt.isCredentialsNonExpired(), userDt.isAccountNonLocked(), userDt.getAuthorities());
		this.esUser = esUser;
	}
}
