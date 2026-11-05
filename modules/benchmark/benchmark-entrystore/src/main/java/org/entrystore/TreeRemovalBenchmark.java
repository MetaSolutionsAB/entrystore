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
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.impl.RepositoryManagerImpl;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;

/**
 * Times a recursive delete of a folder tree, a list of lists holding as many entries as the benchmark universe, while
 * a writer keeps creating entries in another context; the write latency shows how long the removal holds up other
 * writers. The removal counts as finished once the root list's entry graph is gone, which a version that removes on
 * the request thread has reached when {@code removeTree} returns.
 */
public class TreeRemovalBenchmark {

	/** Spelled out rather than taken from RepositoryProperties, so this compiles against any version. */
	private static final IRI RESOURCE = iri("http://entrystore.org/terms/resource");

	private static final int ENTRIES_PER_FOLDER = 100;

	/**
	 * A failing removal is retried after 1 and 10 minutes, and one of a changing tree until it ends, so the wait gives
	 * up after this long.
	 */
	private static final Duration REMOVAL_DEADLINE = Duration.ofHours(1);

	public static void runBenchmark(RepositoryManagerImpl repositoryManager, int entries) {
		LogUtils.logType("  TREE  ");

		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager principalManager = repositoryManager.getPrincipalManager();
		URI admin = principalManager.getAdminUser().getURI();
		principalManager.setAuthenticatedUserURI(admin);

		Context context = (Context) contextManager.createResource(null, GraphType.Context, null, null).getResource();
		Entry root = buildTree(context, entries);
		Context written = (Context) contextManager.createResource(null, GraphType.Context, null, null).getResource();

		WriteLatencyProbe probe = new WriteLatencyProbe(written, principalManager, admin);
		LocalDateTime start = LocalDateTime.now();
		LocalDateTime returned;
		LocalDateTime end;
		LogUtils.logDate("Starting removing the tree at", start);
		try {
			((List) root.getResource()).removeTree();
			returned = LocalDateTime.now();
			Repository repository = repositoryManager.getRepository();
			IRI rootEntry = iri(root.getEntryURI().toString());
			if (!WriteLatencyProbe.await(() -> exists(repository, rootEntry), REMOVAL_DEADLINE)) {
				LogUtils.log.warn("The removal did not finish within {}; the timings below are incomplete",
						REMOVAL_DEADLINE);
			}
			end = LocalDateTime.now();
		} finally {
			probe.stop();
		}

		LogUtils.logDate("Ended removing the tree at", end);
		LogUtils.logTimeDifference("Removing the tree returned after", start, returned);
		LogUtils.logTimeDifference("Removing the tree took", start, end);
		probe.log("the tree removal");
	}

	/** A root list holding one folder per {@link #ENTRIES_PER_FOLDER} entries, each entry with a title. */
	private static Entry buildTree(Context context, int entries) {
		LocalDateTime start = LocalDateTime.now();
		Entry root = context.createResource(null, GraphType.List, null, null);
		Entry folder = null;
		for (int i = 0; i < entries; i++) {
			if (i % ENTRIES_PER_FOLDER == 0) {
				folder = context.createResource(null, GraphType.List, null, root.getResourceURI());
			}
			Entry entry = context.createResource(null, GraphType.None, null, folder.getResourceURI());
			Model metadata = new LinkedHashModel();
			metadata.add(iri(entry.getResourceURI().toString()), DCTERMS.TITLE, literal("Entry " + i));
			entry.getLocalMetadata().setGraph(metadata);
		}
		LogUtils.logTimeDifference("Building a tree of " + entries + " entries took", start, LocalDateTime.now());
		return root;
	}

	/** Reads the root's entry graph by subject, which every triple index answers without a scan. */
	private static boolean exists(Repository repository, IRI entry) {
		try (RepositoryConnection rc = repository.getConnection()) {
			return rc.hasStatement(entry, RESOURCE, null, false, entry);
		}
	}
}
