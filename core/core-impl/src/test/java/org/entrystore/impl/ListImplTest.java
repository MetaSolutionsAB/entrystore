/*
 * Copyright (c) 2007-2017 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class ListImplTest extends AbstractCoreTest {

	@Test
	public void singleOccurrenceOfChild() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("https://slashdot.org/"), null);

		// Ok the first time.
		((List) list.getResource()).addChild(child.getEntryURI());

		// Should fail second time.
		assertThrows(RepositoryException.class, () -> ((List) list.getResource()).addChild(child.getEntryURI()));
	}

	@Test
	public void singleOccurrenceOfChild2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("https://slashdot.org/"), null);
		java.util.List<URI> children = new ArrayList<>();
		children.add(child.getEntryURI());
		children.add(child.getEntryURI());

		assertThrows(RepositoryException.class, () -> ((List) list.getResource()).setChildren(children));
	}

	@Test
	public void singleParentOfList() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);

		// Adding a list to a parent list should be ok.
		((List) parentList1.getResource()).addChild(childList.getEntryURI());

		// Adding a list to a second parent list should fail.
		assertThrows(RepositoryException.class,
			() -> ((List) parentList2.getResource()).addChild(childList.getEntryURI()));

	}

	@Test
	public void singleParentOfLis2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); // Adding a list to a parent list should be ok.
		java.util.List<URI> children = new ArrayList<>();
		children.add(childList.getEntryURI());

		assertThrows(RepositoryException.class, () -> ((List) parentList2.getResource()).setChildren(children));

	}

	@Test
	public void moveEntryBetweenLists() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());

		((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), false);
		assertEquals(0, ((List) listE1.getResource()).getChildren().size());
		assertEquals(1, ((List) listE2.getResource()).getChildren().size());
	}

	@Test
	public void moveEntryBetweenListsInDifferentContexts() throws IOException, QuotaException {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());

		Entry listE2 = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry newEntry = ((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), true);
		assertNull(duck.getByEntryURI(linkEntry.getEntryURI()));
		assertEquals(newEntry.getContext(), mouse);
		assertEquals(1, ((List) listE2.getResource()).getChildren().size());
	}

	@Test
	public void ownerRemoveTree() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, listE1.getResourceURI()); // since owner
		Entry listE3 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());
		Entry linkEntry2 = duck.createLink(null, URI.create("https://digg.com/"), listE2.getResourceURI());
		Entry linkEntry3 = duck.createLink(null, URI.create("https://reddit.com/"), listE3.getResourceURI());
		((List) listE1.getResource()).removeTree();
		assertNull(duck.getByEntryURI(listE1.getEntryURI()));
		assertNull(duck.getByEntryURI(listE2.getEntryURI()));
		assertNull(duck.getByEntryURI(linkEntry.getEntryURI()));
		assertNull(duck.getByEntryURI(linkEntry2.getEntryURI()));
		assertNotNull(duck.getByEntryURI(listE3.getEntryURI()));
		assertNotNull(duck.getByEntryURI(linkEntry3.getEntryURI()));
	}

}
