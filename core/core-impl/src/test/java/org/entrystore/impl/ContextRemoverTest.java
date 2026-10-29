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

import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.DCAT;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.VCARD4;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.base.RepositoryWrapper;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.SearchIndex;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.ContextRemovalPendingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.rdf4j.model.util.Values.bnode;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ENTRYSTORE-1258. Removing a context hides it at once and leaves its graphs, files and search documents to
 * {@link ContextRemover}, which deletes them in chunks without holding the repository monitor in between.
 */
public class ContextRemoverTest extends AbstractCoreTest {

	/** Released after each test, so a job held by {@link #holdJobBeforeFirstChunk()} cannot delay the shutdown. */
	private final CountDownLatch release = new CountDownLatch(1);

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);
	}

	@AfterEach
	public void releaseHeldJob() {
		release.countDown();
	}

	@Test
	public void removeContext_hidesTheContextAtOnceAndDeletesItsGraphsInTheBackground() throws Exception {
		ContextImpl doomed = newContext();
		Entry child = doomed.createLink(null, URI.create("http://example.com/child"), null);
		holdJobBeforeFirstChunk();

		cm.remove(doomed.getEntry().getEntryURI());

		assertNull(cm.getContext(doomed.id), "the context is gone at once");
		assertNull(cm.getEntry(child.getEntryURI()), "and so are its entries");
		assertTrue(cm.getLinks(URI.create("http://example.com/child")).isEmpty(),
			"also when found through the context's index graph, which still names them");
		assertTrue(cm.isRemovalPending(doomed.id));
		assertTrue(hasGraph(child.getEntryURI()), "while its graphs are still being deleted");
		assertNotNull(rm.getSoftCache().getByEntryURI(child.getEntryURI()), "and its entries are still cached");

		release.countDown();
		awaitContextRemovals();

		assertFalse(hasGraphsOf(doomed), "every graph of the context is deleted");
		assertFalse(hasMarker(doomed), "and the marker with them");
		assertFalse(cm.isRemovalPending(doomed.id));
		assertNull(rm.getSoftCache().getByEntryURI(child.getEntryURI()), "its entries are evicted from the cache");
		assertTrue(child.isDeleted(), "and an entry still held reports itself removed");
	}

	@Test
	public void removeContext_leavesAContextWhoseIdExtendsTheRemovedIdAlone() throws Exception {
		ContextImpl doomed = (ContextImpl) cm.createResource("doomed", GraphType.Context, null, null).getResource();
		ContextImpl neighbour = (ContextImpl) cm.createResource("doomed2", GraphType.Context, null, null).getResource();
		Entry survivor = neighbour.createLink(null, URI.create("http://example.com/survivor"), null);

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(hasGraph(survivor.getEntryURI()));
		assertNotNull(rm.getSoftCache().getByEntryURI(survivor.getEntryURI()), "nor is it evicted from the cache");
		assertNotNull(cm.getEntry(survivor.getEntryURI()));
	}

	@Test
	public void aRemovedContextInstanceNoLongerLoadsItsEntries() throws Exception {
		ContextImpl doomed = newContext();
		Entry child = doomed.createLink(null, URI.create("http://example.com/child"), null);
		holdJobBeforeFirstChunk();

		cm.remove(doomed.getEntry().getEntryURI());
		evictFromSoftCache(child);

		assertNull(doomed.getByEntryURI(child.getEntryURI()), "its graphs are still there, but it is removed");
	}

	@Test
	public void aReferenceStopsShowingTheMetadataOfARemovedEntry() throws Exception {
		ContextImpl doomed = newContext();
		Entry described = doomed.createResource(null, GraphType.None, null, null);
		Model title = new LinkedHashModel();
		title.add(iri(described.getResourceURI().toString()), DCTERMS.TITLE, literal("doomed"));
		described.getLocalMetadata().setGraph(title);
		Entry reference = newContext().createReference(null, described.getResourceURI(),
			described.getLocalMetadataURI(), null);
		assertFalse(reference.getCachedExternalMetadata().getGraph().isEmpty(), "the reference must show the "
			+ "metadata for this test to mean anything");
		holdJobBeforeFirstChunk();

		cm.remove(doomed.getEntry().getEntryURI());

		assertTrue(reference.getCachedExternalMetadata().getGraph().isEmpty(),
			"the metadata graph still exists, but its entry is removed");
	}

	@Test
	public void removeContext_releasesTheMonitorBetweenChunks() throws Exception {
		ContextImpl doomed = newContext();
		for (int i = 0; i < 3; i++) {
			doomed.createLink(null, URI.create("http://example.com/child-" + i), null);
		}
		remover().chunkSize = 1;
		List<Boolean> monitorHeldAfterChunk = new CopyOnWriteArrayList<>();
		remover().afterChunk = () -> monitorHeldAfterChunk.add(Thread.holdsLock(rm.getRepository()));

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(monitorHeldAfterChunk.size() > 1, "the context must take several chunks for this test to mean "
			+ "anything");
		assertFalse(monitorHeldAfterChunk.contains(true), "other writers must get the monitor between two chunks");
		assertFalse(hasGraphsOf(doomed), "and a graph larger than a chunk is still deleted completely");
	}

	@Test
	public void removeContext_removesTheRelationCopiesItsMetadataLeftInOtherContexts() throws Exception {
		ContextImpl doomed = newContext();
		Entry target = newContext().createResource(null, GraphType.None, null, null);
		Entry source = doomed.createResource(null, GraphType.None, null, null);
		source.getLocalMetadata().setGraph(relation(source.getResourceURI(), target));
		assertFalse(target.getRelations().isEmpty(), "the relation must have a copy for this test to mean anything");

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(target.getRelations().isEmpty());
	}

	@Test
	public void removeContext_removesTheRelationCopiesItsCachedExternalMetadataLeftInOtherContexts() throws Exception {
		ContextImpl doomed = newContext();
		ContextImpl neighbour = newContext();
		Entry target = neighbour.createResource(null, GraphType.None, null, null);
		Entry described = neighbour.createResource(null, GraphType.None, null, null);
		// Cached external metadata has copies only when it describes a resource of this repository.
		Entry reference = doomed.createReference(null, described.getResourceURI(),
			URI.create("http://example.com/external-metadata"), null);
		reference.getCachedExternalMetadata().setGraph(relation(described.getResourceURI(), target));
		assertFalse(target.getRelations().isEmpty(), "the relation must have a copy for this test to mean anything");

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(target.getRelations().isEmpty());
	}

	@Test
	public void removeContext_removesTheRelationCopiesItsEntryGraphLeftInOtherContexts() throws Exception {
		ContextImpl doomed = newContext();
		Entry target = newContext().createResource(null, GraphType.None, null, null);
		EntryImpl source = (EntryImpl) doomed.createResource(null, GraphType.None, null, null);
		Model entryGraph = source.getGraph();
		entryGraph.add(iri(source.getResourceURI().toString()), DCTERMS.RELATION,
			iri(target.getResourceURI().toString()));
		source.setGraph(entryGraph);
		assertFalse(target.getRelations().isEmpty(), "the relation must have a copy for this test to mean anything");

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(target.getRelations().isEmpty());
	}

	@Test
	public void removeContext_removesBlankNodesByPatternWhereTheStoreCannotMatchThem() throws Exception {
		ContextImpl doomed = newContext();
		Entry contact = doomed.createResource(null, GraphType.None, null, null);
		contact.getLocalMetadata().setGraph(contactPoint(contact));
		Entry neighbour = newContext().createResource(null, GraphType.None, null, null);
		neighbour.getLocalMetadata().setGraph(contactPoint(neighbour));
		remover().blankNodesByPattern = true;

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(hasGraphsOf(doomed));
		assertEquals(2, neighbour.getLocalMetadata().getGraph().size(),
			"the wildcards stay within the removed context's graphs");
	}

	@Test
	public void aStoreReturningStatementsWithoutTheirGraphHasBlankNodesRemovedWithinTheContextOnly() throws Exception {
		ContextImpl doomed = newContext();
		Entry contact = doomed.createResource(null, GraphType.None, null, null);
		contact.getLocalMetadata().setGraph(contactPoint(contact));
		Entry neighbour = newContext().createResource(null, GraphType.None, null, null);
		neighbour.getLocalMetadata().setGraph(contactPoint(neighbour));
		holdJobBeforeFirstChunk();
		cm.remove(doomed.getEntry().getEntryURI());
		ContextRemover sparqlLike = removerOver(new SparqlLikeRepository(rm.getRepository()));
		sparqlLike.blankNodesByPattern = true;

		sparqlLike.schedule(doomed.id);
		sparqlLike.awaitIdle();
		sparqlLike.shutdown();

		assertFalse(hasGraphsOf(doomed));
		assertEquals(2, neighbour.getLocalMetadata().getGraph().size(),
			"the wildcards stay within the removed context's graphs");
	}

	@Test
	public void aStoreReturningStatementsWithoutTheirGraphKeepsATripleAnotherContextStates() throws Exception {
		ContextImpl doomed = newContext();
		Entry described = doomed.createResource(null, GraphType.None, null, null);
		Entry link = newContext().createLink(null, described.getResourceURI(), null);
		Model title = new LinkedHashModel();
		title.add(iri(described.getResourceURI().toString()), DCTERMS.TITLE, literal("shared"));
		described.getLocalMetadata().setGraph(title);
		link.getLocalMetadata().setGraph(title);
		holdJobBeforeFirstChunk();
		cm.remove(doomed.getEntry().getEntryURI());
		ContextRemover sparqlLike = removerOver(new SparqlLikeRepository(rm.getRepository()));

		sparqlLike.schedule(doomed.id);
		sparqlLike.awaitIdle();
		sparqlLike.shutdown();

		assertFalse(hasGraphsOf(doomed));
		assertTrue(link.getLocalMetadata().getGraph().contains(iri(described.getResourceURI().toString()),
			DCTERMS.TITLE, literal("shared")), "the removal stays within the removed context's graphs");
	}

	@Test
	public void aStoreReturningStatementsWithoutTheirGraphHasTheRelationCopiesRemoved() throws Exception {
		ContextImpl doomed = newContext();
		Entry target = newContext().createResource(null, GraphType.None, null, null);
		Entry source = doomed.createResource(null, GraphType.None, null, null);
		source.getLocalMetadata().setGraph(relation(source.getResourceURI(), target));
		assertFalse(target.getRelations().isEmpty(), "the relation must have a copy for this test to mean anything");
		holdJobBeforeFirstChunk();
		cm.remove(doomed.getEntry().getEntryURI());
		ContextRemover sparqlLike = removerOver(new SparqlLikeRepository(rm.getRepository()));

		sparqlLike.schedule(doomed.id);
		sparqlLike.awaitIdle();
		sparqlLike.shutdown();

		assertTrue(target.getRelations().isEmpty(), "the copy is found by the graphs holding it, not by its own graph");
	}

	@Test
	public void aSparqlStoreHasBlankNodesRemovedByPattern() {
		ContextRemover sparql = removerOver(new SPARQLRepository("http://localhost:8181/sparql"));
		sparql.shutdown();

		assertTrue(sparql.blankNodesByPattern, "a SPARQL store returns its blank nodes anew on every read");
	}

	@Test
	public void aGraphThatCannotBeEmptiedStopsTheRemoval() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null);
		holdJobBeforeFirstChunk();
		cm.remove(doomed.getEntry().getEntryURI());
		ContextRemover stuck = removerOver(new RemovalIgnoringRepository(rm.getRepository()));
		stuck.chunkSize = 1; // so that a graph is read again after its first chunk

		stuck.schedule(doomed.id);
		stuck.awaitIdle();
		stuck.shutdown();

		assertTrue(stuck.isPending(doomed.id), "the job gives up on the graph instead of rereading it forever");
		assertTrue(hasMarker(doomed));
	}

	@Test
	public void removeContext_listsTheGraphsOfALargeContextInPortions() throws Exception {
		ContextImpl doomed = newContext();
		for (int i = 0; i < 5; i++) {
			doomed.createLink(null, URI.create("http://example.com/child-" + i), null);
		}
		remover().graphsPerListing = 2;

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(hasGraphsOf(doomed), "a portion that fills a listing is not counted as a pass of its own");
		assertFalse(hasMarker(doomed));
	}

	@Test
	public void removeContext_keepsARelationCopyThatAGraphOutsideTheContextStillStates() throws Exception {
		ContextImpl doomed = newContext();
		ContextImpl neighbour = newContext();
		Entry target = neighbour.createResource(null, GraphType.None, null, null);
		Entry source = doomed.createResource(null, GraphType.None, null, null);
		source.getLocalMetadata().setGraph(relation(source.getResourceURI(), target));
		Entry link = neighbour.createLink(null, source.getResourceURI(), null);
		link.getLocalMetadata().setGraph(relation(source.getResourceURI(), target));

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(target.getRelations().contains(iri(source.getResourceURI().toString()), DCTERMS.RELATION,
			iri(target.getResourceURI().toString())), "the link's metadata still states the relation");
	}

	@Test
	public void removeContext_deletesTheDataFolderOfTheContext(@TempDir Path dataFolder) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Entry data = doomed.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		((Data) data.getResource()).setData(new ByteArrayInputStream("doomed".getBytes(UTF_8)));
		assertTrue(Files.isDirectory(dataFolder.resolve(doomed.id)), "the context must have a data folder for "
			+ "this test to mean anything");

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(Files.exists(dataFolder.resolve(doomed.id)));
	}

	@Test
	public void aDataEntryOfARemovedContextCannotRecreateItsFolder(@TempDir Path dataFolder) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Data data = (Data) doomed.createResource(null, GraphType.None, ResourceType.InformationResource, null)
			.getResource();

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertThrows(DataImpl.EntryRemovedException.class,
			() -> data.setData(new ByteArrayInputStream("late".getBytes(UTF_8))));
		assertFalse(Files.exists(dataFolder.resolve(doomed.id)));
	}

	@Test
	public void aDataFolderThatIsALinkIsNotFollowedAndKeepsTheRemovalPending(@TempDir Path dataFolder,
			@TempDir Path elsewhere) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null);
		Path kept = Files.writeString(elsewhere.resolve("kept"), "not the context's");
		try {
			Files.createSymbolicLink(dataFolder.resolve(doomed.id), elsewhere);
		} catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "symbolic links cannot be created here");
		}

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(Files.exists(kept), "a folder outside the data folder must not be deleted");
		assertTrue(hasMarker(doomed), "the failed deletion keeps the removal pending, as any file failure does");
		assertTrue(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void removeContext_deletesALinkInItsDataFolderButNotWhatItPointsTo(@TempDir Path dataFolder,
			@TempDir Path elsewhere) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Path folder = Files.createDirectories(dataFolder.resolve(doomed.id));
		Path kept = Files.writeString(elsewhere.resolve("kept"), "not the context's");
		try {
			Files.createSymbolicLink(folder.resolve("link"), elsewhere);
		} catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "symbolic links cannot be created here");
		}

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(Files.exists(kept), "the folder a link points to must not be emptied");
		assertFalse(Files.exists(folder));
		assertFalse(hasMarker(doomed));
	}

	@Test
	public void removeContext_finishesWhenAFileVanishesFromTheDataFolderMeanwhile(@TempDir Path dataFolder)
			throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Path folder = Files.createDirectories(dataFolder.resolve(doomed.id));
		Files.writeString(folder.resolve(".upload.part"), "in flight");
		// as an upload in flight deletes its staging files once it finds its entry removed
		remover().beforeDelete = path -> {
			if (Files.isRegularFile(path)) {
				path.toFile().delete();
			}
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(Files.exists(folder));
		assertFalse(hasMarker(doomed));
		assertFalse(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void removeContext_deletesAFileAddedToTheDataFolderMeanwhile(@TempDir Path dataFolder) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Path folder = Files.createDirectories(dataFolder.resolve(doomed.id));
		AtomicInteger added = new AtomicInteger();
		// as an upload in flight writes the staging file of its digest after the folder was read
		remover().beforeDelete = path -> {
			if (Files.isDirectory(path) && added.getAndIncrement() == 0) {
				writeStagingFile(path.resolve(".digest.part"));
			}
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(Files.exists(folder));
		assertFalse(hasMarker(doomed));
		assertFalse(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void aDataFolderThatKeepsGainingFilesKeepsTheRemovalPending(@TempDir Path dataFolder) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		ContextImpl doomed = newContext();
		Path folder = Files.createDirectories(dataFolder.resolve(doomed.id));
		AtomicInteger added = new AtomicInteger();
		remover().beforeDelete = path -> {
			if (Files.isDirectory(path)) {
				writeStagingFile(path.resolve(".late-" + added.incrementAndGet() + ".part"));
			}
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(Files.exists(folder));
		assertTrue(hasMarker(doomed), "the job gives up after a few passes instead of chasing new files forever");
		assertTrue(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void aRemovalMarkerThatNamesNoContextIsIgnored(@TempDir Path dataFolder) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		Path kept = Files.writeString(dataFolder.resolve("kept"), "another context's file");
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			// the last segment of the base URL is empty, which would name the data folder itself
			rc.add(iri(rm.getRepositoryURL().toString()), RepositoryProperties.removalPending, literal("planted"),
				((ContextManagerImpl) cm).getSesameURI());
		}

		remover().resumePending();
		awaitContextRemovals();

		assertTrue(Files.exists(kept));
		assertFalse(cm.isRemovalPending(""));
	}

	@Test
	public void removeContext_purgesTheSearchIndexAndFinishesWhenThatFails() throws Exception {
		ContextImpl doomed = newContext();
		SearchIndex searchIndex = mock(SearchIndex.class);
		when(searchIndex.removeContext(any())).thenReturn(false);
		remover().searchIndex = () -> searchIndex;

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		verify(searchIndex).removeContext(doomed.getURI());
		assertFalse(hasMarker(doomed), "the purge is best effort, its failure does not keep the removal pending");
		assertFalse(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void removeContext_finishesWhenTheSearchIndexThrows() throws Exception {
		ContextImpl doomed = newContext();
		SearchIndex searchIndex = mock(SearchIndex.class);
		// as SolrJ reports an error response from Solr
		when(searchIndex.removeContext(any())).thenThrow(new IllegalStateException("Solr answered 500"));
		remover().searchIndex = () -> searchIndex;

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(hasMarker(doomed));
		assertFalse(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void removeContext_sweepsAGraphWrittenAfterTheFirstPass() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null);
		IRI lateGraph = iri(doomed.getURI() + "/metadata/late");
		SearchIndex searchIndex = mock(SearchIndex.class);
		// the search purge runs between the two passes, where an entry loaded before the removal may still write
		when(searchIndex.removeContext(any())).thenAnswer(invocation -> {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				rc.add(iri("http://example.com/late"), DCTERMS.TITLE, literal("late"), lateGraph);
			}
			return true;
		});
		remover().searchIndex = () -> searchIndex;

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertFalse(hasGraph(URI.create(lateGraph.stringValue())));
		assertFalse(hasMarker(doomed));
	}

	@Test
	public void aContextIdStaysTakenUntilItsRemovalHasFinished() throws Exception {
		ContextImpl doomed = (ContextImpl) cm.createResource("reused", GraphType.Context, null, null).getResource();
		Entry child = doomed.createLink(null, URI.create("http://example.com/child"), null);
		holdJobBeforeFirstChunk();

		cm.remove(doomed.getEntry().getEntryURI());

		assertThrows(ContextRemovalPendingException.class,
			() -> cm.createResource("reused", GraphType.Context, null, null));

		release.countDown();
		awaitContextRemovals();
		Context recreated = (Context) cm.createResource("reused", GraphType.Context, null, null).getResource();

		assertTrue(recreated.getEntries().isEmpty());
		assertNull(recreated.get(child.getId()), "an entry of the removed context must not reappear in the new one");
	}

	@Test
	public void aGeneratedContextIdSkipsAnIdStillBeingRemoved() throws Exception {
		String nextGeneratedId = String.valueOf(Long.parseLong(newContext().id) + 1);
		ContextImpl doomed = (ContextImpl) cm.createResource(nextGeneratedId, GraphType.Context, null, null)
			.getResource();
		doomed.createLink(null, URI.create("http://example.com/child"), null);
		holdJobBeforeFirstChunk();

		cm.remove(doomed.getEntry().getEntryURI());
		ContextImpl generated = newContext();

		assertNotEquals(nextGeneratedId, generated.id, "the job would otherwise delete the new context's graphs");
		assertFalse(generated.isDeleted());
	}

	@Test
	public void anOlderInstanceOfARemovedContextCannotCreateEntries() throws Exception {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl older = (ContextImpl) contextEntry.getResource();
		older.createLink(null, URI.create("http://example.com/child"), null);
		evictFromSoftCache(contextEntry);
		ContextImpl current = (ContextImpl) cm.getContext(older.id);
		assertNotSame(older, current, "the context must have two instances for this test to mean anything");
		holdJobBeforeFirstChunk();

		cm.remove(current.getEntry().getEntryURI());

		assertThrows(ContextRemovalPendingException.class,
			() -> older.createLink(null, URI.create("http://example.com/late"), null));

		release.countDown();
		awaitContextRemovals();

		assertThrows(ContextRemovalPendingException.class,
			() -> older.createLink(null, URI.create("http://example.com/later"), null),
			"also once the removal has finished and freed the id");
	}

	@Test
	public void anOlderInstanceUntouchedDuringTheRemovalCannotCreateEntriesAfterIt() throws Exception {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl older = (ContextImpl) contextEntry.getResource();
		// held, so it stays cached and the job finds the older instance through it
		Entry child = older.createLink(null, URI.create("http://example.com/child"), null);
		evictFromSoftCache(contextEntry);
		ContextImpl current = (ContextImpl) cm.getContext(older.id);
		assertNotSame(older, current, "the context must have two instances for this test to mean anything");

		cm.remove(current.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(child.isDeleted());
		assertThrows(ContextRemovalPendingException.class,
			() -> older.createLink(null, URI.create("http://example.com/later"), null));
	}

	@Test
	public void creatingAnEntryInAListOfARemovedContextIsRefusedAsSuch() throws Exception {
		ContextImpl doomed = newContext();
		Entry list = doomed.createResource(null, GraphType.List, null, null);

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertThrows(ContextRemovalPendingException.class,
			() -> doomed.createLink(null, URI.create("http://example.com/late"), list.getResourceURI()),
			"not as a list that cannot be found");
	}

	@Test
	public void aCreateWaitingForTheMonitorWhileItsContextIsRemovedIsRefused() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null); // a chunk, so the job is held
		holdJobBeforeFirstChunk();
		FutureTask<Entry> lateCreate = new FutureTask<>(
			() -> doomed.createLink("late", URI.create("http://example.com/late"), null));

		synchronized (rm.getRepository()) {
			Thread creator = Thread.ofPlatform().name("late-create").start(lateCreate);
			awaitBlockedOn(creator, rm.getRepository());
			// the removal enters the monitor again, so it commits while the create waits for it
			cm.remove(doomed.getEntry().getEntryURI());
		}

		ExecutionException refused = assertThrows(ExecutionException.class,
			() -> lateCreate.get(10, TimeUnit.SECONDS));
		assertInstanceOf(ContextRemovalPendingException.class, refused.getCause(),
			"the check before the monitor passed, so the one under it refuses");
		assertFalse(hasGraph(URI.create(doomed.getURI() + "/entry/late")), "no graph lands in the removed context");
	}

	@Test
	public void aFailedRemovalKeepsTheContextHiddenAndItsIdTaken() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null);
		remover().afterChunk = () -> {
			throw new IllegalStateException("simulated failure");
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(hasMarker(doomed), "the marker stays, so the next start resumes the removal");
		assertTrue(cm.isRemovalPending(doomed.id));
		assertNull(cm.getContext(doomed.id));
	}

	@Test
	public void aContextWhoseGraphsKeepAppearingStaysPending() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child"), null); // a chunk, so the hook runs
		AtomicInteger lateGraphs = new AtomicInteger();
		remover().afterChunk = () -> {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				rc.add(iri("http://example.com/late"), DCTERMS.TITLE, literal("late"),
					iri(doomed.getURI() + "/metadata/late-" + lateGraphs.incrementAndGet()));
			}
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(hasMarker(doomed), "the job gives up after a few passes instead of chasing new graphs forever");
		assertTrue(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void aContextWhoseGraphsSurviveEveryFullListingStaysPending() throws Exception {
		ContextImpl doomed = newContext();
		doomed.createLink(null, URI.create("http://example.com/child-1"), null);
		doomed.createLink(null, URI.create("http://example.com/child-2"), null);
		List<Resource> graphs = graphsOf(doomed);
		assertTrue(graphs.size() >= 3, "the context must have three graphs for this test to mean anything");
		// one short of all, so that every listing is full and any two listings share a graph
		remover().graphsPerListing = graphs.size() - 1;
		remover().afterChunk = () -> {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				for (Resource graph : graphs) {
					rc.add(iri("http://example.com/survivor"), DCTERMS.TITLE, literal("survivor"), graph);
				}
			}
		};

		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();

		assertTrue(hasMarker(doomed), "the job gives up instead of listing the same graphs forever");
		assertTrue(cm.isRemovalPending(doomed.id));
	}

	@Test
	public void aShutdownStopsTheRemovalBetweenChunksAndTheNextStartFinishesIt(@TempDir Path store) throws Exception {
		Map<String, String> persistentStore = Map.of(Settings.STORE_PATH, store.toUri().toString());
		rm.shutdown();
		setUpEnvironment(persistentStore);
		rm.setCheckForAuthorization(false);
		ContextImpl doomed = newContext();
		Entry child = doomed.createLink(null, URI.create("http://example.com/child"), null);
		ContextRemover remover = remover();
		remover.chunkSize = 1;
		AtomicInteger chunks = new AtomicInteger();
		remover.afterChunk = chunks::incrementAndGet;
		CountDownLatch jobHeld = new CountDownLatch(1);
		remover.beforeChunk = () -> {
			jobHeld.countDown();
			awaitRelease();
		};
		cm.remove(doomed.getEntry().getEntryURI());
		jobHeld.await();

		Thread shutdown = Thread.ofPlatform().start(rm::shutdown);
		while (!remover.stopping) {
			Thread.onSpinWait();
		}
		release.countDown();
		shutdown.join();

		assertEquals(1, chunks.get(), "the job stops after the chunk it was about to read");
		setUpEnvironment(persistentStore);
		awaitContextRemovals();
		assertFalse(hasGraph(child.getEntryURI()));
		assertFalse(hasMarker(doomed));
	}

	@Test
	public void aRemovalThatDidNotFinishResumesAfterARestart(@TempDir Path store) throws Exception {
		Map<String, String> persistentStore = Map.of(Settings.STORE_PATH, store.toUri().toString());
		rm.shutdown();
		setUpEnvironment(persistentStore);
		rm.setCheckForAuthorization(false);
		ContextImpl doomed = newContext();
		Entry child = doomed.createLink(null, URI.create("http://example.com/child"), null);
		remover().beforeChunk = () -> {
			throw new IllegalStateException("simulated crash");
		};
		cm.remove(doomed.getEntry().getEntryURI());
		awaitContextRemovals();
		assertTrue(hasGraph(child.getEntryURI()), "the first run must leave graphs behind for this test to mean "
			+ "anything");

		rm.shutdown();
		setUpEnvironment(persistentStore);
		awaitContextRemovals();

		assertFalse(hasGraph(child.getEntryURI()));
		assertFalse(hasMarker(doomed));
		assertFalse(cm.isRemovalPending(doomed.id));
	}

	private ContextImpl newContext() {
		return (ContextImpl) cm.createResource(null, GraphType.Context, null, null).getResource();
	}

	private ContextRemover remover() {
		return ((ContextManagerImpl) cm).contextRemover;
	}

	/**
	 * A second remover, over a store that behaves unlike the memory store. A test that holds the job of the first
	 * one leaves the removal to this one.
	 */
	private ContextRemover removerOver(Repository repository) {
		return new ContextRemover(rm, repository, ((ContextManagerImpl) cm).getSesameURI());
	}

	/**
	 * Holds the job before it reads anything, until {@link #release} is counted down. Only a context with an entry
	 * has graphs of its own, so for an empty one the job finishes without reaching the hold.
	 */
	private void holdJobBeforeFirstChunk() {
		remover().beforeChunk = this::awaitRelease;
	}

	private void awaitRelease() {
		try {
			release.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Waits until the thread is blocked on entering the monitor, which this thread holds. */
	private static void awaitBlockedOn(Thread thread, Object monitor) {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			ThreadInfo info = threads.getThreadInfo(thread.threadId());
			if (info != null && info.getThreadState() == Thread.State.BLOCKED
					&& info.getLockOwnerId() == Thread.currentThread().threadId()
					&& info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) {
				return;
			}
			Thread.yield();
		}
		throw new AssertionError(thread.getName() + " did not reach the monitor");
	}

	/** Writes a staging file from a hook, which cannot throw an IOException. */
	private static void writeStagingFile(Path file) {
		try {
			Files.writeString(file, "late");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Model relation(URI subject, Entry target) {
		Model model = new LinkedHashModel();
		model.add(iri(subject.toString()), DCTERMS.RELATION, iri(target.getResourceURI().toString()));
		return model;
	}

	/** A contact point given as a blank node, as DCAT metadata commonly has it. */
	private static Model contactPoint(Entry entry) {
		Model model = new LinkedHashModel();
		BNode contact = bnode();
		model.add(iri(entry.getResourceURI().toString()), DCAT.CONTACT_POINT, contact);
		model.add(contact, VCARD4.FN, literal("Contact"));
		return model;
	}

	private boolean hasGraph(URI graph) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.hasStatement(null, null, null, false, iri(graph.toString()));
		}
	}

	private boolean hasGraphsOf(ContextImpl context) {
		return !graphsOf(context).isEmpty();
	}

	private List<Resource> graphsOf(ContextImpl context) {
		String contextURI = context.getURI().toString();
		List<Resource> graphsOfContext = new ArrayList<>();
		try (RepositoryConnection rc = rm.getRepository().getConnection();
			 RepositoryResult<Resource> graphs = rc.getContextIDs()) {
			for (Resource graph : graphs) {
				if (graph.stringValue().equals(contextURI) || graph.stringValue().startsWith(contextURI + "/")) {
					graphsOfContext.add(graph);
				}
			}
		}
		return graphsOfContext;
	}

	private boolean hasMarker(ContextImpl context) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.hasStatement(context.getSesameURI(), RepositoryProperties.removalPending, null, false,
				((ContextManagerImpl) cm).getSesameURI());
		}
	}

	/**
	 * Returns statements without their graph and with blank nodes that are new on every read, as a SPARQL store
	 * outside quad mode does.
	 */
	private static final class SparqlLikeRepository extends RepositoryWrapper {

		private SparqlLikeRepository(Repository delegate) {
			super(delegate);
		}

		@Override
		public RepositoryConnection getConnection() {
			return new RepositoryConnectionWrapper(this, super.getConnection()) {
				@Override
				public RepositoryResult<Statement> getStatements(Resource subj, IRI pred, Value obj,
						boolean includeInferred, Resource... contexts) {
					// a blank node stays the same within one read, as in the result of a CONSTRUCT
					Map<BNode, BNode> relabelled = new HashMap<>();
					List<Statement> asReturned = new ArrayList<>();
					try (RepositoryResult<Statement> statements =
							super.getStatements(subj, pred, obj, includeInferred, contexts)) {
						for (Statement statement : statements) {
							asReturned.add(getValueFactory().createStatement(
								(Resource) relabel(statement.getSubject(), relabelled), statement.getPredicate(),
								relabel(statement.getObject(), relabelled)));
						}
					}
					return new RepositoryResult<>(new CloseableIteratorIteration<>(asReturned.iterator()));
				}
			};
		}

		private static Value relabel(Value value, Map<BNode, BNode> relabelled) {
			return value instanceof BNode blankNode ? relabelled.computeIfAbsent(blankNode, unused -> bnode()) : value;
		}
	}

	/** Removes none of the statements it is given, as a store whose removals miss what it returned. */
	private static final class RemovalIgnoringRepository extends RepositoryWrapper {

		private RemovalIgnoringRepository(Repository delegate) {
			super(delegate);
		}

		@Override
		public RepositoryConnection getConnection() {
			return new RepositoryConnectionWrapper(this, super.getConnection()) {
				@Override
				public void remove(Iterable<? extends Statement> statements, Resource... contexts) {
				}
			};
		}
	}
}
