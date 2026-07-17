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
import org.entrystore.PrincipalManager.AccessProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ENTRYSTORE-1089 (C3/C12): mutation setters honour an active batch connection and behave
 * identically inside and outside {@code inBatch}; the multi-property ACL update applies all
 * access properties in one transaction. Persistence is always asserted against a fresh load
 * from the committed RDF (cache evicted), so a setter that silently skipped the batch commit
 * or wrote to the wrong connection fails here.
 */
public class BatchAwareSettersTest extends AbstractCoreTest {

	private Context duck;
	private URI daisy;
	private URI donald;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		duck = cm.getContext("duck");
		daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		donald = pm.getPrincipalEntry("Donald").getResourceURI();
	}

	private Entry freshLoad(Entry entry) {
		((ContextImpl) duck).softCache.remove(entry);
		return duck.getByEntryURI(entry.getEntryURI());
	}

	@Test
	public void multiPropertyAclUpdateAppliesAllPropertiesAtOnce() {
		Entry entry = duck.createResource(null, GraphType.None, null, null);

		((EntryImpl) entry).updateAllowedPrincipals(Map.of(
				AccessProperty.Administer, Set.of(daisy),
				AccessProperty.ReadMetadata, Set.of(daisy, donald),
				AccessProperty.ReadResource, Set.of(donald)
		), true, false);

		Entry reloaded = freshLoad(entry);
		assertEquals(Set.of(daisy), reloaded.getAllowedPrincipalsFor(AccessProperty.Administer));
		assertEquals(Set.of(daisy, donald), reloaded.getAllowedPrincipalsFor(AccessProperty.ReadMetadata));
		assertEquals(Set.of(donald), reloaded.getAllowedPrincipalsFor(AccessProperty.ReadResource));
		assertEquals(Set.of(), reloaded.getAllowedPrincipalsFor(AccessProperty.WriteMetadata));
	}

	@Test
	public void aclUpdateInsideBatchEqualsOutsideBatch() {
		Entry inBatchEntry = duck.createResource(null, GraphType.None, null, null);
		Entry outsideEntry = duck.createResource(null, GraphType.None, null, null);

		rm.inBatch(() -> ((EntryImpl) inBatchEntry).updateAllowedPrincipals(Map.of(
				AccessProperty.ReadMetadata, Set.of(daisy),
				AccessProperty.WriteResource, Set.of(donald)
		), true, false));
		((EntryImpl) outsideEntry).updateAllowedPrincipals(Map.of(
				AccessProperty.ReadMetadata, Set.of(daisy),
				AccessProperty.WriteResource, Set.of(donald)
		), true, false);

		for (Entry e : new Entry[]{inBatchEntry, outsideEntry}) {
			Entry reloaded = freshLoad(e);
			assertEquals(Set.of(daisy), reloaded.getAllowedPrincipalsFor(AccessProperty.ReadMetadata),
					"ReadMetadata ACL must be identical inside and outside a batch");
			assertEquals(Set.of(donald), reloaded.getAllowedPrincipalsFor(AccessProperty.WriteResource),
					"WriteResource ACL must be identical inside and outside a batch");
		}
	}

	@Test
	public void singlePropertyAclSettersStillWorkInsideBatch() {
		Entry entry = duck.createResource(null, GraphType.None, null, null);

		rm.inBatch(() -> {
			entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, daisy);
			entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, donald);
			entry.removeAllowedPrincipalsFor(AccessProperty.ReadMetadata, donald);
		});

		assertEquals(Set.of(daisy), freshLoad(entry).getAllowedPrincipalsFor(AccessProperty.ReadMetadata));
	}

	@Test
	public void aclCacheMustNotServeStagedStateAfterBatchRollback() {
		Entry entry = duck.createResource(null, GraphType.None, null, null);
		entry.setAllowedPrincipalsFor(AccessProperty.ReadMetadata, Set.of(donald));

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			((EntryImpl) entry).updateAllowedPrincipals(
					Map.of(AccessProperty.ReadMetadata, Set.of(daisy)), true, false);
			throw new IllegalStateException("boom");
		}));

		assertEquals(Set.of(donald), entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata),
				"cached ACL view must reflect committed state after a rolled-back batch");
		assertEquals(Set.of(donald), freshLoad(entry).getAllowedPrincipalsFor(AccessProperty.ReadMetadata),
				"persisted ACL must be unchanged after a rolled-back batch");
	}

	@Test
	public void aclCacheServesCommittedStateAfterSuccessfulBatch() {
		Entry entry = duck.createResource(null, GraphType.None, null, null);

		rm.inBatch(() -> ((EntryImpl) entry).updateAllowedPrincipals(
				Map.of(AccessProperty.ReadMetadata, Set.of(daisy)), true, false));

		assertEquals(Set.of(daisy), entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata),
				"cached ACL view must serve the batch-committed state without a fresh load");
	}

	@Test
	public void statusAndFileMetadataSettersHonourTheBatch() {
		Entry entry = duck.createResource(null, GraphType.None, null, null);
		URI status = URI.create("http://example.org/status/approved");

		rm.inBatch(() -> {
			entry.setStatus(status);
			entry.setFilename("batched.bin");
			entry.setFileSize(1234L);
			entry.setMimetype("application/octet-stream");
		});

		Entry reloaded = freshLoad(entry);
		assertEquals(status, reloaded.getStatus());
		assertEquals("batched.bin", reloaded.getFilename());
		assertEquals(1234L, reloaded.getFileSize());
		assertEquals("application/octet-stream", reloaded.getMimetype());
	}
}
