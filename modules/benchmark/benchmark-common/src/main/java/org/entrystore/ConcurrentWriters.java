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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Fans a person list out over N platform threads in contiguous chunks and blocks until every
 * chunk is inserted (ENTRYSTORE-1084). Each worker first runs {@code perThreadSetup} —
 * authentication is a ThreadLocal in PrincipalManager, so every writer must authenticate
 * itself — then hands its whole chunk to {@code chunkInserter}. When batching is requested the
 * caller wraps the chunk insert in RepositoryManager.inBatch inside {@code chunkInserter}:
 * a batch must stay on one thread, and one chunk = one thread = one batch.
 */
public final class ConcurrentWriters {

	private ConcurrentWriters() {
	}

	public static void run(int writers, List<Object> persons, Runnable perThreadSetup,
			Consumer<List<Object>> chunkInserter) throws InterruptedException {

		List<List<Object>> chunks = new ArrayList<>(writers);
		int chunkSize = Math.ceilDiv(persons.size(), writers);
		for (int from = 0; from < persons.size(); from += chunkSize) {
			chunks.add(persons.subList(from, Math.min(from + chunkSize, persons.size())));
		}

		ExecutorService pool = Executors.newFixedThreadPool(chunks.size());
		try {
			List<Future<?>> results = new ArrayList<>(chunks.size());
			for (List<Object> chunk : chunks) {
				results.add(pool.submit(() -> {
					perThreadSetup.run();
					chunkInserter.accept(chunk);
				}));
			}
			for (Future<?> result : results) {
				try {
					result.get();
				} catch (ExecutionException e) {
					throw new IllegalStateException("Writer thread failed", e.getCause());
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}
}
