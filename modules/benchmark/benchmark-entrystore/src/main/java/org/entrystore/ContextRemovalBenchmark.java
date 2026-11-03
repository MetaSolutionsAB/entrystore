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

		WriteLatencyProbe probe = new WriteLatencyProbe(written, principalManager, admin);
		LocalDateTime start = LocalDateTime.now();
		LocalDateTime returned;
		LocalDateTime end;
		LogUtils.logDate("Starting removing the context at", start);
		try {
			contextManager.remove(removed.getEntry().getEntryURI());
			returned = LocalDateTime.now();
			Repository repository = repositoryManager.getRepository();
			IRI contextIRI = iri(removed.getURI().toString());
			if (!WriteLatencyProbe.await(() -> isRemovalPending(repository, contextIRI), REMOVAL_DEADLINE)) {
				LogUtils.log.warn("The removal did not finish within {}; the timings below are incomplete",
						REMOVAL_DEADLINE);
			}
			end = LocalDateTime.now();
		} finally {
			probe.stop();
		}

		LogUtils.logDate("Ended removing the context at", end);
		LogUtils.logTimeDifference("Removing the context returned after", start, returned);
		LogUtils.logTimeDifference("Removing the context took", start, end);
		probe.log("the removal");
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

	private static boolean isRemovalPending(Repository repository, IRI context) {
		try (RepositoryConnection rc = repository.getConnection()) {
			return rc.hasStatement(context, REMOVAL_PENDING, null, false);
		}
	}
}
