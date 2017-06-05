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

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class ContextImplTest extends AbstractCoreTest {

	private Context context;

	@Before
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		// A new Context
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) entry.getResource();
	}

	@Test
	public void createAndRemoveEntries() {
		//Some Entries
		int oldSize = context.getResources().size();
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		Set<URI> resources = context.getResources();
		assertTrue(resources.size() == oldSize + 3);
		assertTrue(resources.contains(listEntry.getResourceURI()));
		assertTrue(resources.contains(linkEntry.getResourceURI()));
		assertTrue(resources.contains(refEntry.getResourceURI()));

		//Lets remove them all!
		context.remove(listEntry.getEntryURI());
		context.remove(linkEntry.getEntryURI());
		context.remove(refEntry.getEntryURI());
		resources = context.getResources();
		assertTrue(resources.size() == oldSize);
	}


	@Test
	public void accessToEntries() {
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		assertTrue(listEntry.equals(context.getByResourceURI(listEntry.getResourceURI()).iterator().next()));
		assertTrue(listEntry.equals(context.getByEntryURI(listEntry.getEntryURI())));

		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		assertTrue(linkEntry.equals(context.getByResourceURI(linkEntry.getResourceURI()).iterator().next()));
		assertTrue(linkEntry.equals(context.getByEntryURI(linkEntry.getEntryURI())));

		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		assertTrue(refEntry.equals(context.getByResourceURI(refEntry.getResourceURI()).iterator().next()));
		assertTrue(refEntry.equals(context.getByExternalMdURI(refEntry.getExternalMetadataURI()).iterator().next()));
		assertTrue(refEntry.equals(context.getByEntryURI(refEntry.getEntryURI())));
	}

	@Ignore("not ready yet")
	@Test
	public void quota() {
		context.getQuota();
		context.setQuota(5);
		assertTrue(context.getQuota() == 5);
	}

	@Test
	public void lists() {
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		List list = (List) listEntry.getResource();
		Entry sublistEntry1 = context.createResource(null, GraphType.List, null, listEntry.getResource().getURI());
		java.util.List<URI> children = list.getChildren();
		//Assert that something can be added to a list.
		assertTrue(children.size() == 1 && children.contains(sublistEntry1.getEntryURI()));

		//Assert that several things can be added to a list.
		Entry sublistEntry2 = context.createResource(null, GraphType.List, null, listEntry.getResource().getURI());
		children = list.getChildren();
		assertTrue(children.size() == 2 && children.contains(sublistEntry2.getEntryURI()));

		//Assert that the order of added things are the correct one.
		assertTrue(children.indexOf(sublistEntry1.getEntryURI()) == 0);
		assertTrue(children.indexOf(sublistEntry2.getEntryURI()) == 1);

		//Assert that the order can be changed.
		list.moveChildBefore(sublistEntry2.getEntryURI(), sublistEntry1.getEntryURI());
		children = list.getChildren();
		assertTrue(children.indexOf(sublistEntry1.getEntryURI()) == 1);
		assertTrue(children.indexOf(sublistEntry2.getEntryURI()) == 0);

		//Assert that we can set the lists children directly
		children = new ArrayList<URI>();
		children.add(0, sublistEntry1.getEntryURI());
		children.add(1, sublistEntry2.getEntryURI());
		list.setChildren(children);
		children = list.getChildren();
		assertTrue(children.indexOf(sublistEntry1.getEntryURI()) == 0);
		assertTrue(children.indexOf(sublistEntry2.getEntryURI()) == 1);

		//Assert that an entry can be removed from a list without being removed totally.
		list.removeChild(sublistEntry2.getEntryURI());
		children = list.getChildren();
		assertTrue(children.size() == 1 && children.contains(sublistEntry1.getEntryURI()));
		assertTrue(context.getByEntryURI(sublistEntry2.getEntryURI()) != null);

		//Assert that when an entry is removed in itself, it is removed from all lists it appears in.
		URI sl1mmdURI = sublistEntry1.getEntryURI();
		context.remove(sublistEntry1.getEntryURI());
		assertTrue(context.getByEntryURI(sl1mmdURI) == null);
		children = list.getChildren();
		assertTrue(children.size() == 0);

		//Assert that when a list is removed, the children are not removed.
		list.addChild(sublistEntry2.getEntryURI());
		URI lmmdURI = listEntry.getEntryURI();
		context.remove(lmmdURI);
		assertTrue(context.getByEntryURI(lmmdURI) == null);
		assertTrue(context.getByEntryURI(sublistEntry2.getEntryURI()) != null);
	}

}