package org.entrystore.rest.standalone.springboot.security;

import lombok.Getter;
import org.entrystore.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class ESUserDetails extends org.springframework.security.core.userdetails.User {

	private final User esUser;


	public ESUserDetails(UserDetails userDt, User esUser) {

		super(userDt.getUsername(), userDt.getPassword(), userDt.isEnabled(), userDt.isAccountNonExpired(),
			userDt.isCredentialsNonExpired(), userDt.isAccountNonLocked(), userDt.getAuthorities());
		this.esUser = esUser;
	}

	public ESUserDetails(String username, String password, Collection<? extends GrantedAuthority> authorities, User esUser) {

		super(username, password, authorities);
		this.esUser = esUser;
	}

	public ESUserDetails(String username, String password, boolean enabled, boolean accountNonExpired, boolean credentialsNonExpired,
						 boolean accountNonLocked, Collection<? extends GrantedAuthority> authorities, User esUser) {

		super(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
		this.esUser = esUser;
	}
}
