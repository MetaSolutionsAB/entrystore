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

import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1086 (E1): context export must not require the global repository monitor — it reads
 * a consistent snapshot instead, so writers are never stalled behind an export. The test holds
 * the monitor (as any write path does) and proves the export still completes.
 */
public class ContextExportConcurrencyTest extends AbstractCoreTest {

	@Test
	public void exportCompletesWhileRepositoryMonitorIsHeld() throws Exception {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry ctxEntry = cm.createResource(null, GraphType.Context, null, null);
		cm.setName(ctxEntry.getResource().getURI(), "export-concurrency");
		Context ctx = (Context) ctxEntry.getResource();
		for (int i = 0; i < 25; i++) {
			ctx.createResource(null, GraphType.None, null, null);
		}

		File dest = File.createTempFile("entrystore-export-test", ".trig");
		Set<URI> users = new HashSet<>();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		try {
			synchronized (rm.getRepository()) {
				// The monitor is what every core write path synchronizes on. Pre-E1 the export
				// would block here forever; post-E1 it must finish while we keep holding it.
				Thread exporter = new Thread(() -> {
					try {
						pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
						cm.exportContext(ctxEntry, dest, users, false, TriGWriter.class);
					} catch (Throwable t) {
						failure.set(t);
					} finally {
						done.countDown();
					}
				});
				exporter.start();
				assertTrue(done.await(15, TimeUnit.SECONDS),
						"export must complete while another thread holds the repository monitor");
			}

			assertNull(failure.get(), "export must not fail: " + failure.get());
			assertTrue(dest.length() > 0, "export must have written statements");
		} finally {
			Files.deleteIfExists(dest.toPath());
		}
	}
}
