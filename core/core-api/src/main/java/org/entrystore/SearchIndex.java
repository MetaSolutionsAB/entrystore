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

import java.net.URI;

/**
 * FIXME
 * this interface needs some work as it was created ad-hoc to fix some refactoring problems;
 * the Solr implementation contains too many Solr-specific parameters in the method signatures
 *
 * @author Hannes Ebner
 */
public interface SearchIndex {

	void shutdown();

	void reindex();

	/**
	 * Re-indexes all contexts in the calling thread. Contexts and entries that cannot be indexed are logged
	 * and skipped.
	 *
	 * @return the outcome of the reindex; documents may still be waiting in the submission queue
	 */
	ReindexResult reindexSync();

	/** @param contextURI never null; use {@link #reindex()} for the whole repository. */
	void reindex(URI contextURI);

	void reindexSync(URI contextURI);

	void postEntry(Entry entry);

	void removeEntry(Entry entry);

	boolean isIndexing();

	boolean isIndexing(URI contextURI);

	boolean ping();

	boolean isUp();

	/**
	 * Outcome of a synchronous reindex of all contexts. After an interruption, the counts only cover the
	 * contexts that were processed until then.
	 *
	 * @param failedContexts contexts that could not be resolved or whose reindex failed as a whole
	 * @param failedEntries  entries of the processed contexts that could not be loaded or indexed
	 * @param interrupted    true if the reindex stopped before all contexts were processed
	 */
	record ReindexResult(int failedContexts, int failedEntries, boolean interrupted) {

		public ReindexResult {
			if (failedContexts < 0 || failedEntries < 0) {
				throw new IllegalArgumentException("Counts must not be negative");
			}
		}

		public boolean hasFailures() {
			return failedContexts > 0 || failedEntries > 0;
		}

	}

}
