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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
