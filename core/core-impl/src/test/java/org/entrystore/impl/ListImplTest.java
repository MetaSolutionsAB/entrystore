/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import org.entrystore.List;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class ListImplTest extends AbstractCoreTest {

	@Test
	public void singleOccurrenceOfChild() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("https://slashdot.org/"), null);

		// Ok the first time.
		((List) list.getResource()).addChild(child.getEntryURI());

		// Should fail second time.
		assertThrows(RepositoryException.class, () -> ((List) list.getResource()).addChild(child.getEntryURI()));
	}

	@Test
	public void addChildPersistsToRepositoryAndCache() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("https://slashdot.org/"), null);

		((List) list.getResource()).addChild(child.getEntryURI());

		// The list instance that performed the add reflects it in its in-memory cache.
		assertTrue(((List) list.getResource()).getChildren().contains(child.getEntryURI()));

		// Evict the entry so getByEntryURI rebuilds a fresh ListImpl that loads its children straight from the
		// committed RDF, proving the add was actually persisted (not just cached) and that the two agree.
		((ContextImpl) duck).softCache.remove(list);
		List reloaded = (List) duck.getByEntryURI(list.getEntryURI()).getResource();
		assertEquals(1, reloaded.getChildren().size());
		assertTrue(reloaded.getChildren().contains(child.getEntryURI()));
	}

	@Test
	public void singleOccurrenceOfChild2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry list = duck.createResource(null, GraphType.List, null, null);
		Entry child = duck.createLink(null, URI.create("https://slashdot.org/"), null);
		java.util.List<URI> children = new ArrayList<>();
		children.add(child.getEntryURI());
		children.add(child.getEntryURI());

		assertThrows(RepositoryException.class, () -> ((List) list.getResource()).setChildren(children));
	}

	@Test
	public void singleParentOfList() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);

		// Adding a list to a parent list should be ok.
		((List) parentList1.getResource()).addChild(childList.getEntryURI());

		// Adding a list to a second parent list should fail.
		assertThrows(RepositoryException.class,
			() -> ((List) parentList2.getResource()).addChild(childList.getEntryURI()));

	}

	@Test
	public void singleParentOfLis2() {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry parentList1 = duck.createResource(null, GraphType.List, null, null);
		Entry parentList2 = duck.createResource(null, GraphType.List, null, null);
		Entry childList = duck.createResource(null, GraphType.List, null, null);
		((List) parentList1.getResource()).addChild(childList.getEntryURI()); // Adding a list to a parent list should be ok.
		java.util.List<URI> children = new ArrayList<>();
		children.add(childList.getEntryURI());

		assertThrows(RepositoryException.class, () -> ((List) parentList2.getResource()).setChildren(children));

	}

	@Test
	public void moveEntryBetweenLists() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());

		((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), false);
		assertEquals(0, ((List) listE1.getResource()).getChildren().size());
		assertEquals(1, ((List) listE2.getResource()).getChildren().size());
	}

	@Test
	public void moveEntryHereRemovingFromAllListsInSameContext() throws QuotaException, IOException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry sourceList1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry sourceList2 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), sourceList1.getResourceURI());
		((List) sourceList2.getResource()).addChild(linkEntry.getEntryURI());

		// The same-context remove-from-all-lists branch is only reached when fromList does not resolve to an
		// existing entry (a literal null URI is rejected earlier), so pass a well-formed but non-existent list URI.
		URI unresolvedFromList = URI.create(rm.getRepositoryURL() + "duck/entry/_does_not_exist_");
		((List) targetList.getResource()).moveEntryHere(linkEntry.getEntryURI(), unresolvedFromList, true);

		// The branch resolves each source list by its resource URI, which replaces the soft-cache instances,
		// so the references above are stale; re-fetch by entry URI to read the committed repository state.
		assertEquals(0, ((List) duck.getByEntryURI(sourceList1.getEntryURI()).getResource()).getChildren().size());
		assertEquals(0, ((List) duck.getByEntryURI(sourceList2.getEntryURI()).getResource()).getChildren().size());
		List targetAfterMove = (List) duck.getByEntryURI(targetList.getEntryURI()).getResource();
		assertEquals(1, targetAfterMove.getChildren().size());
		assertTrue(targetAfterMove.getChildren().contains(linkEntry.getEntryURI()));
	}

	@Test
	public void moveEntryBetweenListsInDifferentContexts() throws IOException, QuotaException {
		// Use the Donald user.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());

		Entry listE2 = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry newEntry = ((List) listE2.getResource()).moveEntryHere(linkEntry.getEntryURI(), listE1.getEntryURI(), true);
		assertNull(duck.getByEntryURI(linkEntry.getEntryURI()));
		assertEquals(newEntry.getContext(), mouse);
		assertEquals(1, ((List) listE2.getResource()).getChildren().size());
	}

	/**
	 * Creates an entry of the given type in the given list. The four create methods differ in arity, so
	 * the cross-context copy and move tests share this one dispatch rather than repeating it.
	 */
	private Entry createOfType(Context context, EntryType entryType, URI listURI) {
		return switch (entryType) {
			case Local -> context.createResource(null, GraphType.None, ResourceType.NamedResource, listURI);
			case Link -> context.createLink(null, URI.create("https://slashdot.org/"), listURI);
			case Reference -> context.createReference(null, URI.create("https://reddit.com/"),
				URI.create("https://example.com/md1"), listURI);
			case LinkReference -> context.createLinkReference(null, URI.create("https://digg.com/"),
				URI.create("https://example.com/md2"), listURI);
		};
	}

	/**
	 * Records why EntryType.Reference is left out of the cross-context tests that follow: moving or
	 * copying a Reference entry between contexts throws NullPointerException, because copyGraphs
	 * dereferences getLocalMetadata() and initMetadataObjects only ever creates local metadata for
	 * Local, Link and LinkReference. This predates the create-method consolidation and is not fixed
	 * by it, so the test is kept disabled rather than deleted; enable it with the fix.
	 */
	@Disabled("copyGraphs NPEs on a Reference entry, which has no local metadata - pre-existing, needs its own issue")
	@Test
	public void moveReferenceBetweenContexts_currentlyFailsOnMissingLocalMetadata() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry sourceList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry original = createOfType(duck, EntryType.Reference, sourceList.getResourceURI());

		Entry moved = ((List) targetList.getResource())
			.moveEntryHere(original.getEntryURI(), sourceList.getEntryURI(), true);

		assertEquals(EntryType.Reference, moved.getEntryType());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = EntryType.class, names = {"Local", "Link", "LinkReference"})
	public void moveEntryBetweenContexts_preservesEntryType(EntryType entryType) throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry sourceList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry original = createOfType(duck, entryType, sourceList.getResourceURI());

		Entry moved = ((List) targetList.getResource())
			.moveEntryHere(original.getEntryURI(), sourceList.getEntryURI(), true);

		assertEquals(entryType, moved.getEntryType());
		assertEquals(mouse, moved.getContext());
		assertNull(duck.getByEntryURI(original.getEntryURI()));
		assertTrue(((List) targetList.getResource()).getChildren().contains(moved.getEntryURI()));
	}

	@Test
	public void moveLinkBetweenContexts_keepsTheExternalResourceURI() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry sourceList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry original = createOfType(duck, EntryType.Link, sourceList.getResourceURI());
		URI resourceURI = original.getResourceURI();

		Entry moved = ((List) targetList.getResource())
			.moveEntryHere(original.getEntryURI(), sourceList.getEntryURI(), true);

		// a link points at a resource outside the context, so the move must not rewrite it;
		// a Local entry's resource URI is context-local and is regenerated instead
		assertEquals(resourceURI, moved.getResourceURI());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = EntryType.class, names = {"Local", "Link", "LinkReference"})
	public void copyEntryBetweenContexts_preservesEntryTypeAndLeavesTheOriginal(EntryType entryType) {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry sourceList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry original = createOfType(duck, entryType, sourceList.getResourceURI());

		Entry copy = ((ListImpl) targetList.getResource()).copyEntryHere((EntryImpl) original);

		assertEquals(entryType, copy.getEntryType());
		assertEquals(mouse, copy.getContext());
		assertNotNull(duck.getByEntryURI(original.getEntryURI()));
	}

	@Test
	public void moveLinkReferenceBetweenContexts_keepsTheExternalMetadataURI() throws IOException, QuotaException {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Context mouse = cm.getContext("mouse");
		Entry sourceList = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry targetList = mouse.createResource(null, GraphType.List, null, null); // since owner
		Entry original = createOfType(duck, EntryType.LinkReference, sourceList.getResourceURI());
		URI externalMetadataURI = original.getExternalMetadataURI();

		Entry moved = ((List) targetList.getResource())
			.moveEntryHere(original.getEntryURI(), sourceList.getEntryURI(), true);

		// the external metadata URI must survive the create dispatch the move goes through
		assertEquals(externalMetadataURI, moved.getExternalMetadataURI());
	}

	@Test
	public void ownerRemoveTree() {
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");
		Entry listE1 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry listE2 = duck.createResource(null, GraphType.List, null, listE1.getResourceURI()); // since owner
		Entry listE3 = duck.createResource(null, GraphType.List, null, null); // since owner
		Entry linkEntry = duck.createLink(null, URI.create("https://slashdot.org/"), listE1.getResourceURI());
		Entry linkEntry2 = duck.createLink(null, URI.create("https://digg.com/"), listE2.getResourceURI());
		Entry linkEntry3 = duck.createLink(null, URI.create("https://reddit.com/"), listE3.getResourceURI());
		((List) listE1.getResource()).removeTree();
		assertNull(duck.getByEntryURI(listE1.getEntryURI()));
		assertNull(duck.getByEntryURI(listE2.getEntryURI()));
		assertNull(duck.getByEntryURI(linkEntry.getEntryURI()));
		assertNull(duck.getByEntryURI(linkEntry2.getEntryURI()));
		assertNotNull(duck.getByEntryURI(listE3.getEntryURI()));
		assertNotNull(duck.getByEntryURI(linkEntry3.getEntryURI()));
	}

}
