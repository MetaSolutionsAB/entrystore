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

package org.entrystore.repository.impl;

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.ContextImpl;
import org.entrystore.repository.test.TestSuite;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 */
public class RDFLoadTest extends AbstractCoreTest {

	@Before
	public void setup() {
		super.setup();
		TestSuite.addEntriesInDisneySuite(rm);
		((ContextImpl) cm).getCache().clear();
	}

	@Test
	public void typeCheck() {
		//Donald, check owner of duck and editing rights in mouse.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");

		//ContextManager correct types.
		assertTrue(cm.getEntry().getEntryType() == EntryType.Local);
		assertTrue(cm.getEntry().getGraphType() == GraphType.SystemContext);
		assertTrue(cm.getEntry().getResourceType() == ResourceType.InformationResource);

		//Duck context
		assertTrue(duck.getEntry().getEntryType() == EntryType.Local);
		assertTrue(duck.getEntry().getGraphType() == GraphType.Context);
		assertTrue(duck.getEntry().getResourceType() == ResourceType.InformationResource);

		//Top list
		Entry entry = duck.get("_top");
		assertTrue(entry.getEntryType() == EntryType.Local);
		assertTrue(entry.getGraphType() == GraphType.List);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		//LinkReference to Mickeys top list.
		entry = duck.get("3");
		assertTrue("Locationtype should be LinkReference, it is now: " + entry.getEntryType(),
				entry.getEntryType() == EntryType.LinkReference);
		assertTrue(entry.getGraphType() == GraphType.List);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		//Reference to the principal Mickey
		entry = duck.get("4");
		assertTrue(entry.getEntryType() == EntryType.Reference);
		assertTrue(entry.getGraphType() == GraphType.User);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		//Link to wikipedia
		entry = duck.get("6");
		assertTrue(entry.getEntryType() == EntryType.Link);
		assertTrue(entry.getGraphType() == GraphType.None);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		//Phooey, a abstract resource
		entry = duck.get("8");
		assertTrue(entry.getEntryType() == EntryType.Local);
		assertTrue(entry.getGraphType() == GraphType.None);
		assertTrue(entry.getResourceType() == ResourceType.NamedResource);
	}

	@Test
	public void userChecks() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		assertTrue(pm.getGroupUris().size() == 3);
	}

}
