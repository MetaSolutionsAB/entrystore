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
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.DeletedEntryInfo;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.TreeRemovalPendingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1273. A recursive delete of a list is left to {@link ListTreeRemover}, which removes the tree in the
 * background from its leaves to its root, without holding the repository monitor between chunks.
 */
public class ListTreeRemoverTest extends AbstractCoreTest {

	/** Released after each test, so a job held by a hook cannot delay the shutdown. */
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
	public void removeTree_returnsAtOnceAndRemovesTheTreeInTheBackground() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().beforeChunk = this::awaitRelease;

		((List) root.getResource()).removeTree();

		assertNotNull(context.getByEntryURI(root.getEntryURI()), "the tree is still there when the call returns");
		assertTrue(hasMarker(context, root));
		release.countDown();
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
		assertFalse(hasMarker(context, root), "the marker goes with the root");
	}

	@Test
	public void removeTree_releasesTheMonitorBetweenChunks() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		for (int i = 0; i < 3; i++) {
			link(context, root);
		}
		remover().chunkSize = 1;
		java.util.List<Boolean> monitorHeldAfterChunk = new CopyOnWriteArrayList<>();
		remover().afterChunk = () -> monitorHeldAfterChunk.add(Thread.holdsLock(rm.getRepository()));

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertEquals(4, monitorHeldAfterChunk.size(), "one chunk per entry");
		assertFalse(monitorHeldAfterChunk.contains(true), "other writers must get the monitor between two chunks");
		assertNull(context.getByEntryURI(root.getEntryURI()));
	}

	@Test
	public void removeTree_removesTheMembersOfAListBeforeTheList() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry first = link(context, folder);
		Entry second = link(context, folder);
		remover().chunkSize = 1;
		java.util.List<String> remainingAfterChunk = new CopyOnWriteArrayList<>();
		remover().afterChunk = () -> remainingAfterChunk.add(
				(exists(context, first) ? "first " : "") + (exists(context, second) ? "second " : "")
						+ (exists(context, folder) ? "folder " : "") + (exists(context, root) ? "root" : ""));

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		// the last member first, so the list loses its tail
		assertEquals(java.util.List.of("first folder root", "folder root", "root", ""), remainingAfterChunk);
	}

	@Test
	public void removeTree_onlyTakesAMemberOutOfTheTreeThatAListOutsideItHolds() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry outside = list(context, null);
		Entry shared = link(context, root);
		((List) outside.getResource()).addChild(shared.getEntryURI());

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(shared.getEntryURI()));
		assertEquals(java.util.List.of(shared.getEntryURI()), ((List) outside.getResource()).getChildren());
		assertEquals(Set.of(outside.getResourceURI()),
				((EntryImpl) context.getByEntryURI(shared.getEntryURI())).getReferringListsInSameContext());
	}

	@Test
	public void removeTree_publishesWhatItRemovedAndDeletesTheirFiles(@TempDir Path dataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataDir.toString());
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry data = context.createResource(null, GraphType.None, ResourceType.InformationResource,
				folder.getResourceURI());
		((Data) data.getResource()).setData(new ByteArrayInputStream("content".getBytes(UTF_8)));
		Entry shared = link(context, folder);
		Entry outside = list(context, null);
		((List) outside.getResource()).addChild(shared.getEntryURI());
		File file = dataDir.resolve(context.id).resolve(data.getId()).toFile();
		assertTrue(file.exists(), "precondition: the entry has a file");
		// the events fire on the removal thread
		java.util.List<URI> deleted = new CopyOnWriteArrayList<>();
		java.util.List<URI> updated = new CopyOnWriteArrayList<>();
		RepositoryListener deletions = record(RepositoryEvent.EntryDeleted, deleted);
		RepositoryListener updates = record(RepositoryEvent.EntryUpdated, updated);

		try {
			((List) root.getResource()).removeTree();
			awaitTreeRemovals();
		} finally {
			rm.unregisterListener(deletions, RepositoryEvent.EntryDeleted);
			rm.unregisterListener(updates, RepositoryEvent.EntryUpdated);
		}

		assertEquals(Set.of(root.getEntryURI(), folder.getEntryURI(), data.getEntryURI()), Set.copyOf(deleted));
		assertTrue(updated.contains(shared.getEntryURI()), "the member that only leaves the tree is published as "
				+ "changed");
		assertFalse(file.exists());
		assertNotNull(context.getByEntryURI(shared.getEntryURI()));
		assertEquals(java.util.List.of(shared.getEntryURI()), ((List) outside.getResource()).getChildren());
	}

	@Test
	public void removeTree_ofAListHoldingAMemberWhoseEntryIsGone_removesTheTree() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		Entry gone = link(context, root);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.clear(((EntryImpl) gone).getSesameEntryURI());
		}
		evictFromSoftCache(gone);
		// a run that fails gives up at once, so that a failure leaves the tree in place instead of timing out the wait
		remover().retryDelays = java.util.List.of();

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
		assertFalse(hasMarker(context, root));
	}

	@Test
	public void removeTree_removesAMemberAddedWhileItRuns() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		AtomicBoolean added = new AtomicBoolean();
		AtomicInteger chunks = new AtomicInteger();
		java.util.List<Entry> lateMembers = new CopyOnWriteArrayList<>();
		remover().beforeChunk = () -> {
			if (added.compareAndSet(false, true)) {
				lateMembers.add(link(context, root));
			}
		};
		remover().afterChunk = chunks::incrementAndGet;

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertEquals(1, lateMembers.size());
		assertNull(context.getByEntryURI(lateMembers.getFirst().getEntryURI()), "the member added meanwhile is "
				+ "removed too");
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertEquals(1, chunks.get(), "the chunk that found the new member removed nothing");
	}

	@Test
	public void removeTree_ofListsHoldingEachOther_ends() throws Exception {
		ContextImpl context = newContext();
		Entry first = list(context, null);
		Entry second = list(context, first);
		plantMember(second, first);

		((List) first.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(first.getEntryURI()));
		assertNull(context.getByEntryURI(second.getEntryURI()));
	}

	@Test
	public void removeTree_ofListsHoldingEachOther_endsAcrossChunks() throws Exception {
		ContextImpl context = newContext();
		Entry first = list(context, null);
		Entry second = list(context, first);
		plantMember(second, first);
		// the second list goes in a chunk before the first, which it still holds
		remover().chunkSize = 1;

		((List) first.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(first.getEntryURI()));
		assertNull(context.getByEntryURI(second.getEntryURI()));
	}

	@Test
	public void removeTree_keepsAMemberLinkedIntoAListOutsideTheTreeMeanwhile() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		Entry outside = list(context, null);
		AtomicBoolean linked = new AtomicBoolean();
		remover().beforeChunk = () -> {
			if (linked.compareAndSet(false, true)) {
				((List) outside.getResource()).addChild(member.getEntryURI());
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(member.getEntryURI()), "an entry filed elsewhere meanwhile stays");
		assertEquals(java.util.List.of(member.getEntryURI()), ((List) outside.getResource()).getChildren());
	}

	@Test
	public void removeTree_keepsASubFolderMovedOutOfTheTreeMeanwhile() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry member = link(context, folder);
		Entry outside = list(context, null);
		// the member is checked first, alone, while the folder that held it in the tree has left it
		remover().chunkSize = 1;
		AtomicBoolean moved = new AtomicBoolean();
		remover().beforeChunk = () -> {
			if (moved.compareAndSet(false, true)) {
				moveWithinContext(outside, folder, root);
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(folder.getEntryURI()));
		assertNotNull(context.getByEntryURI(member.getEntryURI()));
		assertEquals(java.util.List.of(folder.getEntryURI()), ((List) outside.getResource()).getChildren());
	}

	@Test
	public void removeTree_retriesATreeThatKeepsChangingUntilItStops() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		// the first two runs each find a new member in the root in three passes in a row, which remove nothing, so
		// they use up the retries of failed runs
		remover().retryDelays = java.util.List.of(Duration.ZERO, Duration.ZERO);
		AtomicInteger chunksRead = new AtomicInteger();
		remover().beforeChunk = () -> {
			if (chunksRead.incrementAndGet() <= 6) {
				link(context, root);
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()), "a changing tree is retried until it is gone");
		assertFalse(hasMarker(context, root));
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_keepsRetryingATreeAWriterKeepsAddingTo() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		link(context, root);
		remover().changingRetryDelay = Duration.ZERO;
		remover().chunkSize = 1;
		AtomicInteger chunksRead = new AtomicInteger();
		// each pass removes the members it walked, then finds new ones in the root, for longer than one run's passes
		remover().beforeChunk = () -> {
			if (chunksRead.incrementAndGet() <= 80) {
				link(context, root);
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertTrue(chunksRead.get() > 80, "precondition: the writer outlasted the first run");
		assertNull(context.getByEntryURI(root.getEntryURI()), "the writer cannot make the removal give up");
		assertFalse(hasMarker(context, root));
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_givesUpOnRunsThatRemoveNothing() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		remover().retryDelays = java.util.List.of(Duration.ZERO);
		// a tree of one chunk that changes before every check: no run removes anything
		remover().beforeChunk = () -> link(context, root);

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNotNull(context.getByEntryURI(root.getEntryURI()), "a run that removes nothing is not retried forever");
		assertFalse(hasMarker(context, root));
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_ofATreeWithAMemberLackingItsListLink_removesIt() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry linked = link(context, root);
		// held by the root without its relations graph saying so, as an entry written without a relations graph is
		Entry planted = context.createLink(null, URI.create("https://slashdot.org/"), null);
		plantMember(root, planted);

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(linked.getEntryURI()));
		assertNull(context.getByEntryURI(planted.getEntryURI()));
		assertFalse(hasMarker(context, root));
	}

	@Test
	public void removeTree_walksAListClaimedByAMoveAgainOnceReleased() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry member = link(context, folder);
		IRI claimed = remover().claim((ListImpl) folder.getResource());
		AtomicBoolean released = new AtomicBoolean();
		// the walk left the folder to the move, whose copy fails before the root's chunk is checked
		remover().beforeChunk = () -> {
			if (released.compareAndSet(false, true)) {
				remover().release(claimed);
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(folder.getEntryURI()), "the folder is walked again, not left orphaned");
		assertNull(context.getByEntryURI(member.getEntryURI()));
		assertFalse(hasMarker(context, folder));
	}

	@Test
	public void resumePending_dropsTheRemovalOfAUserWhoNoLongerAdministersTheContext() throws Exception {
		rm.setCheckForAuthorization(true);
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		ContextImpl mouse = (ContextImpl) cm.getContext("mouse");
		Entry root = list(mouse, null);
		Entry member = link(mouse, root);
		// Donald may write to mouse, through friendsOfMickey, but only Mickey administers it
		URI donald = pm.getPrincipalEntry("Donald").getResourceURI();
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(iri(root.getEntryURI().toString()), RepositoryProperties.treeRemovalPending, iri(donald.toString()),
					mouse.resourceURI);
		}

		remover().resumePending();
		awaitTreeRemovals();

		assertNotNull(mouse.getByEntryURI(root.getEntryURI()));
		assertNotNull(mouse.getByEntryURI(member.getEntryURI()));
		assertFalse(hasMarker(mouse, root), "the removal is dropped, not resumed on every start");
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_givesUpAfterItsRetriesFail() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().retryDelays = java.util.List.of(Duration.ZERO);
		AtomicInteger runs = new AtomicInteger();
		remover().beforeChunk = () -> {
			runs.incrementAndGet();
			throw new IllegalStateException("the store failed");
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertEquals(2, runs.get(), "the run and its one retry");
		assertNotNull(context.getByEntryURI(member.getEntryURI()), "the rest of the tree is left in place");
		assertFalse(hasMarker(context, root), "no later start removes it unasked");
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())), "a later delete of it starts a job again");
	}

	@Test
	public void removeTree_takesUpAMarkerWrittenAsTheRunEnds() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		link(context, root);
		AtomicBoolean askedAgain = new AtomicBoolean();
		// a recursive delete asked for after the last chunk, before the run ends, finds the tree still pending
		remover().afterChunk = () -> {
			if (askedAgain.compareAndSet(false, true)) {
				((List) root.getResource()).removeTree();
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertTrue(askedAgain.get());
		assertFalse(hasMarker(context, root), "a run takes up the marker of that delete");
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_leavesTheMarkerOfASubListWithARemovalOfItsOwn() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry sub = list(context, root);
		link(context, sub);
		IRI subEntry = iri(sub.getEntryURI().toString());
		URI donald = pm.getPrincipalEntry("Donald").getResourceURI();
		remover().retryDelays = java.util.List.of(Duration.ofMinutes(10));
		AtomicBoolean failed = new AtomicBoolean();
		remover().beforeChunk = () -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("the store failed");
			}
		};
		pm.setAuthenticatedUserURI(donald);
		((List) sub.getResource()).removeTree();
		awaitRetryOf(subEntry);
		CountDownLatch rootRemoved = new CountDownLatch(1);
		remover().afterChunk = () -> {
			if (!exists(context, root)) {
				rootRemoved.countDown();
			}
		};

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		((List) root.getResource()).removeTree();

		assertTrue(rootRemoved.await(30, TimeUnit.SECONDS));
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertEquals(java.util.List.of(iri(donald.toString())), rc.getStatements(subEntry,
					RepositoryProperties.treeRemovalPending, null, false, context.resourceURI).stream()
					.map(statement -> statement.getObject()).toList(),
					"the sub-list keeps the marker of its own removal");
		}
	}

	@Test
	public void remove_ofAListInATreeBeingRemoved_isRefused() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry member = link(context, folder);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		// removing the folder alone would leave out its members the removal has not reached yet
		assertThrows(TreeRemovalPendingException.class, () -> context.remove(folder.getEntryURI()));

		release.countDown();
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(folder.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void remove_ofAListOutsideATreeBeingRemoved_isAllowed() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		link(context, root);
		Entry outside = list(context, null);
		Entry removed = list(context, outside);
		Entry moved = list(context, outside);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		// pending through a claim, as a move to another context holds it, so that no job runs on the hooks
		IRI claimed = remover().claim((ListImpl) root.getResource());

		// a tree pending in the context refuses only the lists of its own tree
		context.remove(removed.getEntryURI());
		Entry copy = ((List) targetList.getResource()).moveEntryHere(moved.getEntryURI(), outside.getEntryURI(), true);

		assertNull(context.getByEntryURI(removed.getEntryURI()));
		assertNull(context.getByEntryURI(moved.getEntryURI()));
		assertEquals(java.util.List.of(copy.getEntryURI()), ((List) targetList.getResource()).getChildren());
		remover().release(claimed);
	}

	@Test
	public void moveEntryHere_intoAListOfATreeBeingRemoved_isRefused() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry outside = list(context, null);
		Entry linked = link(context, outside);
		ContextImpl other = newContext();
		Entry otherList = list(other, null);
		Entry otherFolder = list(other, otherList);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		// the removal would delete what the move put there, and a move to another context its source too
		assertThrows(TreeRemovalPendingException.class, () -> ((List) folder.getResource())
				.moveEntryHere(linked.getEntryURI(), outside.getEntryURI(), false));
		assertThrows(TreeRemovalPendingException.class, () -> ((List) folder.getResource())
				.moveEntryHere(otherFolder.getEntryURI(), otherList.getEntryURI(), true));

		assertEquals(java.util.List.of(linked.getEntryURI()), ((List) outside.getResource()).getChildren());
		assertEquals(java.util.List.of(otherFolder.getEntryURI()), ((List) otherList.getResource()).getChildren());
		release.countDown();
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(linked.getEntryURI()));
		assertNotNull(other.getByEntryURI(otherFolder.getEntryURI()));
	}

	@Test
	public void removeTree_retriesAFailedRun() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().retryDelays = java.util.List.of(Duration.ZERO);
		AtomicBoolean failed = new AtomicBoolean();
		remover().beforeChunk = () -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("the store failed");
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertTrue(failed.get());
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
		assertFalse(hasMarker(context, root));
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void removeTree_keepsTheListPendingWhileItRetries() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().retryDelays = java.util.List.of(Duration.ZERO);
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch retrying = new CountDownLatch(1);
		remover().beforeChunk = () -> {
			if (runs.incrementAndGet() == 1) {
				throw new IllegalStateException("the store failed");
			}
			retrying.countDown();
			awaitRelease();
		};

		((List) root.getResource()).removeTree();

		assertTrue(retrying.await(30, TimeUnit.SECONDS));
		assertTrue(remover().isPending(iri(root.getEntryURI().toString())), "a move of it is still refused");
		assertThrows(TreeRemovalPendingException.class, () -> context.remove(root.getEntryURI()));
		release.countDown();
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void remove_ofAListWhoseTreeIsBeingRemoved_isRefused() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		// removing the list alone would leave the members the removal has not reached yet
		assertThrows(TreeRemovalPendingException.class, () -> context.remove(root.getEntryURI()));

		release.countDown();
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void removeTree_leavesAListClaimedByAMoveToTheMove() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry member = link(context, folder);
		// as a move of the folder to another context does while it copies it
		IRI claimed = remover().claim((ListImpl) folder.getResource());

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(folder.getEntryURI()), "the folder is left to the move");
		assertNotNull(context.getByEntryURI(member.getEntryURI()));
		assertTrue(hasMarker(context, folder), "a move that fails leaves the folder to the background");
		remover().release(claimed);
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(folder.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void removeTree_leavesAListClaimedAfterItsWalkToTheMove() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry folder = list(context, root);
		Entry member = link(context, folder);
		AtomicReference<IRI> claimed = new AtomicReference<>();
		// the walk took the folder in; the chunk finds it claimed
		remover().beforeChunk = () -> {
			if (claimed.get() == null) {
				claimed.set(remover().claim((ListImpl) folder.getResource()));
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(folder.getEntryURI()), "the folder is left to the move");
		assertNotNull(context.getByEntryURI(member.getEntryURI()));
		remover().release(claimed.get());
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(folder.getEntryURI()));
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void removeTree_leavesTheListALinkInTheTreePointsAt() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry other = list(context, null);
		Entry otherMember = link(context, other);
		Entry pointer = context.createLink(null, other.getResourceURI(), root.getResourceURI());
		pointer.setGraphType(GraphType.List);

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		assertNull(context.getByEntryURI(pointer.getEntryURI()));
		assertNotNull(context.getByEntryURI(other.getEntryURI()), "a link is no list of the tree, whatever it names");
		assertNotNull(context.getByEntryURI(otherMember.getEntryURI()));
		assertEquals(java.util.List.of(otherMember.getEntryURI()), ((List) other.getResource()).getChildren());
	}

	@Test
	public void removeTree_keepsGoingWhileEachPassRemovesSomething() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		java.util.List<Entry> links = new CopyOnWriteArrayList<>();
		for (int i = 0; i < 3; i++) {
			links.add(link(context, root));
		}
		remover().chunkSize = 1;
		AtomicInteger chunksRead = new AtomicInteger();
		// the root's chunk finds a new member in three passes, each of which removed other chunks first
		remover().beforeChunk = () -> {
			if (chunksRead.incrementAndGet() <= 10) {
				links.add(link(context, root));
			}
		};

		((List) root.getResource()).removeTree();
		awaitTreeRemovals();

		assertNull(context.getByEntryURI(root.getEntryURI()));
		for (Entry link : links) {
			assertNull(context.getByEntryURI(link.getEntryURI()));
		}
		assertFalse(hasMarker(context, root));
	}

	@Test
	public void removeTree_endsWhenItsContextIsRemoved() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		link(context, root);
		AtomicInteger chunks = new AtomicInteger();
		remover().afterChunk = chunks::incrementAndGet;
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		cm.remove(context.getEntry().getEntryURI());
		release.countDown();
		awaitTreeRemovals();
		awaitContextRemovals();

		assertEquals(0, chunks.get(), "the chunk finds the context removed and leaves it to the context removal");
		assertFalse(remover().isPending(iri(root.getEntryURI().toString())));
	}

	@Test
	public void claim_holdsBackARecursiveDeleteUntilItIsReleased() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		ListImpl list = (ListImpl) root.getResource();
		// as a move to another context does while it copies the list
		IRI claimed = remover().claim(list);

		list.removeTree();
		awaitTreeRemovals();

		assertNotNull(context.getByEntryURI(member.getEntryURI()), "no removal runs while the tree is claimed");
		assertTrue(hasMarker(context, root));
		remover().release(claimed);
		awaitTreeRemovals();
		assertNull(context.getByEntryURI(root.getEntryURI()), "the delete asked for meanwhile runs once released");
		assertNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void removeTree_inAContextKeepingEntryNames_removesTheTreeBeforeItReturns() {
		Entry root = pm.createResource(null, GraphType.List, null, null);
		Entry child = pm.createResource(null, GraphType.List, null, root.getResourceURI());

		assertFalse(((List) root.getResource()).removeTree(), "nothing is left for the background");
		assertNull(pm.getByEntryURI(root.getEntryURI()));
		assertNull(pm.getByEntryURI(child.getEntryURI()));
	}

	@Test
	public void resumePending_dropsTheMarkerOfAListAlreadyRemoved() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		context.remove(root.getEntryURI());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(iri(root.getEntryURI().toString()), RepositoryProperties.treeRemovalPending,
					iri(pm.getAdminUser().getURI().toString()), context.resourceURI);
		}

		remover().resumePending();
		awaitTreeRemovals();

		assertFalse(hasMarker(context, root));
	}

	@Test
	public void resumePending_ignoresAMarkerOutsideItsContextsIndexGraph() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry described = context.createLink(null, URI.create("https://slashdot.org/"), null);
		IRI metadataGraph = iri(described.getLocalMetadataURI().toString());
		IRI listEntry = iri(root.getEntryURI().toString());
		// a metadata graph holds what its writer puts there, also a subject java.net.URI rejects
		IRI unparsable = iri(root.getEntryURI() + " b");
		IRI admin = iri(pm.getAdminUser().getURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(unparsable, RepositoryProperties.treeRemovalPending, admin, metadataGraph);
			rc.add(listEntry, RepositoryProperties.treeRemovalPending, admin, metadataGraph);
		}

		remover().resumePending();
		awaitTreeRemovals();

		assertFalse(remover().isPending(listEntry));
		assertNotNull(context.getByEntryURI(root.getEntryURI()));
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertTrue(rc.hasStatement(unparsable, RepositoryProperties.treeRemovalPending, admin, false,
					metadataGraph));
			assertTrue(rc.hasStatement(listEntry, RepositoryProperties.treeRemovalPending, admin, false,
					metadataGraph));
		}
	}

	@Test
	public void removeTree_stopsWhenItsMarkerIsGone() throws Exception {
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry member = link(context, root);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		// as an import into the context does
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove(iri(root.getEntryURI().toString()), RepositoryProperties.treeRemovalPending, null,
					context.resourceURI);
		}
		release.countDown();
		awaitTreeRemovals();

		assertNotNull(context.getByEntryURI(root.getEntryURI()));
		assertNotNull(context.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void removeTree_recordsTheRequesterAsWhoDeletedTheEntries() throws Exception {
		boolean tracked = RepositoryManagerImpl.trackDeletedEntries;
		RepositoryManagerImpl.trackDeletedEntries = true;
		try {
			rm.setCheckForAuthorization(true);
			URI donald = pm.getPrincipalEntry("Donald").getResourceURI();
			pm.setAuthenticatedUserURI(donald);
			Context duck = cm.getContext("duck");
			Entry root = duck.createResource(null, GraphType.List, null, null); // since owner
			Entry member = duck.createLink(null, URI.create("https://slashdot.org/"), root.getResourceURI());

			((List) root.getResource()).removeTree();
			awaitTreeRemovals();

			Map<URI, DeletedEntryInfo> deleted = duck.getDeletedEntries();
			assertEquals(donald, deleted.get(member.getEntryURI()).getDeletedBy());
			assertEquals(donald, deleted.get(root.getEntryURI()).getDeletedBy());
		} finally {
			RepositoryManagerImpl.trackDeletedEntries = tracked;
		}
	}

	@Test
	public void aShutdownStopsTheRemovalBetweenChunksAndTheNextStartFinishesIt(@TempDir Path store) throws Exception {
		Map<String, String> persistentStore = Map.of(Settings.STORE_PATH, store.toUri().toString());
		rm.shutdown();
		setUpEnvironment(persistentStore);
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		Entry root = list(context, null);
		Entry first = link(context, root);
		Entry second = link(context, root);
		ListTreeRemover remover = remover();
		remover.chunkSize = 1;
		AtomicInteger chunks = new AtomicInteger();
		remover.afterChunk = chunks::incrementAndGet;
		CountDownLatch jobHeld = new CountDownLatch(1);
		remover.beforeChunk = () -> {
			jobHeld.countDown();
			awaitRelease();
		};
		((List) root.getResource()).removeTree();
		jobHeld.await();

		Thread shutdown = Thread.ofPlatform().start(rm::shutdown);
		while (!remover.stopping) {
			Thread.onSpinWait();
		}
		release.countDown();
		shutdown.join();

		assertEquals(1, chunks.get(), "the job stops after the chunk it was about to read");
		setUpEnvironment(persistentStore);
		awaitTreeRemovals();
		ContextImpl restarted = (ContextImpl) cm.getContext(context.id);
		assertNull(restarted.getByEntryURI(first.getEntryURI()));
		assertNull(restarted.getByEntryURI(second.getEntryURI()));
		assertNull(restarted.getByEntryURI(root.getEntryURI()));
		assertFalse(hasMarker(restarted, root));
	}

	@Test
	public void moveEntryHere_ofAFolderToAnotherContext_removesTheSourceBeforeItReturns() throws Exception {
		ContextImpl source = newContext();
		Entry sourceList = list(source, null);
		Entry folder = list(source, sourceList);
		Entry member = link(source, folder);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);

		Entry moved = ((List) targetList.getResource()).moveEntryHere(folder.getEntryURI(),
				sourceList.getEntryURI(), true);

		assertNull(source.getByEntryURI(folder.getEntryURI()));
		assertNull(source.getByEntryURI(member.getEntryURI()));
		assertEquals(java.util.List.of(), ((List) sourceList.getResource()).getChildren());
		assertEquals(java.util.List.of(moved.getEntryURI()), ((List) targetList.getResource()).getChildren());
		assertFalse(hasMarker(source, folder));
	}

	@Test
	public void moveEntryHere_whoseRemovalFails_failsAndLeavesTheSource() throws Exception {
		ContextImpl source = newContext();
		Entry sourceList = list(source, null);
		Entry folder = list(source, sourceList);
		Entry member = link(source, folder);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		AtomicBoolean failed = new AtomicBoolean();
		remover().beforeChunk = () -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("the store failed");
			}
		};

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), sourceList.getEntryURI(), true));
		awaitTreeRemovals();

		// reported as failed, so nothing removes the source later behind its caller's back
		assertNotNull(source.getByEntryURI(folder.getEntryURI()));
		assertNotNull(source.getByEntryURI(member.getEntryURI()));
		assertEquals(1, ((List) targetList.getResource()).getChildren().size(), "the copy is left in place");
		assertFalse(hasMarker(source, folder));
		assertFalse(remover().isPending(iri(folder.getEntryURI().toString())), "a later move or delete is not refused");
	}

	@Test
	public void moveEntryHere_ofAListOutOfPrincipals_removesTheSource() throws Exception {
		Entry pmRoot = pm.createResource(null, GraphType.List, null, null);
		Entry pmFolder = pm.createResource(null, GraphType.List, null, pmRoot.getResourceURI());
		Entry pmChild = pm.createResource(null, GraphType.List, null, pmFolder.getResourceURI());
		ContextImpl target = newContext();
		Entry targetList = list(target, null);

		Entry moved = ((List) targetList.getResource()).moveEntryHere(pmFolder.getEntryURI(), pmRoot.getEntryURI(),
				true);

		// a context keeping entry names removes the source one entry at a time, which its removals must not refuse
		assertEquals(java.util.List.of(moved.getEntryURI()), ((List) targetList.getResource()).getChildren());
		assertNull(pm.getByEntryURI(pmFolder.getEntryURI()));
		assertNull(pm.getByEntryURI(pmChild.getEntryURI()));
		assertFalse(remover().isPending(iri(pmFolder.getEntryURI().toString())));
	}

	@Test
	public void moveEntryHere_whoseRemovalIsCancelled_fails() throws Exception {
		ContextImpl source = newContext();
		Entry sourceList = list(source, null);
		Entry folder = list(source, sourceList);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		// as an import into the source context does
		remover().beforeChunk = () -> {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				rc.remove(iri(folder.getEntryURI().toString()), RepositoryProperties.treeRemovalPending, null,
						source.resourceURI);
			}
		};

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), sourceList.getEntryURI(), true));

		assertNotNull(source.getByEntryURI(folder.getEntryURI()), "the move reports the source it did not remove");
		assertFalse(remover().isPending(iri(folder.getEntryURI().toString())));
	}

	@Test
	public void moveEntryHere_ofAFolderHoldingAFolderBeingRemoved_isRefusedBeforeCopying() throws Exception {
		ContextImpl source = newContext();
		Entry sourceList = list(source, null);
		Entry folder = list(source, sourceList);
		Entry sub = list(source, folder);
		Entry member = link(source, sub);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		remover().beforeChunk = this::awaitRelease;
		((List) sub.getResource()).removeTree();

		// a copy would bring back the folder deleted inside it
		assertThrows(TreeRemovalPendingException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), sourceList.getEntryURI(), true));

		assertEquals(java.util.List.of(), ((List) targetList.getResource()).getChildren(), "the copies are removed");
		assertFalse(remover().isPending(iri(folder.getEntryURI().toString())), "the failed copy gives up its claim");
		assertEquals(java.util.List.of(folder.getEntryURI()), ((List) sourceList.getResource()).getChildren());
		release.countDown();
		awaitTreeRemovals();
		assertNull(source.getByEntryURI(sub.getEntryURI()));
		assertNull(source.getByEntryURI(member.getEntryURI()));
		assertNotNull(source.getByEntryURI(folder.getEntryURI()));
	}

	@Test
	public void moveEntryHere_ofAFolderTwoLevelsDownATreeBeingRemoved_isRefusedBeforeCopying() throws Exception {
		ContextImpl source = newContext();
		Entry root = list(source, null);
		Entry folder = list(source, root);
		Entry sub = list(source, folder);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		assertThrows(TreeRemovalPendingException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(sub.getEntryURI(), folder.getEntryURI(), true));

		assertEquals(java.util.List.of(), ((List) targetList.getResource()).getChildren(), "nothing was copied");
		release.countDown();
		awaitTreeRemovals();
		assertNull(source.getByEntryURI(sub.getEntryURI()));
	}

	@Test
	public void moveEntryHere_ofAFolderInListsHoldingEachOther_ends() throws Exception {
		ContextImpl source = newContext();
		Entry first = list(source, null);
		Entry second = list(source, first);
		// past the single parent check, so that the relations graphs record the loop too
		((ListImpl) second.getResource()).addChild(first.getEntryURI(), false, true);
		Entry folder = list(source, second);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		// an unrelated claim, so that the check for a pending tree holding the folder walks its holders
		IRI unrelated = remover().claim((ListImpl) list(newContext(), null).getResource());

		Entry moved = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), second.getEntryURI(), true));

		remover().release(unrelated);
		assertNull(source.getByEntryURI(folder.getEntryURI()));
		assertEquals(java.util.List.of(moved.getEntryURI()), ((List) targetList.getResource()).getChildren());
	}

	@Test
	public void moveEntryHere_ofASubFolderOfATreeBeingRemoved_isRefusedBeforeCopying() throws Exception {
		ContextImpl source = newContext();
		Entry root = list(source, null);
		Entry folder = list(source, root);
		Entry member = link(source, folder);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		remover().beforeChunk = this::awaitRelease;
		((List) root.getResource()).removeTree();

		assertThrows(TreeRemovalPendingException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), root.getEntryURI(), true));

		assertEquals(java.util.List.of(), ((List) targetList.getResource()).getChildren(), "nothing was copied");
		release.countDown();
		awaitTreeRemovals();
		assertNull(source.getByEntryURI(root.getEntryURI()));
		assertNull(source.getByEntryURI(folder.getEntryURI()));
		assertNull(source.getByEntryURI(member.getEntryURI()));
	}

	@Test
	public void moveEntryHere_ofAFolderWhoseTreeIsBeingRemoved_isRefusedBeforeCopying() throws Exception {
		ContextImpl source = newContext();
		Entry sourceList = list(source, null);
		Entry folder = list(source, sourceList);
		Entry member = link(source, folder);
		ContextImpl target = newContext();
		Entry targetList = list(target, null);
		remover().beforeChunk = this::awaitRelease;
		((List) folder.getResource()).removeTree();

		assertThrows(TreeRemovalPendingException.class, () -> ((List) targetList.getResource())
				.moveEntryHere(folder.getEntryURI(), sourceList.getEntryURI(), true));

		assertEquals(java.util.List.of(), ((List) targetList.getResource()).getChildren(), "nothing was copied");
		release.countDown();
		awaitTreeRemovals();
		assertNull(source.getByEntryURI(folder.getEntryURI()));
		assertNull(source.getByEntryURI(member.getEntryURI()));
		assertEquals(java.util.List.of(), ((List) sourceList.getResource()).getChildren());
	}

	private static Entry list(Context context, Entry parent) {
		return context.createResource(null, GraphType.List, null, parent != null ? parent.getResourceURI() : null);
	}

	private static Entry link(Context context, Entry parent) {
		return context.createLink(null, URI.create("https://slashdot.org/"), parent.getResourceURI());
	}

	private static boolean exists(ContextImpl context, Entry entry) {
		return context.getByEntryURI(entry.getEntryURI()) != null;
	}

	/** Moves {@code entry} from {@code from} to {@code to}, lists of one context, as a hook that cannot throw. */
	private static void moveWithinContext(Entry to, Entry entry, Entry from) {
		try {
			((List) to.getResource()).moveEntryHere(entry.getEntryURI(), from.getEntryURI(), false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Appends {@code member} to the stored sequence of the list directly, past the membership checks. */
	private void plantMember(Entry listEntry, Entry member) {
		IRI listResource = iri(listEntry.getResourceURI().toString());
		int position = ((List) listEntry.getResource()).getChildren().size() + 1;
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(listResource, iri(RDF.NAMESPACE + "_" + position), iri(member.getEntryURI().toString()),
					listResource);
		}
		((ListImpl) listEntry.getResource()).invalidateChildren();
	}

	private ListTreeRemover remover() {
		return ((ContextManagerImpl) cm).listTreeRemover;
	}

	/** Records the entry URI of each event of the kind into {@code sources} until the returned listener is removed. */
	private RepositoryListener record(RepositoryEvent event, java.util.List<URI> sources) {
		RepositoryListener recorder = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				sources.add(((Entry) eventObject.getSource()).getEntryURI());
			}
		};
		rm.registerListener(recorder, event);
		return recorder;
	}

	/** Waits until a run of the removal of the tree has failed and its retry is scheduled. */
	private void awaitRetryOf(IRI root) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!remover().isWaitingForRetry(root)) {
			assertTrue(System.nanoTime() < deadline, "the run did not fail");
			Thread.sleep(10);
		}
	}

	private void awaitRelease() {
		try {
			release.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean hasMarker(ContextImpl context, Entry root) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.hasStatement(iri(root.getEntryURI().toString()), RepositoryProperties.treeRemovalPending,
					null, false, context.resourceURI);
		}
	}
}
