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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.Metadata;
import org.entrystore.User;
import org.junit.jupiter.api.Test;

public class RelationTest extends AbstractCoreTest {

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
		Model g = md.getGraph();
		ValueFactory vf = rm.getValueFactory();
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameEntryURI()));
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameLocalMetadataURI()));
		g.add(vf.createStatement(list1.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameResourceURI()));
		md.setGraph(g);
		assertTrue(list2.getRelations().size() == 3);

		Metadata lmd = list1.getLocalMetadata();
		Model lg = lmd.getGraph();
		lg.add(vf.createStatement(link.getSesameResourceURI(), RDFS.ISDEFINEDBY, list2.getSesameResourceURI()));
		assertTrue(list2.getRelations().size() == 3); //Should not be changed since resourceURI is not a repository URI.
	}

	private static final IRI RELATED = SimpleValueFactory.getInstance().createIRI("http://example.com/related");

	/**
	 * ENTRYSTORE-1189. Inverse relations are bookkeeping, so they are kept on an entry the editor may not read; a
	 * cached one used to fail the write.
	 */
	@Test
	public void entryRelation_toACachedEntryTheEditorMayNotRead_isAddedAndRemoved() {
		EntryImpl target = entryInPrivateContext();
		EntryImpl source = entryInDuck();
		Statement relation = rm.getValueFactory().createStatement(source.getSesameResourceURI(), RELATED,
				target.getSesameResourceURI());
		Model graph = source.getGraph();

		graph.add(relation);
		asDonald(() -> source.setGraph(graph));
		assertTrue(hasRelation(target, relation));

		graph.remove(relation);
		asDonald(() -> source.setGraph(graph));
		assertFalse(hasRelation(target, relation));
	}

	/** ENTRYSTORE-1189. Uncached, the entry is loaded without the check that getEntry now applies on a miss too. */
	@Test
	public void entryRelation_toAnUncachedEntryTheEditorMayNotRead_isAddedAndRemoved() {
		EntryImpl target = entryInPrivateContext();
		EntryImpl source = entryInDuck();
		Statement relation = rm.getValueFactory().createStatement(source.getSesameResourceURI(), RELATED,
				target.getSesameResourceURI());
		Model graph = source.getGraph();

		graph.add(relation);
		evictFromSoftCache(target);
		asDonald(() -> source.setGraph(graph));
		assertTrue(hasRelation(target, relation));

		graph.remove(relation);
		evictFromSoftCache(target);
		asDonald(() -> source.setGraph(graph));
		assertFalse(hasRelation(target, relation));
	}

	/**
	 * ENTRYSTORE-1189. Adding such a relation was already allowed (ENTRYSTORE-803), but replacing it failed once the
	 * target was cached.
	 */
	@Test
	public void metadataRelation_toACachedEntryTheEditorMayNotRead_canBeReplaced() {
		EntryImpl target = entryInPrivateContext();
		EntryImpl source = entryInDuck();
		Metadata metadata = source.getLocalMetadata();
		Statement relation = rm.getValueFactory().createStatement(source.getSesameResourceURI(), RELATED,
				target.getSesameResourceURI());
		Model graph = new LinkedHashModel();
		graph.add(relation);

		asDonald(() -> metadata.setGraph(graph));
		assertTrue(hasRelation(target, relation));

		asDonald(() -> metadata.setGraph(new LinkedHashModel()));
		assertFalse(hasRelation(target, relation));
	}

	@Test
	public void metadataRelation_toAnUncachedEntryTheEditorMayNotRead_canBeReplaced() {
		EntryImpl target = entryInPrivateContext();
		EntryImpl source = entryInDuck();
		Metadata metadata = source.getLocalMetadata();
		Statement relation = rm.getValueFactory().createStatement(source.getSesameResourceURI(), RELATED,
				target.getSesameResourceURI());
		Model graph = new LinkedHashModel();
		graph.add(relation);

		evictFromSoftCache(target);
		asDonald(() -> metadata.setGraph(graph));
		assertTrue(hasRelation(target, relation));

		evictFromSoftCache(target);
		asDonald(() -> metadata.setGraph(new LinkedHashModel()));
		assertFalse(hasRelation(target, relation));
	}

	/** An entry Donald may not read: in a context the admin creates, which grants Donald nothing. */
	private EntryImpl entryInPrivateContext() {
		return asUser(pm.getAdminUser(),
				() -> (EntryImpl) newContext().createResource(null, GraphType.None, null, null));
	}

	/** An entry Donald administers, as an owner of duck. */
	private EntryImpl entryInDuck() {
		return asUser(donald(),
				() -> (EntryImpl) cm.getContext("duck").createResource(null, GraphType.None, null, null));
	}

	private void asDonald(Runnable action) {
		asUser(donald(), () -> {
			action.run();
			return null;
		});
	}

	/** Matches by subject, predicate and object, since the stored relation carries the target's relation graph. */
	private static boolean hasRelation(EntryImpl target, Statement relation) {
		return target.getRelations().contains(relation.getSubject(), relation.getPredicate(), relation.getObject());
	}

	private User donald() {
		return (User) pm.getPrincipalEntry("Donald").getResource();
	}
}
