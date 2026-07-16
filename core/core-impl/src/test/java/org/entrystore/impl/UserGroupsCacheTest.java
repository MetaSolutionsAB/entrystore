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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1085: the cross-request user-to-groups cache must never grant access after a
 * membership revocation — every mutation path that changes what {@code getGroupUris} would
 * return has to invalidate it. These tests pin the no-staleness contract through the public
 * authorization API and the cache lifecycle through package-private internals.
 */
public class UserGroupsCacheTest extends AbstractCoreTest {

	private PrincipalManagerImpl pmi;
	private Entry target;
	private Entry userEntry;
	private User user;
	private Entry groupEntry;
	private Group group;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pmi = (PrincipalManagerImpl) pm;
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		// A fresh entry in a non-guest-readable context, readable only via a group grant.
		Context mouse = cm.getContext("mouse");
		target = mouse.createResource(null, GraphType.None, null, null);
		userEntry = pm.createResource(null, GraphType.User, null, null);
		user = (User) userEntry.getResource();
		groupEntry = pm.createResource(null, GraphType.Group, null, null);
		group = (Group) groupEntry.getResource();
		target.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, group.getURI());
	}

	@Test
	public void removeMemberRevokesAccessImmediately() {
		group.addMember(user);
		assertTrue(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"member must be authorized via the group grant");

		group.removeMember(user);
		assertFalse(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"a removed member must lose access on the very next decision — a stale cached "
						+ "group set here is a security bug");
	}

	@Test
	public void addMemberGrantsAccessImmediately() {
		assertFalse(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"non-member must not be authorized (this decision caches the empty group set)");

		group.addMember(user);
		assertTrue(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"a new member must gain access on the very next decision");
	}

	@Test
	public void setChildrenReplacementSwapsAccess() {
		Entry otherUserEntry = pm.createResource(null, GraphType.User, null, null);
		User otherUser = (User) otherUserEntry.getResource();
		group.addMember(user);

		assertTrue(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata));
		assertFalse(pm.isUserAuthorized(otherUser.getURI(), target, AccessProperty.ReadMetadata));

		// Full membership replacement via the inherited ListImpl.setChildren path.
		group.setChildren(List.of(otherUserEntry.getEntryURI()));

		assertFalse(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"replaced-away member must lose access immediately");
		assertTrue(pm.isUserAuthorized(otherUser.getURI(), target, AccessProperty.ReadMetadata),
				"replaced-in member must gain access immediately");
	}

	@Test
	public void groupDeleteRevokesAccessImmediately() {
		group.addMember(user);
		assertTrue(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata));

		pm.remove(groupEntry.getEntryURI());

		assertFalse(pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata),
				"deleting the granting group must revoke access on the very next decision");
	}

	@Test
	public void decisionPopulatesCache() {
		group.addMember(user);
		assertTrue(pmi.userGroupsCache.isEmpty(), "no decision made yet");

		pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata);

		assertTrue(pmi.userGroupsCache.containsKey(user.getURI()),
				"a group-consulting decision must populate the cross-request cache");
	}

	@Test
	public void userDeleteEvictsCache() {
		group.addMember(user);
		pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata);
		assertTrue(pmi.userGroupsCache.containsKey(user.getURI()));

		pm.remove(userEntry.getEntryURI());

		assertFalse(pmi.userGroupsCache.containsKey(user.getURI()),
				"deleting a user must evict their cached group set");
	}

	@Test
	public void cacheClearedAfterInBatchCommit() {
		group.addMember(user);
		pm.isUserAuthorized(user.getURI(), target, AccessProperty.ReadMetadata);
		assertFalse(pmi.userGroupsCache.isEmpty());

		// Events fired inside a batch precede the commit, so a decision racing the batch can
		// repopulate the cache from pre-commit state; the post-commit clear closes that window.
		rm.inBatch(() -> {
		});

		assertTrue(pmi.userGroupsCache.isEmpty(),
				"a committed batch must clear the cache (listener events may have fired pre-commit)");
	}
}
