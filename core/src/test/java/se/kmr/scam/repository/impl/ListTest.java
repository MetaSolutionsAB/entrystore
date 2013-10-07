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

package se.kmr.scam.repository.impl;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Context;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.List;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.QuotaException;
import se.kmr.scam.repository.RepositoryException;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.test.TestSuite;

/**
 */
public class ListTest {
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
	}

	@Test (expected=RepositoryException.class)
	public void singleOccurenceOfChild() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, BuiltinType.List, null, null);
		Entry child = duck.createLink(null, URI.create("http://slashdot.org/"), null);
		((List) list.getResource()).addChild(child.getEntryURI()); //Ok first time
		((List) list.getResource()).addChild(child.getEntryURI()); //Should fail second time.
	}

	@Test (expected=RepositoryException.class)
	public void singleOccurenceOfChild2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, BuiltinType.List, null, null);
		Entry child = duck.createLink(null, URI.create("http://slashdot.org/"), null);
		java.util.List<URI> children = new ArrayList<URI>();
		children.add(child.getEntryURI());
		children.add(child.getEntryURI());
		((List) list.getResource()).setChildren(children);
	}

	@Test (expected=RepositoryException.class)
	public void singleParentOfList() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, BuiltinType.List, null, null);
		Entry parentList2 = duck.createResource(null, BuiltinType.List, null, null);
		Entry childList = duck.createResource(null, BuiltinType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); //Adding list to parent list, should be ok.
		((List) parentList2.getResource()).addChild(childList.getEntryURI()); //Adding list to second parent list, should fail.
	}

	@Test (expected=RepositoryException.class)
	public void singleParentOfLis2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, BuiltinType.List, null, null);
		Entry parentList2 = duck.createResource(null, BuiltinType.List, null, null);
		Entry childList = duck.createResource(null, BuiltinType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); //Adding list to parent list, should be ok.
		java.util.List<URI> children = new ArrayList<URI>();
		children.add(childList.getEntryURI());
		((List) parentList2.getResource()).setChildren(children);
	}

	@Test
	public void moveEntryBetweenLists() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, BuiltinType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, BuiltinType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("http://slashdot.org/"), listE1.getResourceURI());

		((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), false);
		assertTrue(((List) listE1.getResource()).getChildren().size() == 0);
		assertTrue(((List) listE2.getResource()).getChildren().size() == 1);
	}
	
	@Test
	public void moveEntryBetweenListsInDifferentContexts() throws IOException, QuotaException {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry listE1 = duck.createResource(null, BuiltinType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("http://slashdot.org/"), listE1.getResourceURI());

		Entry listE2 = mouse.createResource(null, BuiltinType.List, null, null); // since owner
		Entry newEntry = ((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), false);
		assertTrue(duck.getByEntryURI(linkEntry.getEntryURI()) == null);
		assertTrue(newEntry.getContext() == mouse);
		assertTrue(((List) listE2.getResource()).getChildren().size() == 1);
	}
	
	@Test
	public void ownerRemoveTree() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, BuiltinType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, BuiltinType.List, null, listE1.getResourceURI()); // since owner
		Entry listE3 = duck.createResource(null, BuiltinType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("http://slashdot.org/"), listE1.getResourceURI());
		Entry linkEntry2 = duck.createLink(null, URI.create("http://digg.com/"), listE2.getResourceURI());
		Entry linkEntry3 = duck.createLink(null, URI.create("http://reddit.com/"), listE3.getResourceURI());
		((List) listE1.getResource()).removeTree();
		assertTrue(duck.getByEntryURI(listE1.getEntryURI()) == null);
		assertTrue(duck.getByEntryURI(listE2.getEntryURI()) == null);
		assertTrue(duck.getByEntryURI(linkEntry.getEntryURI()) == null);
		assertTrue(duck.getByEntryURI(linkEntry2.getEntryURI()) == null);
		assertTrue(duck.getByEntryURI(listE3.getEntryURI()) != null);
		assertTrue(duck.getByEntryURI(linkEntry3.getEntryURI()) != null);
	}
}
