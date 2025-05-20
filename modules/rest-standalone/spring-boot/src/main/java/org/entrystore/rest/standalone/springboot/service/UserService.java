package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

	private final PrincipalManager principalManager;

	public boolean isAdmin(User user) {
		return principalManager.getAdminUser().getURI().equals(user.getURI()) ||
			principalManager.getAdminGroup().isMember(user);
	}

}
