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

import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.repository.util.SolrSearchIndex;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Concurrent-writer variant of {@link MultipleTransactions} for the Solr-indexed write path
 * (ENTRYSTORE-1084): the person list is split into contiguous chunks written by N threads into
 * the same context, then the Solr submission queue is drained. This is the mode that makes the
 * A1 write-path decoupling measurable — with a single writer the submitter never contends with
 * the write path. Per-insert modulo sampling and inter-contexts are not supported here.
 */
public class ConcurrentMultipleTransactions {

	public static void runBenchmark(
			RepositoryManagerImpl repositoryManager,
			List<Object> persons,
			int writers,
			boolean batched,
			boolean isWithAcl) throws InterruptedException {

		LogUtils.logType(" WRITERS");

		LocalDateTime start = LocalDateTime.now();
		LogUtils.logDate("Starting adding to context and sending data to Solr at", start);

		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager principalManager = repositoryManager.getPrincipalManager();

		Entry newContext = contextManager.createResource(null, GraphType.Context, null, null);
		contextManager.setName(newContext.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_0");

		URI writeAs;
		if (isWithAcl) {
			User benchmarkUser = createBenchmarkUser(principalManager);
			newContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, benchmarkUser.getURI());
			benchmarkUser.setHomeContext((Context) newContext.getResource());
			writeAs = benchmarkUser.getURI();
			principalManager.setAuthenticatedUserURI(writeAs);
		} else {
			writeAs = null;
		}

		Context context = (Context) newContext.getResource();
		Consumer<List<Object>> chunkInserter = chunk -> {
			Runnable insertChunk = () -> chunk.forEach(person -> {
				if (person != null) {
					ObjectMapper.mapObjectToContext(context, person);
				}
			});
			if (batched) {
				repositoryManager.inBatch(insertChunk);
			} else {
				insertChunk.run();
			}
		};

		ConcurrentWriters.run(writers, persons, () -> {
			if (writeAs != null) {
				principalManager.setAuthenticatedUserURI(writeAs);
			}
		}, chunkInserter);

		LocalDateTime endContext = LocalDateTime.now();
		LogUtils.logDate("Ending adding to context at", endContext);
		LogUtils.logTimeDifference("Adding to context took", start, endContext);

		SolrSearchIndex solrSearchIndex = (SolrSearchIndex) repositoryManager.getIndex();
		solrSearchIndex.waitForQueueDrain();

		LocalDateTime endSolr = LocalDateTime.now();
		LogUtils.logDate("Ending sending data to Solr at", endSolr);
		LogUtils.logTimeDifference("Adding to context and sending data to Solr took", start, endSolr);
	}

	private static User createBenchmarkUser(PrincipalManager principalManager) {
		principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

		Entry benchmarkUserEntry = principalManager.createResource(null, GraphType.User, null, null);
		User benchmarkUser = (User) benchmarkUserEntry.getResource();
		principalManager.setPrincipalName(benchmarkUserEntry.getResourceURI(), BenchmarkCommons.BENCHMARK_USER);
		benchmarkUser.setSecret(BenchmarkCommons.BENCHMARK_USER_SECRET);

		return benchmarkUser;
	}
}
