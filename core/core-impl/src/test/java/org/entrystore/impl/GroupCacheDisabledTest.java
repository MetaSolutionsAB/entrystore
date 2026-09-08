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

package org.entrystore.impl;

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code entrystore.auth.group-cache=false} is the kill switch: decisions must stay correct and the cache must
 * stay unused.
 */
public class GroupCacheDisabledTest extends AbstractCoreTest {

	@Override
	protected void customizeConfig(Config config) {
		config.setProperty(Settings.AUTH_GROUP_CACHE, "false");
	}

	@Test
	public void disabledCacheCachesNothingAndDecisionsStayCorrect() {
		PrincipalManagerImpl pmi = (PrincipalManagerImpl) pm;
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Context mouse = cm.getContext("mouse");
		Entry target = mouse.createResource(null, GraphType.None, null, null);
		Entry userEntry = pm.createResource(null, GraphType.User, null, null);
		User user = (User) userEntry.getResource();
		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		Group group = (Group) groupEntry.getResource();
		target.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, group.getURI());
		group.addMember(user);

		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));
		assertTrue(pmi.userGroupsCache.isEmpty(), "disabled cache must never be populated");

		group.removeMember(user);
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata));
	}
}
