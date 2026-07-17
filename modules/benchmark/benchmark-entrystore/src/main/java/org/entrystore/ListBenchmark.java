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

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * List-mutation benchmark for ENTRYSTORE-1089 (C2): fills one list with N children, then times
 * one-by-one removals from the end (tail renumber cost ~O(1) per removal post-C2, full rewrite
 * pre-C2) and from the front (both sides pay O(tail)/O(size) — the parity check).
 */
public class ListBenchmark {

	public static void run(RepositoryManagerImpl repositoryManager, int count) {
		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		LogUtils.logType("  LIST  ");

		Entry contextEntry = contextManager.createResource(null, GraphType.Context, null, null);
		contextManager.setName(contextEntry.getResource().getURI(), BenchmarkCommons.CONTEXT_ALIAS + "_list");
		Context context = (Context) contextEntry.getResource();
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		List list = (List) listEntry.getResource();

		java.util.List<URI> childURIs = new ArrayList<>(count);
		// entry creation inside one batch (fast); addChild manages its own connection and must
		// see the committed entries, so the appends run after the batch
		repositoryManager.inBatch(() -> {
			for (int i = 0; i < count; i++) {
				childURIs.add(context.createResource(null, GraphType.None, null, null).getEntryURI());
			}
		});
		LocalDateTime startFill = LocalDateTime.now();
		for (URI child : childURIs) {
			list.addChild(child);
		}
		LocalDateTime endFill = LocalDateTime.now();
		LogUtils.logTimeDifference("Filling list with " + count + " children took", startFill, endFill);

		int quarter = count / 4;

		LocalDateTime startEnd = LocalDateTime.now();
		for (int i = 0; i < quarter; i++) {
			list.removeChild(childURIs.get(childURIs.size() - 1 - i));
		}
		LocalDateTime endEnd = LocalDateTime.now();
		LogUtils.logTimeDifference("Removing " + quarter + " children from the end took", startEnd, endEnd);

		LocalDateTime startFront = LocalDateTime.now();
		for (int i = 0; i < quarter; i++) {
			list.removeChild(childURIs.get(i));
		}
		LocalDateTime endFront = LocalDateTime.now();
		LogUtils.logTimeDifference("Removing " + quarter + " children from the front took", startFront, endFront);

		LogUtils.log.info("List contains {} children after removals", list.getChildren().size());
	}
}
