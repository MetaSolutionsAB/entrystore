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

package org.entrystore.rest.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.impl.converters.Graph2Entries;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MergeService {

	private final ContextService contextService;

	/**
	 * Merges an RDF graph into entries of the specified context.
	 *
	 * @param contextId          the context to merge into
	 * @param rdfBody            the raw RDF string body
	 * @param mediaType          the resolved/validated RDF media type
	 * @param destinationEntryId optional target entry ID (null = multi-destination,
	 *                           empty string = create new, non-empty = use/create specific)
	 */
	public void mergeRdfIntoContext(String contextId, String rdfBody, String mediaType, String destinationEntryId) {
		Context context = contextService.getContextOrThrow(contextId);

		Model graph = GraphUtil.deserializeGraph(rdfBody, mediaType);
		if (graph == null) {
			throw new BadRequestException("Unable to parse the RDF graph from the request body");
		}

		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> mergedEntries = g2e.merge(graph, destinationEntryId, null);

		if (mergedEntries == null) {
			throw new BadRequestException("Merge returned no results, possibly due to an empty or invalid graph");
		}

		log.info("Merged {} entries into context '{}'", mergedEntries.size(), contextId);
	}

}
