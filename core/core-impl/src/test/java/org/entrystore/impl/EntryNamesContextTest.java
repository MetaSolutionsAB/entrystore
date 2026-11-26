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

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntryNamesContextTest extends AbstractCoreTest {

	/** A second context object over {@code _principals}: the names are in the store, its name index is not loaded. */
	private EntryNamesContext coldPrincipals() {
		EntryImpl principalsEntry = (EntryImpl) pm.getEntry();
		return new EntryNamesContext(principalsEntry, principalsEntry.getResourceURI().toString(), rm.getSoftCache());
	}

	@Test
	public void getEntryByName_duringTheFirstLoad_waitsForTheFilledIndex() throws Exception {
		EntryNamesContext principals = coldPrincipals();
		CompletableFuture<Entry> readDuringLoad = new CompletableFuture<>();
		AtomicBoolean readerWaited = new AtomicBoolean();
		principals.nameScanHookForTests = () -> {
			Thread reader = Thread.ofPlatform().start(() -> {
				try {
					readDuringLoad.complete(principals.getEntryByName("donald"));
				} catch (Throwable t) {
					readDuringLoad.completeExceptionally(t);
				}
			});
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (reader.getState() != Thread.State.BLOCKED && !readDuringLoad.isDone()
					&& System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			readerWaited.set(reader.getState() == Thread.State.BLOCKED);
		};

		principals.getEntryByName("daisy");

		assertTrue(readerWaited.get(), "a reader that arrives while the index is filled must wait for it");
		Entry donald = readDuringLoad.get(5, TimeUnit.SECONDS);
		assertNotNull(donald, "and then answer from the filled index");
		assertEquals(pm.getPrincipalEntry("Donald").getEntryURI(), donald.getEntryURI());
	}

	@Test
	public void getName_afterTheFirstLoad_isTheStoredName() {
		Entry donald = pm.getPrincipalEntry("Donald");

		assertEquals("donald", coldPrincipals().getName(donald.getEntryURI()));
	}

	@Test
	public void loadNameIndex_unreadableAliasStatement_isSkipped() {
		EntryNamesContext principals = coldPrincipals();
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI("http://example.com/not a uri"), RepositoryProperties.alias,
					vf.createLiteral("broken"), principals.resourceURI);
		}

		assertNotNull(principals.getEntryByName("donald"), "one unreadable name must not cost the others");
	}

	@Test
	public void nullNameAndNullEntryURI_areUnknown() {
		EntryNamesContext principals = coldPrincipals();

		assertNull(principals.getEntryByName(null));
		assertNull(principals.getName(null));
	}
}
