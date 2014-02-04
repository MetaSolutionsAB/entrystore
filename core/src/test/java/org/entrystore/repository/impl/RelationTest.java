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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;

import org.entrystore.repository.GraphType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.List;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.User;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.Before;
import org.junit.Test;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.RDFS;

/**
 */
public class RelationTest {
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

	@Test
	public void listReferents() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childLink = duck.createLink(null, URI.create("http://slashdot.org"), null);
		assertTrue(childLink.getRelations().size() == 0);
		((List) parentList1.getResource()).addChild(childLink.getEntryURI());
		assertTrue(childLink.getRelations().size() == 1);
		((List) parentList2.getResource()).addChild(childLink.getEntryURI());
		assertTrue(childLink.getRelations().size() == 2);
	}

	@Test
	public void listGroupsForUser() {
		// Use the Donald user.
		Entry donald = pm.getPrincipalEntry("Donald");
		Entry daisy = pm.getPrincipalEntry("Daisy");
		pm.setAuthenticatedUserURI(donald.getResourceURI());
		assertTrue(((User) donald.getResource()).getGroups().size() == 1);
		assertTrue(((User) daisy.getResource()).getGroups().size() == 0);
	}

	@Test
	public void metadataRelation() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		EntryImpl list1 = (EntryImpl) duck.createResource(null, GraphType.List, null, null);
		EntryImpl list2 = (EntryImpl) duck.createResource(null, GraphType.List, null, null);
		EntryImpl link = (EntryImpl) duck.createLink(null, URI.create("http://slashdot.org"), null);
		Metadata md = list1.getLocalMetadata();
		Graph g = md.getGraph();
		ValueFactory vf = g.getValueFactory();
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameEntryURI()));
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameLocalMetadataURI()));
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameResourceURI()));
		md.setGraph(g);
		assertTrue(list2.getRelations().size() == 3);

		Metadata lmd = list1.getLocalMetadata();
		Graph lg = lmd.getGraph();
		lg.add(vf.createStatement(link.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameResourceURI()));
		assertTrue(list2.getRelations().size() == 3); //Should not be changed since resourceURI is not a repository URI.
	}
}
