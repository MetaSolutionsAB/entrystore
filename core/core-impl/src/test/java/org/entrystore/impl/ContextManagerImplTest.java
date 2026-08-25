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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.CommonQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 */
public class ContextManagerImplTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);
	}

	@Disabled("FIXME - does not do any sensible testing now")
	@Test
	public void sparqlSearch() {
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) entry.getResource();

		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);

		Model graph = listEntry.getLocalMetadata().getGraph();
		ValueFactory vf = rm.getValueFactory();
		IRI root = vf.createIRI(listEntry.getResource().getURI().toString());


		graph.add(root, vf.createIRI("http://purl.org/dc/terms/title"), vf.createLiteral("Folder 1", "en"));
		graph.add(root, vf.createIRI("dc:description"), vf.createLiteral("A top level folder", "en"));
		graph.add(root, vf.createIRI("dc:subject"), vf.createLiteral("mainFolder"));
		listEntry.getLocalMetadata().setGraph(graph);


		graph = linkEntry.getLocalMetadata().getGraph();
		root = vf.createIRI(linkEntry.getResourceURI().toString());

		graph.add(root, vf.createIRI("http://purl.org/dc/terms/title"), vf.createLiteral("Dagens Nyheter"));
		graph.add(root, vf.createIRI("dc:description"), vf.createLiteral("A widely spread morning newspaper in sweden."));
		graph.add(root, vf.createIRI("dc:format"), vf.createLiteral("text/html"));
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
			Model g = en.getLocalMetadata().getGraph();
			// g = en.getGraph();
		}


		String mdQuery = "PREFIX dc:<http://purl.org/dc/terms/> " +
										 "SELECT ?src " +
										 "WHERE  { GRAPH ?src {?x dc:title ?y} }";

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

	@Disabled("Needs to be implemented")
	@Test
	public void solrSearch() {}

	@Test
	public void createAndRemoveContext() {
		int nrOfContexts = cm.getResources().size();

		//Add success?
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		URI contextMMdURI = entry.getEntryURI();
		assertEquals(cm.getResources().size(), nrOfContexts + 1);
		Entry entryRequested = cm.getByEntryURI(contextMMdURI);
		assertEquals(entryRequested, entry);

		//Remove success?
		try {
			// should go wrong and will produce an ugly stack trace in the testing output, even though we catch it below
			cm.remove(URI.create("http://exampls.com/nonexistingMMDURI"));
			fail();
		} catch (Exception e) {
		}
		cm.remove(entry.getEntryURI()); //If something goes wrong it throws an exception.
		assertEquals(cm.getResources().size(), nrOfContexts);
		entryRequested = cm.getByEntryURI(contextMMdURI);
		assertNull(entryRequested);
	}

	@Test
	public void manageContextAliases() {
		assertNull(cm.getContextURI("newcontext"));

		int nrOfAliases = cm.getNames().size();

		//Create a new context, and set it's alias to "newcontext"
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		cm.setName(entry.getResource().getURI(), "newcontext");
		assertEquals(cm.getNames().size(), nrOfAliases + 1);

		//Request the context via it's alias
		URI cURI = cm.getContextURI("newcontext");
		assertNotNull(cURI);
		assertEquals(entry.getResource().getURI(), cURI);

		//Change the alias and make sure the old alias is removed and that the new works.
		cm.setName(entry.getResource().getURI(), "oldcontext");
		assertNull(cm.getContextURI("newcontext"));
		assertNotNull(cm.getContextURI("oldcontext"));
		assertEquals(cm.getNames().size(), nrOfAliases + 1);
	}


	@Test
	public void importContext_failureDuringRemoval_restoresPreviousContext(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		rm.setCheckForAuthorization(true);
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		List<Entry> grantedEntries = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			grantedEntries.add(context.createLink(null, URI.create("http://slashdot.org/" + i), null));
		}
		Entry deniedEntry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		((Data) deniedEntry.getResource()).setData(new ByteArrayInputStream("file content".getBytes(StandardCharsets.UTF_8)));
		File dataFile = new File(new File(tempDataDir.toFile(), contextEntry.getId()), deniedEntry.getId());
		assertTrue(dataFile.exists());
		Date modifiedBefore = contextEntry.getModifiedDate();

		// Mickey may administer the granted entries but not the file entry (and not the context), so the
		// removal inside the import transaction fails partway; iteration order of getEntries() is hash-based,
		// so several granted entries make it very likely that at least one removal starts before the denial
		Entry mickey = pm.getPrincipalEntry("Mickey");
		for (Entry grantedEntry : grantedEntries) {
			grantedEntry.addAllowedPrincipalsFor(AccessProperty.Administer, mickey.getResourceURI());
		}
		File importZip = createMinimalImportZip(tempDataDir);

		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		var thrown = assertThrows(org.entrystore.repository.RepositoryException.class,
				() -> cm.importContext(contextEntry, importZip));
		assertEquals("Failed to import context data", thrown.getMessage());

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		List<URI> allEntryURIs = new ArrayList<>(grantedEntries.stream().map(Entry::getEntryURI).toList());
		allEntryURIs.add(deniedEntry.getEntryURI());
		assertTrue(context.getEntries().containsAll(allEntryURIs));
		for (URI entryURI : allEntryURIs) {
			Entry restoredEntry = context.getByEntryURI(entryURI);
			assertNotNull(restoredEntry);
			assertFalse(restoredEntry.isDeleted());
		}
		assertTrue(dataFile.exists(), "A failed import must not delete data files from disk");
		assertEquals(modifiedBefore, contextEntry.getModifiedDate());
	}

	@Test
	public void importContext_success_removesOldEntriesAndDataFiles(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry dataEntry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		((Data) dataEntry.getResource()).setData(new ByteArrayInputStream("file content".getBytes(StandardCharsets.UTF_8)));
		File dataFile = new File(new File(tempDataDir.toFile(), contextEntry.getId()), dataEntry.getId());
		assertTrue(dataFile.exists());

		// the minimal ZIP contains no entries, so a successful import empties the context
		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNull(context.getByEntryURI(dataEntry.getEntryURI()));
		assertFalse(dataFile.exists(), "the deferred file deletions must run after a successful import");
	}

	// Builds the smallest ZIP that passes importContext's property and RDF validation, so the import
	// only fails later, inside the removal/add transaction.
	private static File createMinimalImportZip(Path dir) throws IOException {
		File zipFile = dir.resolve("entrystore-import-test.zip").toFile();
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
			zos.putNextEntry(new ZipEntry("export.properties"));
			String properties = """
					baseURI=http://localhost:8181/
					contextEntryURI=http://localhost:8181/_contexts/entry/99
					contextResourceURI=http://localhost:8181/99
					containedUsers=x
					""";
			zos.write(properties.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("triples.rdf"));
			zos.closeEntry();
		}
		return zipFile;
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
