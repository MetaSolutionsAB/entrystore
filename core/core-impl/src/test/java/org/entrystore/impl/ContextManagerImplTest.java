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
import org.entrystore.repository.util.CommonQueries;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.repository.RepositoryException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 */
public class ContextManagerImplTest extends AbstractCoreTest {

	@Before
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);
	}

	@Ignore("FIXME - does not do any sensible testing now")
	@Test
	public void sparqlSearch() throws Exception {
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) entry.getResource();

		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);

		Graph graph = listEntry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI root = vf.createURI(listEntry.getResource().getURI().toString());


		graph.add(root, new org.openrdf.model.impl.URIImpl("http://purl.org/dc/terms/title"), vf.createLiteral("Folder 1", "en"));
		graph.add(root, new org.openrdf.model.impl.URIImpl("dc:description"), vf.createLiteral("A top level folder", "en"));
		graph.add(root, new org.openrdf.model.impl.URIImpl("dc:subject"), vf.createLiteral("mainFolder"));
		listEntry.getLocalMetadata().setGraph(graph);


		graph = linkEntry.getLocalMetadata().getGraph();
		vf = graph.getValueFactory();
		root = vf.createURI(linkEntry.getResourceURI().toString());

		graph.add(root, new org.openrdf.model.impl.URIImpl("http://purl.org/dc/terms/title"), vf.createLiteral("Dagens Nyheter"));
		graph.add(root, new org.openrdf.model.impl.URIImpl("dc:description"), vf.createLiteral("A widely spread morning newspaper in sweden."));
		graph.add(root, new org.openrdf.model.impl.URIImpl("dc:format"), vf.createLiteral("text/html"));
		linkEntry.getLocalMetadata().setGraph(graph);

		String from = "2008-06-01";
		String until = "2008-08-01T17:10:46Z";
		String mdPrefix = "context";
		String q = null;
		try {
			q = CommonQueries.createListIdentifiersQuery(from, until, null, cm);
		} catch (RepositoryException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (MalformedQueryException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}


		Context c = cm.getContext("1");
		Entry en = c.get("2");

		if (!EntryType.Reference.equals(en.getEntryType())) {
			Graph g = en.getLocalMetadata().getGraph();
			// g = en.getGraph();
		}


		String mdQuery = new String("PREFIX dc:<http://purl.org/dc/terms/> " +
				"SELECT ?src " +
				"WHERE  { GRAPH ?src {?x dc:title ?y} }");

		try {
			List<URI> testList = new ArrayList<URI>();
			testList.add(new URI("http://my.confolio.org/1/data/100"));
			List<Entry> entries = cm.search(mdQuery, q, null);
			for (Entry e : entries) {
				System.out.println(e.getEntryURI());
				System.out.println(e.getCreationDate());
				System.out.println(e.getModifiedDate());
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Ignore("Needs to be implemented")
	@Test
	public void solrSearch() {

	}

	@Test
	public void createAndRemoveContext() {
		int nrOfContexts = cm.getResources().size();

		//Add success?
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		URI contextMMdURI = entry.getEntryURI();
		assertTrue(cm.getResources().size() == nrOfContexts + 1);
		Entry entryRequested = cm.getByEntryURI(contextMMdURI);
		assertTrue(entryRequested.equals(entry));

		//Remove success?
		try {
			cm.remove(URI.create("http://exampls.com/nonexistingMMDURI")); //Should go wrong.
			assertTrue(false);
		} catch (Exception e) {
		}
		cm.remove(entry.getEntryURI()); //If something goes wrong it throws an exception.
		assertTrue(cm.getResources().size() == nrOfContexts);
		entryRequested = cm.getByEntryURI(contextMMdURI);
		assertTrue(entryRequested == null);
	}

	@Test
	public void manageContextAliases() {
		assertTrue(cm.getContextURI("newcontext") == null);

		int nrOfAliases = cm.getNames().size();

		//Create a new context, and set it's alias to "newcontext"
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		cm.setName(entry.getResource().getURI(), "newcontext");
		assertTrue(cm.getNames().size() == nrOfAliases + 1);

		//Request the context via it's alias
		URI cURI = cm.getContextURI("newcontext");
		assertTrue(cURI != null);
		assertTrue(entry.getResource().getURI().equals(cURI));

		//Change the alias and make sure the old alias is removed and that the new works.
		cm.setName(entry.getResource().getURI(), "oldcontext");
		assertTrue(cm.getContextURI("newcontext") == null);
		assertTrue(cm.getContextURI("oldcontext") != null);
		assertTrue(cm.getNames().size() == nrOfAliases + 1);
	}


	@Test
	public void entryAccess() {
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) entry.getResource();
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		Entry dataEntry = context.createResource(null, GraphType.None, null, null);


		//Check retrieval via resources URI.
		Entry le = cm.getEntry(listEntry.getResource().getURI());
		assertTrue(le != null && le.equals(listEntry));

		//Check retrieval via md URI.
		le = cm.getEntry(listEntry.getLocalMetadataURI());
		assertTrue(le != null && le.equals(listEntry));

		//Check retrieval via entry URI.
		le = cm.getEntry(listEntry.getEntryURI());
		assertTrue(le != null && le.equals(listEntry));

		//Check that we do not get the list as a link.
		Set<Entry> links = cm.getLinks(listEntry.getResource().getURI());
		assertTrue(links.isEmpty());

		//Check that we do not get the list as a reference.
		Set<Entry> references = cm.getReferences(listEntry.getLocalMetadataURI());
		assertTrue(references.isEmpty());

		//Check that we can get the link.
		links = cm.getLinks(linkEntry.getLocalMetadata().getResourceURI());
		assertTrue(links.size() == 1 && links.contains(linkEntry));

		//Check that we can get the reference.
		references = cm.getReferences(refEntry.getExternalMetadataURI());
		assertTrue(references.size() == 1 && references.contains(refEntry));

	}

}