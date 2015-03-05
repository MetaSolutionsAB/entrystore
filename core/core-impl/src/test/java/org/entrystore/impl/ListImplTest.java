/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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
import org.entrystore.List;
import org.entrystore.QuotaException;
import org.entrystore.repository.RepositoryException;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import static org.junit.Assert.assertTrue;

/**
 */
public class ListImplTest extends AbstractCoreTest {

	@Test (expected=RepositoryException.class)
	public void singleOccurenceOfChild() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("http://slashdot.org/"), null);
		((List) list.getResource()).addChild(child.getEntryURI()); //Ok first time
		((List) list.getResource()).addChild(child.getEntryURI()); //Should fail second time.
	}

	@Test (expected=RepositoryException.class)
	public void singleOccurenceOfChild2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
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
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); //Adding list to parent list, should be ok.
		((List) parentList2.getResource()).addChild(childList.getEntryURI()); //Adding list to second parent list, should fail.
	}

	@Test (expected=RepositoryException.class)
	public void singleParentOfLis2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); //Adding list to parent list, should be ok.
		java.util.List<URI> children = new ArrayList<URI>();
		children.add(childList.getEntryURI());
		((List) parentList2.getResource()).setChildren(children);
	}

	@Test
	public void moveEntryBetweenLists() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, null); // since owner
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
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("http://slashdot.org/"), listE1.getResourceURI());

		Entry listE2 = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry newEntry = ((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), true);
		assertTrue(duck.getByEntryURI(linkEntry.getEntryURI()) == null);
		assertTrue(newEntry.getContext().equals(mouse));
		assertTrue(((List) listE2.getResource()).getChildren().size() == 1);
	}
	
	@Test
	public void ownerRemoveTree() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, listE1.getResourceURI()); // since owner
		Entry listE3 = duck.createResource(null, GraphType.List, null, null); // since owner
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
