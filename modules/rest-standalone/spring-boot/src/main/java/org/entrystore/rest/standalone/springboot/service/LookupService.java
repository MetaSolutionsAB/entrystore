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

package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrException;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.Metadata;
import org.entrystore.SearchIndex;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.standalone.springboot.model.api.LookupScope;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LookupService {

	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;

	public Entry lookupGlobal(URI resourceURI) {
		SearchIndex index = repositoryManager.getIndex();
		if (index == null) {
			throw new CustomResponseException("Solr search is deactivated", HttpStatus.SERVICE_UNAVAILABLE);
		}
		if (!(index instanceof SolrSearchIndex solrIndex)) {
			log.error("Lookup requires SolrSearchIndex but found: {}", index.getClass().getName());
			throw new CustomResponseException("Lookup is not available: search index is not properly configured", HttpStatus.SERVICE_UNAVAILABLE);
		}

		String escapedURI = ClientUtils.escapeQueryChars(resourceURI.toString());
		SolrQuery q = new SolrQuery("resource:" + escapedURI + " AND public:true");
		q.setStart(0);
		q.setRows(1);

		QueryResult qResult;
		try {
			qResult = solrIndex.sendQuery(q);
		} catch (SolrException se) {
			log.warn("SolrException during lookup for URI '{}' (code: {}): {}", resourceURI, se.code(), se.getMessage());
			if (se.code() >= 500) {
				throw new CustomResponseException("Search service error during lookup", HttpStatus.SERVICE_UNAVAILABLE);
			}
			throw new BadRequestException("Lookup failed: " + se.getMessage());
		} catch (Exception e) {
			log.error("Unexpected error during lookup for URI '{}': {}", resourceURI, e.getMessage(), e);
			throw new CustomResponseException("Search service encountered an unexpected error", HttpStatus.SERVICE_UNAVAILABLE);
		}

		// sendQueryForEntryURIs sets hits = -1 when SolrServerException/IOException occurs
		if (qResult.getHits() == -1) {
			log.error("Solr query returned error sentinel (hits=-1) for URI '{}'; search backend may be unavailable", resourceURI);
			throw new CustomResponseException("Search service encountered an error during lookup", HttpStatus.SERVICE_UNAVAILABLE);
		}

		return extractSingleEntry(qResult.getEntries(), resourceURI);
	}

	public Entry lookupInContext(String contextId, URI resourceURI) {
		Context context = contextService.getContextOrThrow(contextId);
		Set<Entry> entries = context.getByResourceURI(resourceURI);
		return extractSingleEntry(entries, resourceURI);
	}

	public String getMetadataByScope(Entry entry, LookupScope scope, String mediaType) {
		Model graph = new LinkedHashModel();
		EntryType entryType = entry.getEntryType();

		if (EntryType.Local.equals(entryType) || EntryType.Link.equals(entryType)) {
			if (scope == LookupScope.ALL || scope == LookupScope.LOCAL) {
				addMetadataGraph(graph, entry.getLocalMetadata(), "local", entry);
			}
		} else if (EntryType.Reference.equals(entryType)) {
			if (scope == LookupScope.ALL || scope == LookupScope.EXTERNAL) {
				addMetadataGraph(graph, entry.getCachedExternalMetadata(), "cached external", entry);
			}
		} else if (EntryType.LinkReference.equals(entryType)) {
			if (scope == LookupScope.ALL || scope == LookupScope.LOCAL) {
				addMetadataGraph(graph, entry.getLocalMetadata(), "local", entry);
			}
			if (scope == LookupScope.ALL || scope == LookupScope.EXTERNAL) {
				addMetadataGraph(graph, entry.getCachedExternalMetadata(), "cached external", entry);
			}
		}

		if (graph.isEmpty() && scope != LookupScope.ALL) {
			log.info("Scope '{}' produced no metadata for entry type '{}' (resource URI: {})",
					scope, entryType, entry.getResourceURI());
		}

		String serialized = GraphUtil.serializeGraph(graph, mediaType);
		if (serialized == null) {
			throw new BadRequestException("Unable to serialize metadata in the requested format: " + mediaType);
		}
		return serialized;
	}

	private Entry extractSingleEntry(Set<Entry> entries, URI resourceURI) {
		if (entries == null || entries.isEmpty()) {
			throw new EntityNotFoundException("No entry found for resource URI: " + resourceURI);
		}
		entries.removeIf(Objects::isNull);
		if (entries.isEmpty()) {
			log.warn("All entries resolved to null for resource URI: {}", resourceURI);
			throw new EntityNotFoundException("No entry found for resource URI: " + resourceURI);
		}
		if (entries.size() > 1) {
			log.warn("Multiple entries found for resource URI: {}", resourceURI);
		}
		return entries.iterator().next();
	}

	private void addMetadataGraph(Model target, Metadata metadata, String metadataKind, Entry entry) {
		if (metadata == null) {
			log.warn("Expected {} metadata is null for entry '{}' (type: {})",
					metadataKind, entry.getEntryURI(), entry.getEntryType());
			return;
		}
		Model mdGraph = metadata.getGraph();
		if (mdGraph == null) {
			log.warn("{} metadata graph is null for entry '{}' (type: {})",
					metadataKind, entry.getEntryURI(), entry.getEntryType());
			return;
		}
		target.addAll(mdGraph);
	}
}
