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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1086 (E4): {@code ContextImpl.reIndex} rebuilds the context's resource/external-md
 * lookup index and entry-id counter. The scan is now scoped to the context's own entry named
 * graphs instead of the whole repository — these tests pin that the rebuilt index is equivalent:
 * same entries, working resource/external-md lookups, preserved counter, and no cross-context
 * bleed.
 */
public class ContextReIndexTest extends AbstractCoreTest {

	private Context contextA;
	private Context contextB;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry entryA = cm.createResource(null, GraphType.Context, null, null);
		cm.setName(entryA.getResource().getURI(), "reindex-a");
		contextA = (Context) entryA.getResource();
		Entry entryB = cm.createResource(null, GraphType.Context, null, null);
		cm.setName(entryB.getResource().getURI(), "reindex-b");
		contextB = (Context) entryB.getResource();
	}

	@Test
	public void reIndexRebuildsEquivalentIndex() {
		Entry local = contextA.createResource(null, GraphType.None, null, null);
		Entry reference = contextA.createReference(null, URI.create("http://example.org/res"),
				URI.create("http://example.org/md"), null);
		Entry other = contextB.createResource(null, GraphType.None, null, null);
		Set<URI> entriesBefore = contextA.getEntries();

		contextA.reIndex();

		assertEquals(entriesBefore, contextA.getEntries(), "entry set must survive reIndex");
		assertNotNull(contextA.getByEntryURI(local.getEntryURI()));
		Set<Entry> byResource = contextA.getByResourceURI(local.getResourceURI());
		assertTrue(byResource.stream().anyMatch(e -> e.getEntryURI().equals(local.getEntryURI())),
				"resource URI lookup must work after reIndex");
		Set<Entry> byExternalMd = contextA.getByExternalMdURI(URI.create("http://example.org/md"));
		assertTrue(byExternalMd.stream().anyMatch(e -> e.getEntryURI().equals(reference.getEntryURI())),
				"external metadata lookup must work after reIndex");

		// Cross-context isolation: B's index and entries are untouched.
		assertNotNull(contextB.getByEntryURI(other.getEntryURI()));
		assertEquals(1, contextB.getEntries().stream().filter(u -> !u.toString().contains("/_")).count());
	}

	@Test
	public void reIndexPreservesEntryIdCounter() {
		Entry first = contextA.createResource(null, GraphType.None, null, null);
		Entry second = contextA.createResource(null, GraphType.None, null, null);

		contextA.reIndex();

		Entry third = contextA.createResource(null, GraphType.None, null, null);
		assertFalse(third.getId().equals(first.getId()) || third.getId().equals(second.getId()),
				"the id counter must survive reIndex — a reset counter would reuse existing ids");
		assertNotNull(contextA.getByEntryURI(third.getEntryURI()));
	}

	@Test
	public void reIndexOfEmptyContextYieldsEmptyIndex() {
		contextA.reIndex();

		assertTrue(contextA.getEntries().stream().noneMatch(u -> u.toString().contains("/entry/1")),
				"an empty context must stay empty after reIndex");
		Entry created = contextA.createResource(null, GraphType.None, null, null);
		assertNotNull(contextA.getByEntryURI(created.getEntryURI()),
				"creation after empty-context reIndex must work");
	}
}
