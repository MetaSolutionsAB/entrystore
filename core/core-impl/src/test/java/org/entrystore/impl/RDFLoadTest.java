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
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 */
public class RDFLoadTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUp();
		TestSuite.addEntriesInDisneySuite(rm);
		((ContextImpl) cm).getSoftCache().clear();
	}

	@Test
	public void typeCheck() {
		//Donald, check the owner of duck and editing rights in mouse.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");

		//ContextManager correct types.
		assertSame(EntryType.Local, cm.getEntry().getEntryType());
		assertSame(GraphType.SystemContext, cm.getEntry().getGraphType());
		assertSame(ResourceType.InformationResource, cm.getEntry().getResourceType());

		//Duck context
		assertSame(EntryType.Local, duck.getEntry().getEntryType());
		assertSame(GraphType.Context, duck.getEntry().getGraphType());
		assertSame(ResourceType.InformationResource, duck.getEntry().getResourceType());

		Entry entry = duck.get("3");
		assertSame(EntryType.Link, entry.getEntryType(), "Locationtype should be LinkReference, it is now: " + entry.getEntryType());
		assertSame(ResourceType.InformationResource, entry.getResourceType());

		entry = duck.get("4");
		assertSame(EntryType.Link, entry.getEntryType());
		assertSame(ResourceType.InformationResource, entry.getResourceType());

		entry = duck.get("6");
		assertSame(EntryType.Local, entry.getEntryType());
		assertSame(GraphType.None, entry.getGraphType());
		assertSame(ResourceType.InformationResource, entry.getResourceType());

		entry = duck.get("6");
		assertSame(EntryType.Local, entry.getEntryType());
		assertSame(GraphType.None, entry.getGraphType());
		assertSame(ResourceType.InformationResource, entry.getResourceType());
	}

	@Test
	public void userChecks() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		assertEquals(3, pm.getGroupUris().size());
	}

}
