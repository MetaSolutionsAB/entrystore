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
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1085: `entrystore.auth.group-cache=false` is the kill switch — decisions must stay
 * correct and the cache must stay unused.
 */
public class GroupCacheDisabledTest {

	private RepositoryManagerImpl rm;
	private ContextManager cm;
	private PrincipalManager pm;

	@BeforeEach
	public void setUp() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_GROUP_CACHE, "false");

		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
		TestSuite.initDisneySuite(rm);
	}

	@AfterEach
	public void tearDown() {
		rm.shutdown();
		rm = null;
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

		assertTrue(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata));
		assertTrue(pmi.userGroupsCache.isEmpty(), "disabled cache must never be populated");

		group.removeMember(user);
		assertFalse(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata));
	}
}
