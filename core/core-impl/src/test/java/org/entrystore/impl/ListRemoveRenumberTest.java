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

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.List;
import org.entrystore.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1089 (C2): removeChild renumbers the rdf:Seq tail in place instead of clearing and
 * rewriting the whole graph. The list-ordering invariant is security-relevant (GroupImpl
 * membership is stored as a list), so every removal position and the persisted state after a
 * cache eviction are pinned here.
 */
public class ListRemoveRenumberTest extends AbstractCoreTest {

	private Context duck;
	private Entry listEntry;
	private List list;
	private java.util.List<Entry> children;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		duck = cm.getContext("duck");
		listEntry = duck.createResource(null, GraphType.List, null, null);
		list = (List) listEntry.getResource();
		children = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Entry child = duck.createLink(null, URI.create("https://renumber.example.com/" + i), null);
			children.add(child);
			list.addChild(child.getEntryURI());
		}
	}

	/** Children as loaded fresh from the committed RDF, bypassing the in-memory vector. */
	private java.util.List<URI> reloadedChildren() {
		((ContextImpl) duck).softCache.remove(listEntry);
		List reloaded = (List) duck.getByEntryURI(listEntry.getEntryURI()).getResource();
		return reloaded.getChildren();
	}

	private java.util.List<URI> uris(int... indices) {
		java.util.List<URI> result = new ArrayList<>();
		for (int i : indices) {
			result.add(children.get(i).getEntryURI());
		}
		return result;
	}

	@Test
	public void removeMiddleChildPreservesOrderInMemoryAndPersisted() {
		list.removeChild(children.get(2).getEntryURI());

		assertEquals(uris(0, 1, 3, 4), list.getChildren(), "in-memory order after middle removal");
		assertEquals(uris(0, 1, 3, 4), reloadedChildren(), "persisted rdf:Seq order after middle removal");
	}

	@Test
	public void removeFirstChildPreservesOrder() {
		list.removeChild(children.get(0).getEntryURI());

		assertEquals(uris(1, 2, 3, 4), list.getChildren());
		assertEquals(uris(1, 2, 3, 4), reloadedChildren());
	}

	@Test
	public void removeLastChildPreservesOrder() {
		list.removeChild(children.get(4).getEntryURI());

		assertEquals(uris(0, 1, 2, 3), list.getChildren());
		assertEquals(uris(0, 1, 2, 3), reloadedChildren());
	}

	@Test
	public void removeAllThenAddWorks() {
		for (Entry child : children) {
			list.removeChild(child.getEntryURI());
		}
		assertEquals(0, reloadedChildren().size(), "emptied list must persist as empty");

		Entry fresh = duck.createLink(null, URI.create("https://renumber.example.com/fresh"), null);
		list.addChild(fresh.getEntryURI());
		assertEquals(java.util.List.of(fresh.getEntryURI()), reloadedChildren(),
				"adding to an emptied list must restore a well-formed rdf:Seq");
	}

	@Test
	public void interleavedRemovesMatchVectorState() {
		list.removeChild(children.get(1).getEntryURI());
		list.removeChild(children.get(3).getEntryURI());
		list.removeChild(children.get(0).getEntryURI());

		assertEquals(uris(2, 4), list.getChildren());
		assertEquals(uris(2, 4), reloadedChildren(),
				"repeated single-child removals must keep vector and persisted seq in lockstep");
	}

	/** Replaces the 5-child fixture with a fresh list of {@code count} children. */
	private void buildList(int count) {
		listEntry = duck.createResource(null, GraphType.List, null, null);
		list = (List) listEntry.getResource();
		children = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Entry child = duck.createLink(null, URI.create("https://renumber.example.com/big/" + i), null);
			children.add(child);
			list.addChild(child.getEntryURI());
		}
	}

	@Test
	public void multiPositionTailRenumberPreservesOrder() {
		buildList(12);
		// size after removal 11, tail 11-9=2 <= 11/3=3: targeted renumber shifts two positions
		list.removeChild(children.get(9).getEntryURI());

		assertEquals(uris(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11), list.getChildren());
		assertEquals(uris(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11), reloadedChildren(),
				"multi-position tail renumber must persist the shifted rdf:_N order");
	}

	@Test
	public void crossoverBoundaryTargetedSideMatchesFallbackSide() {
		buildList(12);
		// tail 3 == 11/3: last position handled by the targeted renumber
		list.removeChild(children.get(8).getEntryURI());
		assertEquals(uris(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11), reloadedChildren(),
				"targeted side of the tail<=size/3 crossover");

		buildList(12);
		// tail 4 > 11/3: first position that falls back to the full rewrite
		list.removeChild(children.get(7).getEntryURI());
		assertEquals(uris(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11), reloadedChildren(),
				"full-rewrite side of the tail<=size/3 crossover");
	}

	@Test
	public void groupMembershipSurvivesRemovalRenumbering() {
		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		Group group = (Group) groupEntry.getResource();
		java.util.List<User> members = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Entry userEntry = pm.createResource(null, GraphType.User, null, null);
			members.add((User) userEntry.getResource());
			group.addMember((User) userEntry.getResource());
		}

		group.removeMember(members.get(1));

		assertEquals(3, group.members().size());
		assertTrue(group.isMember(members.get(0)));
		assertTrue(!group.isMember(members.get(1)), "removed member must be gone");
		assertTrue(group.isMember(members.get(2)));
		assertTrue(group.isMember(members.get(3)));
	}
}
