/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SoftCacheTest extends AbstractCoreTest{

	private Entry duck1;
	private Entry duck2;
	private Entry duck3;

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

	@Test
	public void shutdown_ok() {
		SoftCache softCache = new SoftCache();
		softCache.shutdown();
		assert softCache.isShutdown();
	}

	@Test
	public void put_ok() {
		SoftCache softCache = new SoftCache();
		assertTrue(softCache.getCache().isEmpty());
		softCache.put(duck1);
		assertEquals(1, softCache.getCache().size());
		softCache.put(duck2);
		assertEquals(2, softCache.getCache().size());
		softCache.put(duck2);
		assertEquals(2, softCache.getCache().size());
	}

	@Test
	public void clear_ok() {
		SoftCache softCache = new SoftCache();
		assertTrue(softCache.getCache().isEmpty());
		softCache.put(duck1);
		softCache.put(duck2);
		assertFalse(softCache.getCache().isEmpty());
		softCache.clear();
		assertTrue(softCache.getCache().isEmpty());
	}

	@Test
	public void remove_ok() {
		SoftCache softCache = new SoftCache();
		assertTrue(softCache.getCache().isEmpty());
		softCache.put(duck1);
		softCache.put(duck2);
		assertFalse(softCache.getCache().isEmpty());
		softCache.remove(duck1);
		assertFalse(softCache.getCache().isEmpty());
		assertEquals(1, softCache.getCache().size());
		assertNull(softCache.getCache().get(duck1.getEntryURI()));
		assertNotNull(softCache.getCache().get(duck2.getEntryURI()));
		softCache.remove(duck3);
		assertEquals(1, softCache.getCache().size());
		assertNull(softCache.getCache().get(duck3.getEntryURI()));
		softCache.remove(duck2);
		assertTrue(softCache.getCache().isEmpty());
	}

	@Test
	public void getByEntryURI_ok() {
		SoftCache softCache = new SoftCache();
		softCache.put(duck1);
		Entry tempDuck1 = softCache.getByEntryURI(duck1.getEntryURI());
		assertNotNull(tempDuck1);
		Entry tempDuck2 = softCache.getByEntryURI(duck2.getEntryURI());
		assertNull(tempDuck2);
	}
}
