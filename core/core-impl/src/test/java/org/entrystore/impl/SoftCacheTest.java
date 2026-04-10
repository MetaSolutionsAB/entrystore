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
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SoftCacheTest extends AbstractCoreTest {

	private Entry duck1;
	private Entry duck2;
	private Entry duck3;
	private final List<SoftCache> testCachesList = new ArrayList<>();

	@BeforeEach
	public void setUp() {
		super.setUp();
		TestSuite.addEntriesInDisneySuite(rm);
		rm.setCheckForAuthorization(false);
		Context duck = cm.getContext("duck");
		duck1 = duck.get("1");
		duck2 = duck.get("2");
		duck3 = duck.get("3");
	}

	@AfterEach
	public void shutdownCaches() {
		testCachesList.forEach(SoftCache::shutdown);
		testCachesList.clear();
	}

	private SoftCache createCache() {
		SoftCache cache = new SoftCache();
		testCachesList.add(cache);
		return cache;
	}

	@Test
	public void shutdown_ok() {
		SoftCache softCache = createCache();
		softCache.shutdown();
		assertTrue(softCache.isShutdown());
	}

	@Test
	public void put_ok() {
		SoftCache softCache = createCache();
		assertEquals(0, softCache.cacheSize());
		softCache.put(duck1);
		assertEquals(1, softCache.cacheSize());
		softCache.put(duck2);
		assertEquals(2, softCache.cacheSize());
		softCache.put(duck2);
		assertEquals(2, softCache.cacheSize());
	}

	@Test
	public void clear_ok() {
		SoftCache softCache = createCache();
		assertEquals(0, softCache.cacheSize());
		softCache.put(duck1);
		softCache.put(duck2);
		assertEquals(2, softCache.cacheSize());
		softCache.clear();
		assertEquals(0, softCache.cacheSize());
	}

	@Test
	public void remove_ok() {
		SoftCache softCache = createCache();
		assertEquals(0, softCache.cacheSize());
		softCache.put(duck1);
		softCache.put(duck2);
		assertEquals(2, softCache.cacheSize());
		softCache.remove(duck1);
		assertEquals(1, softCache.cacheSize());
		assertNull(softCache.getByEntryURI(duck1.getEntryURI()));
		assertNotNull(softCache.getByEntryURI(duck2.getEntryURI()));
		softCache.remove(duck3);
		assertEquals(1, softCache.cacheSize());
		assertNull(softCache.getByEntryURI(duck3.getEntryURI()));
		softCache.remove(duck2);
		assertEquals(0, softCache.cacheSize());
	}

	@Test
	public void getByEntryURI_ok() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		Entry tempDuck1 = softCache.getByEntryURI(duck1.getEntryURI());
		assertNotNull(tempDuck1);
		Entry tempDuck2 = softCache.getByEntryURI(duck2.getEntryURI());
		assertNull(tempDuck2);
	}

	@Test
	public void put_null_doesNotThrow() {
		SoftCache softCache = createCache();
		softCache.put(null);
		assertEquals(0, softCache.cacheSize());
	}

	@Test
	public void remove_null_doesNotThrow() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		softCache.remove(null);
		assertEquals(1, softCache.cacheSize());
	}

	@Test
	public void getByEntryURI_null_returnsNull() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		assertNull(softCache.getByEntryURI(null));
	}

	@Test
	public void getByURI_resourceURI_returnsEntry() {
		SoftCache softCache = createCache();
		softCache.put(duck1);

		Set<Entry> results = softCache.getByURI(duck1.getResourceURI());
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.contains(duck1));
	}

	@Test
	public void getByURI_metadataURI_returnsEntry() {
		SoftCache softCache = createCache();
		softCache.put(duck1);

		Set<Entry> results = softCache.getByURI(duck1.getLocalMetadataURI());
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.contains(duck1));
	}

	@Test
	public void getByURI_unknownURI_returnsNull() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		assertNull(softCache.getByURI(duck2.getResourceURI()));
	}

	@Test
	public void getByURI_null_returnsNull() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		assertNull(softCache.getByURI(null));
	}

	@Test
	public void getByURI_afterRemove_returnsNull() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		assertNotNull(softCache.getByURI(duck1.getResourceURI()));

		softCache.remove(duck1);
		assertNull(softCache.getByURI(duck1.getResourceURI()));
	}

	@Test
	public void getByURI_afterClear_returnsNull() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		softCache.put(duck2);
		assertNotNull(softCache.getByURI(duck1.getResourceURI()));

		softCache.clear();
		assertNull(softCache.getByURI(duck1.getResourceURI()));
		assertNull(softCache.getByURI(duck2.getResourceURI()));
	}

	@Test
	public void put_sameEntryTwice_doesNotDuplicateInReverseIndex() {
		SoftCache softCache = createCache();
		softCache.put(duck1);
		softCache.put(duck1);

		Set<Entry> results = softCache.getByURI(duck1.getResourceURI());
		assertNotNull(results);
		assertEquals(1, results.size());
	}

	@Test
	public void getByURI_sharedResourceURI_returnsBothEntries() {
		Context duck = cm.getContext("duck");
		URI sharedURI = URI.create("http://example.org/shared-resource");
		Entry link1 = duck.createLink(null, sharedURI, null);
		Entry link2 = duck.createLink(null, sharedURI, null);

		SoftCache softCache = createCache();
		softCache.put(link1);
		softCache.put(link2);

		Set<Entry> results = softCache.getByURI(sharedURI);
		assertNotNull(results);
		assertEquals(2, results.size());
		assertTrue(results.contains(link1));
		assertTrue(results.contains(link2));
	}

	@Test
	public void getByURI_removeOneOfSharedEntries_returnsRemaining() {
		Context duck = cm.getContext("duck");
		URI sharedURI = URI.create("http://example.org/shared-resource-2");
		Entry link1 = duck.createLink(null, sharedURI, null);
		Entry link2 = duck.createLink(null, sharedURI, null);

		SoftCache softCache = createCache();
		softCache.put(link1);
		softCache.put(link2);

		softCache.remove(link1);

		Set<Entry> results = softCache.getByURI(sharedURI);
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.contains(link2));
	}

	@Test
	public void getByURI_removeBothSharedEntries_returnsNull() {
		Context duck = cm.getContext("duck");
		URI sharedURI = URI.create("http://example.org/shared-resource-3");
		Entry link1 = duck.createLink(null, sharedURI, null);
		Entry link2 = duck.createLink(null, sharedURI, null);

		SoftCache softCache = createCache();
		softCache.put(link1);
		softCache.put(link2);

		softCache.remove(link1);
		softCache.remove(link2);

		assertNull(softCache.getByURI(sharedURI));
	}
}
