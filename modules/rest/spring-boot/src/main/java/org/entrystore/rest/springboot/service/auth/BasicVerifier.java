/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.service.auth;

import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;

import java.net.URI;


/**
 * Does a simple lookup for the secret of a principal.
 *
 * @author Hannes Ebner
 */
@Slf4j
public class BasicVerifier {

	public static String getSaltedHashedSecret(PrincipalManager pm, String identifier) {
		URI authUser = pm.getAuthenticatedUserURI();
		Throwable primary = null;
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(identifier);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSaltedHashedSecret() != null) {
					return user.getSaltedHashedSecret();
				} else {
					log.error("No secret found for principal: {}", identifier);
				}
			}
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, authUser, primary);
		}

		return null;
	}

	public static boolean isUserDisabled(PrincipalManager pm, User user) {
		URI currentUser = pm.getAuthenticatedUserURI();
		Throwable primary = null;
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			return user.isDisabled();
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, currentUser, primary);
		}
	}
}
