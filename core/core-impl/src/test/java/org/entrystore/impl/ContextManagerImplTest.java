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
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.ContextImportPendingException;
import org.entrystore.repository.security.ContextRemovalPendingException;
import org.entrystore.repository.test.TestSuite;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

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
	public void importContext_byAUserWhoMayNotAdministerTheContext_isRefusedAndChangesNothing(@TempDir Path tempDataDir)
			throws Exception {
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

		// Mickey may administer the granted entries but not the context, so the import is refused before the upload
		// is read
		Entry mickey = pm.getPrincipalEntry("Mickey");
		for (Entry grantedEntry : grantedEntries) {
			grantedEntry.addAllowedPrincipalsFor(AccessProperty.Administer, mickey.getResourceURI());
		}
		File importZip = createMinimalImportZip(tempDataDir);

		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		assertThrows(AuthorizationException.class, () -> cm.importContext(contextEntry, importZip));

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
		assertNotNull(context.createLink(null, URI.create("https://digg.com/"), null),
				"a refused import must not keep refusing creates in the context");
	}

	@Test
	public void importContext_intoARemovedContext_isRefused(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		File importZip = createMinimalImportZip(tempDataDir);

		cm.remove(contextEntry.getEntryURI());
		awaitContextRemovals();

		assertThrows(ContextRemovalPendingException.class, () -> cm.importContext(contextEntry, importZip));
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

	@Test
	public void importContext_survivingListReadDuringImport_showsCommittedMembersAfterwards(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry listEntry = context.createResource("_list", GraphType.List, null, null);
		Entry removed = context.createLink(null, URI.create("http://slashdot.org/"), listEntry.getResourceURI());
		Entry kept = context.createLink("_kept", URI.create("http://digg.com/"), listEntry.getResourceURI());

		ListImpl list = spy((ListImpl) listEntry.getResource());
		((EntryImpl) listEntry).setResource(list);
		AtomicReference<List<URI>> readDuringImport = new AtomicReference<>();
		ExecutorService reader = Executors.newSingleThreadExecutor();
		try {
			// a reader takes no repository monitor, so while the import transaction is open it caches the
			// members the store still holds
			doAnswer(invocation -> {
				Object pruned = invocation.callRealMethod();
				readDuringImport.set(reader.submit(list::getChildren).get(10, TimeUnit.SECONDS));
				return pruned;
			}).when(list).pruneExactly(any(RepositoryConnection.class), any());

			cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));
		} finally {
			reader.shutdownNow();
		}

		assertEquals(List.of(removed.getEntryURI(), kept.getEntryURI()), readDuringImport.get(),
				"precondition: the reader cached the members as they were before the import");
		assertEquals(List.of(kept.getEntryURI()), list.getChildren());
		list.removeChild(kept.getEntryURI());
		assertEquals(List.of(), storedMembers(listEntry),
				"a list write after the import must not write removed entries back into the list");
	}

	@Test
	public void importContext_cachedSurvivingListShowsImportedMembers(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry listEntry = context.createResource("_list", GraphType.List, null, null);
		Entry kept = context.createLink("_kept", URI.create("http://digg.com/"), null);
		ListImpl list = (ListImpl) listEntry.getResource();
		assertEquals(List.of(), list.getChildren(), "precondition: the empty member list is cached");

		cm.importContext(contextEntry, createImportZip(tempDataDir, KEPT_LIST_MEMBER));

		assertEquals(List.of(kept.getEntryURI()), list.getChildren());
	}

	@Test
	public void importContext_publishesAKeptListWhoseMembersItChanged(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry listEntry = context.createResource("_list", GraphType.List, null, null);
		context.createLink("_kept", URI.create("http://digg.com/"), null);
		List<URI> updated = new ArrayList<>();
		RepositoryListener recorder = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				updated.add(((Entry) eventObject.getSource()).getEntryURI());
			}
		};
		rm.registerListener(recorder, RepositoryEvent.ResourceUpdated);
		try {
			cm.importContext(contextEntry, createImportZip(tempDataDir, KEPT_LIST_MEMBER));
		} finally {
			rm.unregisterListener(recorder, RepositoryEvent.ResourceUpdated);
		}

		// the import removed no member of the list, so only the import itself can publish the one it added
		assertTrue(updated.contains(listEntry.getEntryURI()),
				"listeners such as the search index must learn of the members the import gave a kept list");
	}

	/** Puts the entry _kept into the list _list, both of which an import keeps. */
	private static final String KEPT_LIST_MEMBER = """
			<http://localhost:8181/99/resource/_list> {
				<http://localhost:8181/99/resource/_list> <%s_1> <http://localhost:8181/99/entry/_kept> .
			}
			""".formatted(RDF.NAMESPACE);

	/** The members of a list as the store holds them, bypassing every in-memory copy. */
	private List<URI> storedMembers(Entry listEntry) {
		ValueFactory vf = rm.getRepository().getValueFactory();
		IRI listResource = vf.createIRI(listEntry.getResourceURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.getStatements(listResource, null, null, false, listResource).stream()
					.filter(statement -> statement.getPredicate().stringValue().startsWith(RDF.NAMESPACE + "_"))
					.map(statement -> URI.create(statement.getObject().stringValue()))
					.toList();
		}
	}

	@Test
	public void importContext_leavesOutStagingFilesOfTheExport(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		String stagingFile = "." + UUID.randomUUID() + DataImpl.STAGING_POSTFIX;

		cm.importContext(contextEntry, createImportZip(tempDataDir, "", Map.of(
				"resources/7", "data", "resources/.document.part", "data", "resources/" + stagingFile, "partial")));

		File contextFolder = new File(tempDataDir.toFile(), contextEntry.getId());
		assertTrue(new File(contextFolder, "7").exists());
		// The file of an entry whose id resembles a staging name
		assertTrue(new File(contextFolder, ".document.part").exists());
		assertFalse(new File(contextFolder, stagingFile).exists());
	}

	private static File createImportZip(Path dir, String trig) throws IOException {
		return createImportZip(dir, trig, Map.of());
	}

	// Builds the smallest ZIP that passes importContext's property and RDF validation, so the import
	// only fails later, inside the removal/add transaction.
	private static File createMinimalImportZip(Path dir) throws IOException {
		return createImportZip(dir, "");
	}

	/**
	 * An export of the context http://localhost:8181/99 holding the given TriG, which the import maps to its
	 * target, and {@code extraEntries} as further ZIP entries, e.g. data files under {@code resources/}.
	 */
	private static File createImportZip(Path dir, String trig, Map<String, String> extraEntries)
			throws IOException {
		File zipFile = dir.resolve("entrystore-import-test.zip").toFile();
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
			for (Map.Entry<String, String> extra : extraEntries.entrySet()) {
				zos.putNextEntry(new ZipEntry(extra.getKey()));
				zos.write(extra.getValue().getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
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
			zos.write(trig.getBytes(StandardCharsets.UTF_8));
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

	@Test
	public void getLinks_excludesLinksTheUserMayNotRead() {
		URI sharedResource = URI.create("http://slashdot.org/shared");

		// ContextImpl.checkAccess asks for ReadMetadata on the context entry first and returns as soon
		// as that grants, and the Disney fixture grants guest exactly that on both duck and mouse, so
		// an unreadable entry needs a context that grants guest nothing rather than an entry-level ACL
		Context duck = cm.getContext("duck");
		Context privateContext = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
		Entry readable = duck.createLink(null, sharedResource, null);
		Entry unreadable = privateContext.createLink(null, sharedResource, null);

		rm.setCheckForAuthorization(true);
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		Set<Entry> links = cm.getLinks(sharedResource);

		assertEquals(1, links.size());
		assertTrue(links.contains(readable));
		assertFalse(links.contains(unreadable));
	}

	@Test
	public void getLinks_skipsIndexTriplesThatResolveToNoEntry() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		URI resourceURI = linkEntry.getResourceURI();

		// a stale index triple, as left behind by a failed index update, naming an entry that is gone
		addIndexTriple(contextEntry, resourceURI, contextEntry.getResourceURI() + "/entry/no-such-entry");

		Set<Entry> links = cm.getLinks(resourceURI);

		// the live link still comes back rather than the whole call failing on the stale triple
		assertEquals(1, links.size());
		assertTrue(links.contains(linkEntry));
	}

	@Test
	public void getLinks_skipsIndexTriplesNamingAContextThatDoesNotExist() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		URI resourceURI = linkEntry.getResourceURI();

		// resolves no context at all, which is the branch a missing context id takes
		addIndexTriple(contextEntry, resourceURI, rm.getRepositoryURL() + "no-such-ctx/entry/no-such-entry");

		Set<Entry> links = cm.getLinks(resourceURI);

		assertEquals(1, links.size());
		assertTrue(links.contains(linkEntry));
	}

	@Test
	public void getLinks_skipsIndexTriplesWhoseObjectIsNotUnderTheRepositoryBase() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		URI resourceURI = linkEntry.getResourceURI();

		// shorter than the repository base URL, which is what a restored backup or a changed
		// entrystore.baseurl.folder leaves behind; resolving it used to throw out of the whole call
		addIndexTriple(contextEntry, resourceURI, "urn:x");

		Set<Entry> links = cm.getLinks(resourceURI);

		assertEquals(1, links.size());
		assertTrue(links.contains(linkEntry));
	}

	/** Adds a resHasEntry triple whose object is a literal, which no index triple may ever be. */
	private void addLiteralIndexTriple(Entry contextEntry, URI subject, String literal) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI(subject.toString()),
				RepositoryProperties.resHasEntry,
				vf.createLiteral(literal),
				vf.createIRI(contextEntry.getResourceURI().toString()));
		}
	}

	@Test
	public void getLinks_skipsIndexTriplesWhoseObjectIsNotAnIRI() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		URI resourceURI = linkEntry.getResourceURI();

		// a literal object is what a caller holding WriteMetadata can write under an index predicate
		addLiteralIndexTriple(contextEntry, resourceURI, "not an entry URI at all");

		Set<Entry> links = cm.getLinks(resourceURI);

		assertEquals(1, links.size());
		assertTrue(links.contains(linkEntry));
	}

	@Test
	public void getReferences_readsTheMetadataIndexAndKeepsOnlyReferences() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		URI externalMetadata = URI.create("http://example.com/shared-md");
		Entry reference = context.createReference(null, URI.create("http://reddit.com/a"), externalMetadata, null);
		Entry linkReference = context.createLinkReference(null, URI.create("http://reddit.com/b"), externalMetadata, null);

		// both entries index the same external metadata URI, so only the entry type separates them
		Set<Entry> references = cm.getReferences(externalMetadata);

		assertEquals(1, references.size());
		assertTrue(references.contains(reference));
		assertFalse(references.contains(linkReference));
	}

	@Test
	public void getLinks_readsTheResourceIndexAndKeepsOnlyLinks() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		URI externalMetadata = URI.create("http://example.com/shared-md");
		Entry linkReference = context.createLinkReference(null, URI.create("http://reddit.com/b"), externalMetadata, null);

		// resHasEntry is keyed by resource URI, so a metadata URI matches nothing in it
		assertTrue(cm.getLinks(externalMetadata).isEmpty());
		// and a LinkReference sits in the resource index without being a Link
		assertTrue(cm.getLinks(linkReference.getResourceURI()).isEmpty());
	}

	@Test
	public void getEntryReturnsNullForAnUriInTheRepositoryThatCannotBeSplit() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		// An entry path without an entry ID
		URI entryPathWithoutId = URI.create(rm.getRepositoryURL() + contextEntry.getId() + "/entry");

		assertNull(cm.getEntry(entryPathWithoutId));
	}

	@Test
	public void entryMetadataOfAReferenceWhoseExternalMetadataUriCannotBeResolvedIsReadable() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				URI.create("http://example.com/metadata"), null);
		// Stored before URIs in the repository that do not denote an entry were rejected
		replaceExternalMetadataInStore(reference, URI.create(rm.getRepositoryURL() + contextEntry.getId() + "/entry"));
		context.softCache.remove(reference);
		Entry loaded = context.get(reference.getId());

		assertTrue(((ContextManagerImpl) cm).isEntryMetadataReadable(loaded));
	}

	/**
	 * An import removes the ordinary entries but keeps lists whose id starts with an underscore, so those lists
	 * are pruned inside the import transaction, which fires nothing of its own.
	 */
	@Test
	public void importContext_prunesASurvivingListAndPublishesIt(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry survivingList = context.createResource("_survivor", GraphType.List, null, null);
		Entry member = context.createLink(null, URI.create("https://slashdot.org/"), survivingList.getResourceURI());
		ListImpl list = (ListImpl) survivingList.getResource();
		assertTrue(list.getChildren().contains(member.getEntryURI()), "precondition: the member is in the list");

		List<URI> published = new ArrayList<>();
		List<Boolean> removedMemberVisibleAtDispatch = new ArrayList<>();
		RepositoryListener recorder = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				URI source = ((Entry) eventObject.getSource()).getEntryURI();
				published.add(source);
				if (source.equals(survivingList.getEntryURI())) {
					// what a listener that re-reads the list on this event, such as the search index, would read
					removedMemberVisibleAtDispatch.add(list.getChildren().contains(member.getEntryURI()));
				}
			}
		};
		RepositoryManagerImpl rmi = (RepositoryManagerImpl) rm;
		rmi.registerListener(recorder, RepositoryEvent.ResourceUpdated);
		try {
			// the minimal ZIP contains no entries, so the import removes every ordinary entry
			cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));
		} finally {
			rmi.unregisterListener(recorder, RepositoryEvent.ResourceUpdated);
		}

		assertNull(context.getByEntryURI(member.getEntryURI()), "the import removes the ordinary member");
		assertNotNull(context.getByEntryURI(survivingList.getEntryURI()), "a list with an underscore id survives");
		assertFalse(list.getChildren().contains(member.getEntryURI()),
				"a read after the import must not report a member the import removed");
		assertTrue(published.contains(survivingList.getEntryURI()),
				"the pruned list's change must be published so listeners such as the search index see it");
		assertEquals(List.of(false), removedMemberVisibleAtDispatch,
				"the members must be dropped before the event is fired, or a listener that re-scans on it "
						+ "reads the stale ones");
	}

	@Test
	public void importContext_letsAWriteToAnotherContextCompleteWhileItPrepares(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		((Context) contextEntry.getResource()).createLink(null, URI.create("https://slashdot.org/"), null);
		Context other = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
		AtomicReference<Boolean> monitorHeld = new AtomicReference<>();
		AtomicReference<Entry> written = new AtomicReference<>();
		((ContextManagerImpl) cm).whileImportPrepares = () -> {
			monitorHeld.set(Thread.holdsLock(rm.getRepository()));
			written.set(onAnotherThread(() -> other.createLink(null, URI.create("https://digg.com/"), null)));
		};

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertFalse(monitorHeld.get(), "the import must not hold the repository monitor while it prepares");
		assertNotNull(written.get(), "a write to another context must complete while the import prepares");
	}

	@Test
	public void importContext_refusesACreateInTheContextWhileItPrepares(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		AtomicReference<Throwable> refused = new AtomicReference<>();
		((ContextManagerImpl) cm).whileImportPrepares = () -> refused.set(assertThrows(
				ContextImportPendingException.class,
				() -> context.createLink(null, URI.create("https://digg.com/"), null)));

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNotNull(refused.get());
		assertNotNull(context.createLink(null, URI.create("https://digg.com/"), null),
				"creates must be accepted again once the import has ended");
	}

	@Test
	public void importContext_refusesASecondImportOfTheContextWhileItPrepares(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		File secondImport = createMinimalImportZip(Files.createDirectory(tempDataDir.resolve("second")));
		AtomicReference<Throwable> refused = new AtomicReference<>();
		((ContextManagerImpl) cm).whileImportPrepares = () -> {
			refused.set(assertThrows(ContextImportPendingException.class,
					() -> cm.importContext(contextEntry, secondImport)));
			assertThrows(ContextImportPendingException.class,
					() -> context.createLink(null, URI.create("https://digg.com/"), null),
					"the refused import must leave the fence of the first one in place");
		};

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNotNull(refused.get());
	}

	@Test
	public void importContext_answersACreateByAUserWhoMayNotWriteAsUnauthorizedWhileItPrepares(
			@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		rm.setCheckForAuthorization(true);
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		User mickey = (User) pm.getPrincipalEntry("Mickey").getResource();
		AtomicReference<Throwable> refused = new AtomicReference<>();
		// refused for lack of rights before the import is checked, so the caller learns nothing of the import
		((ContextManagerImpl) cm).whileImportPrepares = () -> refused.set(asUser(mickey, () -> assertThrows(
				AuthorizationException.class, () -> context.createLink(null, URI.create("https://digg.com/"), null))));

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNotNull(refused.get());
	}

	@Test
	public void importContext_withAKeptEntryThatCannotBeLoaded_failsAsAServerError(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Entry kept = ((Context) contextEntry.getResource())
				.createLink("_kept", URI.create("https://slashdot.org/"), null);
		removeFromEntryGraph(kept.getEntryURI(), RepositoryProperties.resource);
		evictFromSoftCache(kept);
		File importZip = createMinimalImportZip(tempDataDir);

		// the store's data failed, not the upload, which a RepositoryException would be answered as
		assertThrows(IllegalStateException.class, () -> cm.importContext(contextEntry, importZip));
	}

	@Test
	public void importContext_refusesAMoveIntoAListOfTheContextWhileItPrepares(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ListImpl keptList = (ListImpl) ((Context) contextEntry.getResource())
				.createResource("_list", GraphType.List, null, null).getResource();
		Context other = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
		Entry sourceList = other.createResource(null, GraphType.List, null, null);
		Entry moved = other.createLink(null, URI.create("https://slashdot.org/"), sourceList.getResourceURI());
		AtomicReference<Throwable> refused = new AtomicReference<>();
		// a move from another context creates a copy in this one, which the import refuses like any create
		((ContextManagerImpl) cm).whileImportPrepares = () -> refused.set(assertThrows(
				ContextImportPendingException.class,
				() -> keptList.moveEntryHere(moved.getEntryURI(), sourceList.getEntryURI(), true)));

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNotNull(refused.get());
		assertNotNull(other.getByEntryURI(moved.getEntryURI()), "the refused move leaves the entry where it was");
	}

	@Test
	public void importContext_removesAnEntryEditedWhileItPrepares(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry edited = context.createLink(null, URI.create("https://slashdot.org/"), null);
		((ContextManagerImpl) cm).whileImportPrepares = () -> {
			// past the millisecond of the modified date the plan read, which the edit must change
			sleep(10);
			Model metadata = edited.getLocalMetadata().getGraph();
			metadata.add(rm.getValueFactory().createIRI(edited.getResourceURI().toString()), DCTERMS.TITLE,
					rm.getValueFactory().createLiteral("Edited during the import"));
			edited.getLocalMetadata().setGraph(metadata);
		};

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNull(context.getByEntryURI(edited.getEntryURI()));
		assertFalse(hasGraph(edited.getLocalMetadataURI()),
				"the import must read again an entry edited after its plan, or the edit is left behind");
		assertFalse(hasGraph(edited.getEntryURI()));
	}

	@Test
	public void importContext_writesTheIndexReIndexWouldBuild(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		// replaced by the import, whose index triples by resource and by external metadata reIndex() does not write
		context.createLink(null, URI.create("https://slashdot.org/"), null);
		context.createReference(null, URI.create("https://example.com/referenced"),
				URI.create("https://example.com/referenced/metadata"), null);

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));
		Set<Statement> imported = indexOf(context);
		long importedCounter = counterOf(context);
		context.reIndex();

		assertEquals(indexOf(context), imported);
		assertEquals(counterOf(context), importedCounter);
	}

	@Test
	public void importContext_letsACreateTakeAnIdAboveTheImportedOnes(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		context.createLink(null, URI.create("https://slashdot.org/"), null);

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));

		assertEquals("13", context.createLink(null, URI.create("https://digg.com/"), null).getId());
	}

	@Test
	public void importContext_leavesAnIdBeyondTheCounterRangeOutOfTheCounter(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		String beyondTheIntRange = """
				<http://localhost:8181/99/entry/5000000000> {
					<http://localhost:8181/99/entry/5000000000> <%s> <http://localhost:8181/99/resource/5000000000> .
				}
				""".formatted(RepositoryProperties.resource);

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES + beyondTheIntRange));

		// the counter is read back as an int, so a larger one would fail every create that takes a generated id
		assertEquals("13", context.createLink(null, URI.create("https://digg.com/"), null).getId());
	}

	@Test
	public void importContext_replacesALinkWhoseGraphTypeIsUser(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry link = context.createLink(null, URI.create("https://example.com/someone"), null);
		link.setGraphType(GraphType.User);

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertNull(context.getByEntryURI(link.getEntryURI()));
		assertFalse(hasGraph(link.getEntryURI()));
	}

	@Test
	public void importContext_ofAContextRemovedWhileItPrepares_isRefused(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		File importZip = createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES);
		((ContextManagerImpl) cm).whileImportPrepares = () -> cm.remove(contextEntry.getEntryURI());

		assertThrows(ContextRemovalPendingException.class, () -> cm.importContext(contextEntry, importZip));
		awaitContextRemovals();
	}

	@Test
	public void importContext_completesWhenAnEntryIsRemovedWhileItPrepares(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry removedMeanwhile = context.createLink(null, URI.create("https://slashdot.org/"), null);
		((ContextManagerImpl) cm).whileImportPrepares = () -> context.remove(removedMeanwhile.getEntryURI());

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));

		assertNull(context.getByEntryURI(removedMeanwhile.getEntryURI()));
		assertTrue(hasGraph(URI.create(contextEntry.getResourceURI() + "/entry/7")), "the import committed");
	}

	@Test
	public void importContext_removesTheGraphsOfAnEntryItCannotLoad(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Entry corrupt = corruptLink((Context) contextEntry.getResource());

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		// found through the graph URIs an entry of its id has, since its entry graph cannot say which it has
		assertFalse(hasGraph(corrupt.getEntryURI()));
		assertFalse(hasGraph(corrupt.getLocalMetadataURI()));
	}

	@Test
	public void importContext_recordsTheEntriesItRemovesWhenDeletedEntriesAreTracked(@TempDir Path tempDataDir)
			throws Exception {
		rm.shutdown();
		setUpEnvironment(Map.of(Settings.REPOSITORY_TRACK_DELETED, "on", Settings.DATA_FOLDER, tempDataDir.toString()));
		TestSuite.initDisneySuite(rm);
		rm.setCheckForAuthorization(false);
		URI admin = pm.getAdminUser().getURI();
		pm.setAuthenticatedUserURI(admin);
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry removed = context.createLink(null, URI.create("https://slashdot.org/"), null);

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		ValueFactory vf = rm.getValueFactory();
		IRI removedIRI = vf.createIRI(removed.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertTrue(rc.hasStatement(removedIRI, RepositoryProperties.Deleted, null, false, context.resourceURI));
			assertTrue(rc.hasStatement(removedIRI, RepositoryProperties.DeletedBy, vf.createIRI(admin.toString()),
					false, context.resourceURI));
		}
	}

	@Test
	public void importContext_replacesALinkWhoseResourceIsNoJavaURI(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), null);
		ValueFactory vf = rm.getValueFactory();
		IRI linkIRI = vf.createIRI(link.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove(linkIRI, RepositoryProperties.resource, null, linkIRI);
			// a valid IRI, which java.net.URI rejects for the brackets in its path
			rc.add(linkIRI, RepositoryProperties.resource, vf.createIRI("https://example.com/x[1]"), linkIRI);
		}
		evictFromSoftCache(link);

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		assertFalse(hasGraph(link.getEntryURI()));
		assertFalse(context.getEntries().contains(link.getEntryURI()));
	}

	@Test
	public void importContext_failingInItsTransaction_leavesTheContextAsItWas(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry listEntry = context.createResource("_list", GraphType.List, null, null);
		Entry removed = context.createLink(null, URI.create("https://slashdot.org/"), listEntry.getResourceURI());
		Entry kept = context.createLink("_kept", URI.create("https://digg.com/"), listEntry.getResourceURI());
		ListImpl list = spy((ListImpl) listEntry.getResource());
		((EntryImpl) listEntry).setResource(list);
		// fails once the list is pruned and stamped modified in memory, which the rollback must undo
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure");
		}).when(list).pruneExactly(any(RepositoryConnection.class), any());
		Date listModifiedBefore = listEntry.getModifiedDate();
		long counterBefore = counterOf(context);
		File importZip = createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES);
		// past the millisecond of the list's modified date, which the prune stamps
		sleep(10);

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> cm.importContext(contextEntry, importZip));

		assertTrue(thrown.getMessage().contains("commit"), "the import failed in its transaction, not while planning");
		assertNotNull(context.getByEntryURI(removed.getEntryURI()));
		assertFalse(removed.isDeleted());
		assertEquals(List.of(removed.getEntryURI(), kept.getEntryURI()), storedMembers(listEntry));
		assertEquals(listModifiedBefore, listEntry.getModifiedDate());
		assertFalse(hasGraph(URI.create(contextEntry.getResourceURI() + "/entry/7")), "nothing was imported");
		assertEquals(counterBefore, counterOf(context));
		assertNotNull(context.createLink(null, URI.create("https://example.com/"), null),
				"a failed import must not keep refusing creates in the context");
	}

	@Test
	public void importContext_skipsAStatementInAGraphOfAnotherContext(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Entry other = newContext().createLink(null, URI.create("https://digg.com/"), null);
		String plantedInOther = """
				<%1$s> {
					<%2$s> <%3$s> "planted" .
				}
				""".formatted(other.getEntryURI(), other.getResourceURI(), DCTERMS.TITLE);

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES + plantedInOther));

		IRI otherEntryGraph = rm.getValueFactory().createIRI(other.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertFalse(rc.hasStatement(null, DCTERMS.TITLE, null, false, otherEntryGraph));
		}
		assertTrue(hasGraph(URI.create(contextEntry.getResourceURI() + "/entry/7")), "the rest is imported");
	}

	/** A link with a title whose entry graph has lost its resource statement, evicted so it is loaded again. */
	private Entry corruptLink(Context context) {
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), null);
		Model metadata = link.getLocalMetadata().getGraph();
		metadata.add(rm.getValueFactory().createIRI(link.getResourceURI().toString()), DCTERMS.TITLE,
				rm.getValueFactory().createLiteral("a link"));
		link.getLocalMetadata().setGraph(metadata);
		removeFromEntryGraph(link.getEntryURI(), RepositoryProperties.resource);
		evictFromSoftCache(link);
		assertTrue(hasGraph(link.getLocalMetadataURI()), "precondition: the metadata graph holds the title");
		return link;
	}

	/** Entries 7 and 12 of the exported context 99; 12 is a reference, which the index also lists by metadata. */
	private static final String TWO_IMPORTED_ENTRIES = """
			<http://localhost:8181/99/entry/7> {
				<http://localhost:8181/99/entry/7> <%1$s> <http://localhost:8181/99/resource/7> .
			}
			<http://localhost:8181/99/entry/12> {
				<http://localhost:8181/99/entry/12> <%1$s> <http://example.com/resource> .
				<http://localhost:8181/99/entry/12> <%2$s> <http://example.com/metadata> .
			}
			""".formatted(RepositoryProperties.resource, RepositoryProperties.externalMetadata);

	@Test
	public void importContext_cancelsATreeRemovalPendingInTheContext(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry list = context.createResource(null, GraphType.List, null, null);
		IRI listEntry = rm.getValueFactory().createIRI(list.getEntryURI().toString());
		ContextImpl other = (ContextImpl) cm.createResource(null, GraphType.Context, null, null).getResource();
		Entry otherList = other.createResource(null, GraphType.List, null, null);
		IRI otherListEntry = rm.getValueFactory().createIRI(otherList.getEntryURI().toString());
		IRI admin = rm.getValueFactory().createIRI(pm.getAdminUser().getURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(listEntry, RepositoryProperties.treeRemovalPending, admin, context.resourceURI);
			rc.add(otherListEntry, RepositoryProperties.treeRemovalPending, admin, other.resourceURI);
		}

		cm.importContext(contextEntry, createMinimalImportZip(tempDataDir));

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertFalse(rc.hasStatement(listEntry, RepositoryProperties.treeRemovalPending, null, false,
					context.resourceURI), "an import must cancel a tree removal whose list it may replace");
			assertTrue(rc.hasStatement(otherListEntry, RepositoryProperties.treeRemovalPending, null, false,
					other.resourceURI), "an import leaves the tree removals of other contexts alone");
		}
	}

	@Test
	public void importContext_endsATreeRemovalWaitingForARetry(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Entry list = context.createResource(null, GraphType.List, null, null);
		IRI listEntry = rm.getValueFactory().createIRI(list.getEntryURI().toString());
		ListTreeRemover remover = ((ContextManagerImpl) cm).listTreeRemover;
		remover.retryDelays = List.of(Duration.ofMinutes(10));
		AtomicBoolean failed = new AtomicBoolean();
		remover.beforeChunk = () -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("the store failed");
			}
		};
		((org.entrystore.List) list.getResource()).removeTree();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!remover.isWaitingForRetry(listEntry)) {
			assertTrue(System.nanoTime() < deadline, "the run did not fail");
			Thread.sleep(10);
		}

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));

		// a delete or a move of the list would otherwise be refused until the retry, minutes later
		assertFalse(remover.isPending(listEntry));
		assertFalse(remover.isBeingRemoved(listEntry));
	}

	@Test
	public void importContext_leavesTheRetriesOfOtherContexts(@TempDir Path tempDataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl other = (ContextImpl) cm.createResource(null, GraphType.Context, null, null).getResource();
		Entry otherList = other.createResource(null, GraphType.List, null, null);
		IRI otherListEntry = rm.getValueFactory().createIRI(otherList.getEntryURI().toString());
		ListTreeRemover remover = ((ContextManagerImpl) cm).listTreeRemover;
		remover.retryDelays = List.of(Duration.ofMinutes(10));
		remover.beforeChunk = () -> {
			throw new IllegalStateException("the store failed");
		};
		((org.entrystore.List) otherList.getResource()).removeTree();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!remover.isWaitingForRetry(otherListEntry)) {
			assertTrue(System.nanoTime() < deadline, "the run did not fail");
			Thread.sleep(10);
		}

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));

		assertTrue(remover.isWaitingForRetry(otherListEntry), "an import ends only the removals in its own context");
		assertTrue(remover.isPending(otherListEntry));
	}

	@Test
	public void importContext_takesTheImportedEntriesIntoIndexesLoadedBefore(@TempDir Path tempDataDir)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		Set<URI> imported = Set.of(URI.create(contextEntry.getResourceURI() + "/entry/7"),
				URI.create(contextEntry.getResourceURI() + "/entry/12"));
		// loads the indexes, which a purge only takes removed entries out of; read as entry URIs, since the imported
		// entry graphs are too thin to load
		assertTrue(Collections.disjoint(imported, context.getEntries()));

		cm.importContext(contextEntry, createImportZip(tempDataDir, TWO_IMPORTED_ENTRIES));

		assertTrue(context.getEntries().containsAll(imported));
	}

	/** The index triples of the context, without the counter, which {@link #counterOf} compares by value. */
	private Set<Statement> indexOf(ContextImpl context) {
		ValueFactory vf = rm.getValueFactory();
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.getStatements(null, null, null, false, context.resourceURI).stream()
					.filter(s -> s.getPredicate().equals(RepositoryProperties.resHasEntry)
							|| s.getPredicate().equals(RepositoryProperties.mdHasEntry))
					.map(s -> vf.createStatement(s.getSubject(), s.getPredicate(), s.getObject()))
					.collect(Collectors.toSet());
		}
	}

	private long counterOf(ContextImpl context) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.getStatements(context.resourceURI, RepositoryProperties.counter, null, false,
					context.resourceURI).stream().mapToLong(s -> ((Literal) s.getObject()).longValue()).max()
					.orElse(-1);
		}
	}

	/** Runs {@code work} on a thread of its own and waits for it, so the caller's monitors do not carry over. */
	private static <T> T onAnotherThread(Callable<T> work) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			return executor.submit(work).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new AssertionError("the work on another thread did not complete", e);
		} finally {
			executor.shutdownNow();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}
}
