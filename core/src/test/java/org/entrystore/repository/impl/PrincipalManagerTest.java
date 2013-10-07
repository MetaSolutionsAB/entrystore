/**
 * Copyright (c) 2007-2010
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

package org.entrystore.repository.impl;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.DisallowedException;
import org.entrystore.repository.Entry;
import org.entrystore.repository.Group;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.repository.test.TestSuite;
import org.junit.Before;
import org.junit.Test;

/**
 */
public class PrincipalManagerTest {
	private RepositoryManagerImpl rm;

	private ContextManager cm;

	private PrincipalManager pm;

	@Before
	public void setup() {
		ConfigurationManager confMan = null;
		try {
			confMan = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
		} catch (IOException e) {
			e.printStackTrace();
		}
		Config config = confMan.getConfiguration();
		config.setProperty(Settings.STORE_TYPE, "memory");
		rm = new RepositoryManagerImpl("http://my.confolio.org/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
		TestSuite.initDisneySuite(rm);
		TestSuite.addEntriesInDisneySuite(rm);
	}

	
	@Test
	public void contextAccessCheck() {

		// Check editing rights in mouse for Donald since he is in
		// friendsOfMickey group which has read and write access to mouse context.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context mouse = cm.getContext("mouse");
		Set<AccessProperty> rights = pm.getRights(mouse.getEntry());
		assertTrue(rights.contains(AccessProperty.ReadMetadata)); //Because guest has rights
		assertTrue(rights.contains(AccessProperty.WriteResource)); //Because in friendsOfMickeygroup
		assertTrue(rights.size() == 2); //ReadResource is implicit when WriteResouce is set.
	}
	
	@Test
	public void listAccessCheck() {

		// Check if Daisy can add a link in a folder where she has access without having access to entire portfolio.
		//First create a list where daisy has access (she does not have access to mouse context)
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Mickey").getResourceURI());
		Context mouse = cm.getContext("mouse");
		Entry listEntry = mouse.createResource(null, BuiltinType.List, RepresentationType.InformationResource, null);
		Entry daisy = pm.getPrincipalEntry("Daisy");		
		listEntry.addAllowedPrincipalsFor(AccessProperty.WriteResource, daisy.getResourceURI());

		//Now change to the Daisy user and try to create a resource in the newly created list.
		pm.setAuthenticatedUserURI(daisy.getResourceURI());
		mouse.createLink(null, URI.create("http://www.daisy.org"), listEntry.getResourceURI());
		
		try {
			mouse.createLink(null, URI.create("http://www.daisy2.org"), null);
			fail("Daisy should not have access to create a link in mouse context where she has no rights.");
		} catch (AuthorizationException ae) {
			
		}
		
	}
	

	
	@Test
	public void ownerCheck() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Daisy").getResourceURI());
		Context duck = cm.getContext("duck");
		duck.createResource(null, BuiltinType.List, null, null); // since owner
	}

	@Test
	public void guestAccessCheck() {
		// Guest, check public access to duck and none to mouse.
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		Context duck = cm.getContext("duck");
		assertTrue(duck.get("1") != null); // since guest access is allowed on duck
	}

	@Test (expected=AuthorizationException.class)
	public void guestNoAccessCheck() {
		// Guest, check public access to duck and none to mouse.
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		Context mouse = cm.getContext("mouse");
		mouse.get("1").getMetadataGraph(); //No guest access on Mouse context.
	}

	@Test
	public void groupAccessCheck() {

		// Check editing rights in mouse for Donald since he is in
		// friendsOfMickey group which has read and write access to mouse context.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context mouse = cm.getContext("mouse");
		assertTrue(mouse.get("1") != null);
		mouse.createResource(null, BuiltinType.List, null, null);
	}

	@Test
	public void administratorAccessToEntry() {
		Context mouse = cm.getContext("mouse");
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Mickey").getResourceURI());
		
		//	Mickey is owner of mouse context, and should be allowed to change it's ACL.
		mouse.getEntry().addAllowedPrincipalsFor(AccessProperty.ReadResource,
				pm.getPrincipalEntry("Daisy").getResourceURI());
	}

	@Test (expected=AuthorizationException.class)
	public void noAdministratorAccessToEntry() {
		Context mouse = cm.getContext("mouse");
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		//	Donald is not owner of mouse context, hence should not be allowed to change it's ACL.
		mouse.getEntry().setAllowedPrincipalsFor(AccessProperty.ReadResource, new HashSet());
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
		} catch (AuthorizationException ae) {
		}
	}

	
	@Test
	public void usersCheck() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Group userGroup = pm.getUserGroup();
		assertTrue(userGroup.members().size() == 5);
		try {
			userGroup.removeMember(userGroup.members().get(0));
			fail("UserGroup contains more than three users.");
		} catch (UnsupportedOperationException e) {
		}
		try {
			pm.remove(userGroup.getEntry().getEntryURI());
			fail("UserGroup is a systemEntry and should not be removable.");
		} catch (DisallowedException e) {
		}
	}
}
