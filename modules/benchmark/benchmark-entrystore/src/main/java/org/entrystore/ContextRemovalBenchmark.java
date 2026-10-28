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

package org.entrystore;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.impl.RepositoryManagerImpl;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * Times removing the benchmark context while a writer keeps creating entries in another context; the write latency
 * shows how long the removal holds up other writers. The removal counts as finished once its removal-pending
 * marker is gone; a version that removes on the request thread writes none.
 */
public class ContextRemovalBenchmark {

	/** Spelled out rather than taken from RepositoryProperties, so this compiles against a version without it. */
	private static final IRI REMOVAL_PENDING = iri("http://entrystore.org/terms/removalPending");

	/** Entries of the removed context whose metadata relates them to the other context, giving it relation copies. */
	private static final int RELATED_ENTRIES = 100;

	private static final long WRITE_PAUSE_MILLIS = 10;

	/** A failed removal keeps its marker, so the wait gives up after this long. */
	private static final Duration REMOVAL_DEADLINE = Duration.ofHours(1);

	public static void runBenchmark(RepositoryManagerImpl repositoryManager) {
		LogUtils.logType(" REMOVE ");

		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager principalManager = repositoryManager.getPrincipalManager();
		URI admin = principalManager.getAdminUser().getURI();
		principalManager.setAuthenticatedUserURI(admin);

		Context removed = contextManager.getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1");
		Context written = (Context) contextManager.createResource(null, GraphType.Context, null, null).getResource();
		relateEntries(removed, written.createResource(null, GraphType.None, null, null));

		List<Long> writeNanos = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger failedWrites = new AtomicInteger();
		AtomicBoolean removing = new AtomicBoolean(true);
		Thread writer = Thread.ofPlatform().name("benchmark-writer").start(() -> {
			principalManager.setAuthenticatedUserURI(admin);
			while (removing.get()) {
				long start = System.nanoTime();
				try {
					written.createResource(null, GraphType.None, null, null);
					writeNanos.add(System.nanoTime() - start);
				} catch (RuntimeException e) {
					failedWrites.incrementAndGet();
				}
				try {
					Thread.sleep(WRITE_PAUSE_MILLIS);
				} catch (InterruptedException e) {
					return;
				}
			}
		});

		LocalDateTime start = LocalDateTime.now();
		LocalDateTime returned;
		LocalDateTime end;
		LogUtils.logDate("Starting removing the context at", start);
		try {
			contextManager.remove(removed.getEntry().getEntryURI());
			returned = LocalDateTime.now();
			if (!awaitRemoval(repositoryManager.getRepository(), iri(removed.getURI().toString()))) {
				LogUtils.log.warn("The removal did not finish within {}; the timings below are incomplete",
						REMOVAL_DEADLINE);
			}
			end = LocalDateTime.now();
		} finally {
			// the writer is no daemon, so a failed removal must stop it too or the benchmark never exits
			removing.set(false);
			try {
				writer.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		LogUtils.logDate("Ended removing the context at", end);
		LogUtils.logTimeDifference("Removing the context returned after", start, returned);
		LogUtils.logTimeDifference("Removing the context took", start, end);
		logWriteLatency(writeNanos, failedWrites.get());
	}

	private static void relateEntries(Context context, Entry target) {
		int related = 0;
		for (URI entryURI : context.getEntries()) {
			if (related++ == RELATED_ENTRIES) {
				break;
			}
			Entry entry = context.getByEntryURI(entryURI);
			Model metadata = entry.getMetadataGraph();
			metadata.add(iri(entry.getResourceURI().toString()), DCTERMS.RELATION,
					iri(target.getResourceURI().toString()));
			entry.getLocalMetadata().setGraph(metadata);
		}
	}

	/** @return false if the removal did not finish within {@link #REMOVAL_DEADLINE} */
	private static boolean awaitRemoval(Repository repository, IRI context) {
		long deadline = System.nanoTime() + REMOVAL_DEADLINE.toNanos();
		while (isRemovalPending(repository, context)) {
			if (System.nanoTime() - deadline > 0) {
				return false;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	private static boolean isRemovalPending(Repository repository, IRI context) {
		try (RepositoryConnection rc = repository.getConnection()) {
			return rc.hasStatement(context, REMOVAL_PENDING, null, false);
		}
	}

	private static void logWriteLatency(List<Long> writeNanos, int failedWrites) {
		List<Long> sorted = new ArrayList<>(writeNanos);
		if (sorted.isEmpty()) {
			LogUtils.log.info("No write to the other context completed during the removal ({} failed)", failedWrites);
			return;
		}
		Collections.sort(sorted);
		LogUtils.log.info("Writes to the other context during the removal: {}, p50 {} ms, max {} ms, {} failed",
				sorted.size(), sorted.get(sorted.size() / 2) / 1_000_000, sorted.getLast() / 1_000_000, failedWrites);
	}
}
