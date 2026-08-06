/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.security.DisallowedException;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 */
public class PrincipalManagerImplTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUp();
		TestSuite.addEntriesInDisneySuite(rm);
	}


	@Test
	public void contextAccessCheck() {
		// Check editing rights in mouse for Donald since he is in
		// friendsOfMickey group that has read and write access to mouse context.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context mouse = cm.getContext("mouse");
		Set<AccessProperty> rights = pm.getRights(mouse.getEntry());
		assertTrue(rights.contains(AccessProperty.ReadMetadata)); //Because guest has rights
		assertTrue(rights.contains(AccessProperty.WriteResource)); //Because in friendsOfMickeygroup
		assertEquals(2, rights.size()); //ReadResource is implicit when WriteResouce is set.
	}

	@Test
	public void listAccessCheck() {

		// Check if Daisy can add a link in a folder where she has access without having access to entire portfolio.
		//First create a list where daisy has access (she does not have access to mouse context)
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Mickey").getResourceURI());
		Context mouse = cm.getContext("mouse");
		Entry listEntry = mouse.createResource(null, GraphType.List, ResourceType.InformationResource, null);
		Entry daisy = pm.getPrincipalEntry("Daisy");
		listEntry.addAllowedPrincipalsFor(AccessProperty.WriteResource, daisy.getResourceURI());

		//Now change to the Daisy user and try to create a resource in the newly created list.
		pm.setAuthenticatedUserURI(daisy.getResourceURI());
		mouse.createLink(null, URI.create("http://www.daisy.org"), listEntry.getResourceURI());

		try {
			mouse.createLink(null, URI.create("http://www.daisy2.org"), null);
			fail("Daisy should not have access to create a link in mouse context where she has no rights.");
		} catch (AuthorizationException ignored) {
		}

	}


	@Test
	public void ownerCheck() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Daisy").getResourceURI());
		Context duck = cm.getContext("duck");
		duck.createResource(null, GraphType.List, null, null); // since owner
	}

	@Test
	public void guestAccessCheck() {
		// Guest, check public access to duck and none to mouse.
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		Context duck = cm.getContext("duck");
		assertNotNull(duck.get("1")); // since guest access is allowed on duck
	}

	@Test
	public void guestNoAccessCheck() {
		// Guest, check public access to duck and none to mouse.
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		Context mouse = cm.getContext("mouse");
		//No guest access on Mouse context.
		assertThrows(AuthorizationException.class, () -> mouse.get("1").getMetadataGraph());
	}

	@Test
	public void groupAccessCheck() {

		// Check editing rights in mouse for Donald since he is in
		// friendsOfMickey group which has read and write access to mouse context.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context mouse = cm.getContext("mouse");
		assertNotNull(mouse.get("1"));
		mouse.createResource(null, GraphType.List, null, null);
	}

	@Test
	public void administratorAccessToEntry() {
		Context mouse = cm.getContext("mouse");
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Mickey").getResourceURI());

		//	Mickey is owner of mouse context, and should be allowed to change it's ACL.
		mouse.getEntry().addAllowedPrincipalsFor(AccessProperty.ReadResource,
			pm.getPrincipalEntry("Daisy").getResourceURI());
	}

	@Test
	public void noAdministratorAccessToEntry() {
		Context mouse = cm.getContext("mouse");
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		//	Donald is not owner of mouse context, hence should not be allowed to change it's ACL.
		assertThrows(AuthorizationException.class, () ->
			mouse.getEntry().setAllowedPrincipalsFor(AccessProperty.ReadResource, new HashSet()));
	}

	@Test
	public void contextAclOverridden() {
		Context mouse = cm.getContext("mouse");
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Daisy").getResourceURI());
		mouse.get("2").getMetadataGraph();
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		try {
			mouse.get("2").getMetadataGraph();
			fail("Donald should not have access to this entry since the entry overrides the context ACL " +
				"and his rights to the surrounding context is not administrator/owner.");
		} catch (AuthorizationException ignored) {
		}
	}


	@Test
	public void getGroupUris_skipsNullEntriesFromConcurrentDelete() {
		// Pin the null-guard added in PrincipalManagerImpl.getGroupUris(URI): getEntries()
		// lists URIs, but a concurrent delete can make a subsequent getByEntryURI() return
		// null for one of those URIs. Without the guard, getGraphType() NPEs and the access-
		// control check unwinds with an unspecific 500. The guard converts that into the
		// documented best-effort behaviour: skip stale URIs, return what is currently visible.
		PrincipalManagerImpl spied = spy((PrincipalManagerImpl) pm);

		// Listing returns a stale URI alongside the real ones.
		URI staleUri = URI.create("http://example.org/_principals/entry/stale-uri-9999");
		Set<URI> entriesWithStale = new LinkedHashSet<>(spied.getEntries());
		entriesWithStale.add(staleUri);
		doReturn(entriesWithStale).when(spied).getEntries();

		// And getByEntryURI returns null for that stale URI (the concurrent delete).
		doAnswer(invocation -> {
			URI requested = invocation.getArgument(0);
			if (staleUri.equals(requested)) {
				return null;
			}
			return invocation.callRealMethod();
		}).when(spied).getByEntryURI(any(URI.class));

		// Iterate as admin so getByEntryURI can resolve every real principal entry; the test's
		// concern is the null path, not the ACL path. Production callers set the authenticated
		// user to admin/guest before calling into here.
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		URI donaldUri = pm.getPrincipalEntry("Donald").getResourceURI();
		Set<URI> groupUris = spied.getGroupUris(donaldUri);

		// Stronger assertion than mere non-null: Donald is a member of friendsOfMickey in the
		// Disney suite; the iteration must continue PAST the stale URI and return the real
		// group memberships. A regression that catches NPE silently and bails out early would
		// produce an empty set; a regression that propagates NPE would throw.
		URI friendsOfMickeyUri = pm.getPrincipalEntry("friendsOfMickey").getResourceURI();
		assertNotNull(groupUris, "groupUris must be non-null when the listing contains stale URIs");
		assertTrue(groupUris.contains(friendsOfMickeyUri),
				"groupUris must include Donald's friendsOfMickey membership despite the stale URI; "
						+ "got " + groupUris);
	}

	@Test
	public void getUser_withNullURI_returnsNullInsteadOfThrowing() {
		// ENTRYSTORE-1095. The caller ContextImpl's null-guard exists for: isUserAdminOrAdminGroup leaves
		// the principal null when the authenticatedUserURI ThreadLocal is unset and hands it to getUser,
		// which calls getByResourceURI(null). ContextImplTest pins the guard itself, but stops at the guard
		// clause, so nothing exercised the path that actually reaches it.
		assertNull(pm.getUser(null), "an unresolvable principal must be absent, not an exception");
		assertNull(pm.getGroup(null));
	}

	@Test
	public void unresolvableAuthenticatedUser_isDeniedRatherThanGrantedTheUserGroupsRights() {
		// ENTRYSTORE-1095. hasAccess used to read `currentUser != getGuestUser()` as true for a null user,
		// so every entry granting the _users group was granted to a principal that could not be resolved —
		// and the next check dereferenced the null. An unreadable principal must deny.
		Context mouse = cm.getContext("mouse");
		Entry entry = mouse.getEntry();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getUserGroup().getURI());

		PrincipalManagerImpl principalManager = (PrincipalManagerImpl) pm;

		assertFalse(principalManager.hasAccess(null, entry, AccessProperty.ReadMetadata),
			"a principal that could not be resolved must not inherit the _users group's rights");
	}

	@Test
	public void usersCheck() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Group userGroup = pm.getUserGroup();
		assertEquals(6, userGroup.members().size());
		try {
			userGroup.removeMember(userGroup.members().getFirst());
			fail("UserGroup contains more than three users.");
		} catch (UnsupportedOperationException ignored) {
		}
		try {
			pm.remove(userGroup.getEntry().getEntryURI());
			fail("UserGroup is a systemEntry and should not be removable.");
		} catch (DisallowedException ignored) {
		}
	}

}
