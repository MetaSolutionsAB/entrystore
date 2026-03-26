/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GroupImplTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUpWithoutSuite();
		((ContextImpl) cm).getSoftCache().clear();
	}

	@Test
	public void testCreateGroup() {
		String userName = "TestUser";
		String groupName = "TestGroup";

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry userEntry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(userEntry.getResourceURI(), userName);
		User user = (User) userEntry.getResource();

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), groupName);
		Group group = (Group) groupEntry.getResource();
		//group.addMember(user);
		List<URI> users = new ArrayList<>();
		users.add(user.getEntry().getEntryURI());
		group.setChildren(users);

		assertEquals(1, group.members().size());
		Entry groupMember = group.members().getFirst().getEntry();
		assertEquals(user.getURI(), groupMember.getResourceURI());
		assertEquals(1, groupMember.getRelations().size());

		User userQueried = (User) pm.getPrincipalEntry(userName).getResource();
		Group groupQueried = (Group) pm.getPrincipalEntry(groupName).getResource();
		assertEquals(1, groupQueried.members().size());
		Entry groupQueriedMember = groupQueried.members().getFirst().getEntry();
		assertEquals(userQueried.getURI(), groupQueriedMember.getResourceURI());
		assertEquals(1, groupQueriedMember.getRelations().size());
	}

	@Test
	public void testSetGraphUpdatesGroupMembers() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry user1Entry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(user1Entry.getResourceURI(), "GraphUser1");

		Entry user2Entry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(user2Entry.getResourceURI(), "GraphUser2");

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "GraphGroup");
		Group group = (Group) groupEntry.getResource();

		// Build an RDF model with rdf:_1 and rdf:_2 pointing to user entry URIs
		var vf = SimpleValueFactory.getInstance();
		var resourceIRI = vf.createIRI(groupEntry.getResourceURI().toString());
		Model graph = new LinkedHashModel();
		graph.add(resourceIRI, RDF.TYPE, RDF.SEQ);
		graph.add(resourceIRI, vf.createIRI(RDF.NAMESPACE + "_1"), vf.createIRI(user1Entry.getEntryURI().toString()));
		graph.add(resourceIRI, vf.createIRI(RDF.NAMESPACE + "_2"), vf.createIRI(user2Entry.getEntryURI().toString()));

		group.setGraph(graph);

		assertEquals(2, group.members().size());
		assertEquals(user1Entry.getResourceURI(), group.members().get(0).getEntry().getResourceURI());
		assertEquals(user2Entry.getResourceURI(), group.members().get(1).getEntry().getResourceURI());
	}

	@Test
	public void testSetChildrenRejectsNonUserEntry() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "RejectNonUserGroup");
		Group group = (Group) groupEntry.getResource();

		// Create another group (non-User entry) and try to add it as a member
		Entry otherGroupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(otherGroupEntry.getResourceURI(), "NotAUser");

		List<URI> children = new ArrayList<>();
		children.add(otherGroupEntry.getEntryURI());

		assertThrows(IllegalArgumentException.class, () -> group.setChildren(children));
	}

	@Test
	public void testSetChildrenRejectsNonExistentEntry() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "RejectMissingGroup");
		Group group = (Group) groupEntry.getResource();

		List<URI> children = new ArrayList<>();
		children.add(URI.create("http://example.com/_principals/entry/nonexistent"));

		assertThrows(IllegalArgumentException.class, () -> group.setChildren(children));
	}

	@Test
	public void testSetGraphNullThrowsIllegalArgumentException() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "NullGraphGroup");
		Group group = (Group) groupEntry.getResource();

		assertThrows(IllegalArgumentException.class, () -> group.setGraph(null));
	}

	@Test
	public void testSetGraphUpdatesInverseRelationsOnUsers() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry user1Entry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(user1Entry.getResourceURI(), "RelUser1");

		Entry user2Entry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(user2Entry.getResourceURI(), "RelUser2");

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "RelGroup");
		Group group = (Group) groupEntry.getResource();

		var vf = SimpleValueFactory.getInstance();
		var resourceIRI = vf.createIRI(groupEntry.getResourceURI().toString());
		Model graph = new LinkedHashModel();
		graph.add(resourceIRI, RDF.TYPE, RDF.SEQ);
		graph.add(resourceIRI, vf.createIRI(RDF.NAMESPACE + "_1"), vf.createIRI(user1Entry.getEntryURI().toString()));
		graph.add(resourceIRI, vf.createIRI(RDF.NAMESPACE + "_2"), vf.createIRI(user2Entry.getEntryURI().toString()));

		group.setGraph(graph);

		assertEquals(2, group.members().size());
		// Verify inverse relations are set on user entries
		assertEquals(1, user1Entry.getRelations().size());
		assertEquals(1, user2Entry.getRelations().size());
	}

	@Test
	public void testSetGraphWithEmptyModelClearsMembers() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry userEntry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(userEntry.getResourceURI(), "ClearUser");

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), "ClearGroup");
		Group group = (Group) groupEntry.getResource();

		// Add a member first
		List<URI> users = new ArrayList<>();
		users.add(userEntry.getEntryURI());
		group.setChildren(users);
		assertEquals(1, group.members().size());

		// Set empty graph to clear members
		Model emptyGraph = new LinkedHashModel();
		group.setGraph(emptyGraph);

		assertTrue(group.members().isEmpty());
	}
}
