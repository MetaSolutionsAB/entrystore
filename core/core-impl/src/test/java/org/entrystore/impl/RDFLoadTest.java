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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 */
public class RDFLoadTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUp();
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

		Entry entry = duck.get("3");
		assertTrue(entry.getEntryType() ==
							 EntryType.Link, "Locationtype should be LinkReference, it is now: " + entry.getEntryType());
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		entry = duck.get("4");
		assertTrue(entry.getEntryType() == EntryType.Link);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		entry = duck.get("6");
		assertTrue(entry.getEntryType() == EntryType.Local);
		assertTrue(entry.getGraphType() == GraphType.None);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);

		entry = duck.get("6");
		assertTrue(entry.getEntryType() == EntryType.Local);
		assertTrue(entry.getGraphType() == GraphType.None);
		assertTrue(entry.getResourceType() == ResourceType.InformationResource);
	}

	@Test
	public void userChecks() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		assertTrue(pm.getGroupUris().size() == 3);
	}

}
