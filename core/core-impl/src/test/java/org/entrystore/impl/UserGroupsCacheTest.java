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

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * The cross-request user-to-groups cache must never grant access after a membership revocation: every mutation
 * path that changes what {@code getGroupUris} would return has to invalidate it. These tests pin the no-staleness
 * contract through the public authorization API and the cache lifecycle through package-private internals.
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
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"member must be authorized via the group grant");

		group.removeMember(user);
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a removed member must lose access on the very next decision; a stale cached group set here "
						+ "is a security bug");
	}

	@Test
	public void addMemberGrantsAccessImmediately() {
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"non-member must not be authorized (this decision caches the empty group set)");

		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a new member must gain access on the very next decision");
	}

	@Test
	public void setChildrenReplacementSwapsAccess() {
		Entry otherUserEntry = pm.createResource(null, GraphType.User, null, null);
		User otherUser = (User) otherUserEntry.getResource();
		group.addMember(user);

		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));
		assertFalse(isAuthorized(otherUser, target, AccessProperty.ReadMetadata));

		// Full membership replacement via the inherited ListImpl.setChildren path.
		group.setChildren(List.of(otherUserEntry.getEntryURI()));

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"replaced-away member must lose access immediately");
		assertTrue(isAuthorized(otherUser, target, AccessProperty.ReadMetadata),
				"replaced-in member must gain access immediately");
	}

	@Test
	public void groupDeleteRevokesAccessImmediately() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));

		pm.remove(groupEntry.getEntryURI());

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"deleting the granting group must revoke access on the very next decision");
	}

	@Test
	public void getRightsReflectsMembershipChange() {
		group.addMember(user);
		assertTrue(rightsOf(user, target).contains(AccessProperty.ReadMetadata),
				"getRights must report the group-granted right while the user is a member");

		group.removeMember(user);
		assertFalse(rightsOf(user, target).contains(AccessProperty.ReadMetadata),
				"getRights must drop the group-granted right on the very next call after removal");
	}

	@Test
	public void userDeleteEvictsCache() {
		group.addMember(user);
		isAuthorized(user, target, AccessProperty.ReadMetadata);
		assertTrue(pmi.userGroupsCache.containsKey(user.getURI()),
				"precondition: a group-consulting decision populates the cache");

		pm.remove(userEntry.getEntryURI());

		assertFalse(pmi.userGroupsCache.containsKey(user.getURI()),
				"deleting a user must evict their cached group set");
	}

	@Test
	public void loaderRacingInvalidationDoesNotPublishStaleGroups() throws Exception {
		group.addMember(user);

		// The spy shares the cache map and the epoch counter with the real principal manager, so an
		// invalidation triggered through the real listener is visible to a loader running on the spy.
		PrincipalManagerImpl spied = spy(pmi);
		CountDownLatch scanCaptured = new CountDownLatch(1);
		CountDownLatch mayPublish = new CountDownLatch(1);
		doAnswer(invocation -> {
			// Scan first, so the captured set still lists the group, then hold the result back until
			// the membership change has committed and fired its invalidation.
			Object preMutationGroups = invocation.callRealMethod();
			scanCaptured.countDown();
			if (!mayPublish.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("loader was never released");
			}
			return preMutationGroups;
		}).when(spied).getGroupUris(any(URI.class));

		ExecutorService loader = Executors.newSingleThreadExecutor();
		try {
			Future<Set<URI>> published = loader.submit(() -> {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				return spied.getGroupUrisCached(user.getURI());
			});
			assertTrue(scanCaptured.await(10, TimeUnit.SECONDS), "loader must have scanned before the mutation");

			group.removeMember(user);
			mayPublish.countDown();
			Set<URI> staleGroups = published.get(10, TimeUnit.SECONDS);

			assertTrue(staleGroups.contains(group.getURI()),
					"precondition: the loader really did capture the pre-mutation group set");
			assertFalse(pmi.userGroupsCache.containsKey(user.getURI()),
					"a scan that started before the membership change committed must not be published");
			assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
					"the next decision must see the revocation, not the racing loader's snapshot");
		} finally {
			loader.shutdownNow();
		}
	}
}
