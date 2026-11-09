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

import com.rometools.rome.feed.synd.SyndFeed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.entrystore.AuthorizationException;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.LocalMetadataWrapper;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.CorruptData;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.configuration.SyndicationProperties;
import org.entrystore.rest.springboot.model.dto.RenderedFeed;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.Syndication;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyndicationService {

	private static final int DEFAULT_FEED_SIZE = 50;

	private final RepositoryManagerImpl repositoryManager;
	private final SyndicationProperties syndicationProperties;

	@Value("${entrystore.solr.max-limit:" + DEFAULT_FEED_SIZE + "}")
	private int maxFeedSize;

	/**
	 * Renders the feed of a context or list entry as XML; an unknown feed type fails as a bad request. Entries of
	 * any other graph type are rejected with {@link MethodNotAllowedException}, installations without a Solr index
	 * answer {@link NotImplementedException}, and {@code feedSize} is clamped to {@code entrystore.solr.max-limit}.
	 * Item links follow the URL template configured under {@code urlTemplate} (the {@code default} one when null),
	 * else the resource URI; {@code feedTitle} replaces the default title "Feed of &lt;alias&gt;" and is sanitized
	 * like a /search feed title. Entries the caller may not read are left out, and a list in the feed whose resource
	 * it may not read adds none of its children. The requested entry itself is not checked.
	 */
	public RenderedFeed renderFeed(Entry entry, String feedType, String language, int feedSize, String urlTemplate,
								   String feedTitle) {
		SyndFeed feed = getSyndicationFeedSolr(entry, feedType, language, feedSize, urlTemplate, feedTitle);
		String xml = Syndication.convertSyndFeedToXml(feed);
		MediaType mediaType = Objects.requireNonNull(Syndication.convertFeedTypeToMediaType(feed.getFeedType()),
				() -> "No media type for feed type " + feed.getFeedType());
		return new RenderedFeed(xml, mediaType);
	}

	private SyndFeed getSyndicationFeedSolr(Entry entry, String type, String language, int feedSize,
											String urlTemplate, String feedTitle) {

		if (repositoryManager.getIndex() == null) {
			throw new NotImplementedException("Feeds are not supported by this installation");
		}

		if (feedSize > maxFeedSize) {
			feedSize = maxFeedSize;
		} else if (feedSize < 0) {
			// we allow 0 on purpose, this enables requests for the purpose of getting a result count only
			feedSize = DEFAULT_FEED_SIZE;
		}

		GraphType gt = entry.getGraphType();
		if (!GraphType.Context.equals(gt) && !GraphType.List.equals(gt)) {
			throw new MethodNotAllowedException("Entry is not a context or a list");
		}

		List<Entry> recursiveEntries = findSyndicationEntriesInSolr(entry);
		ContextManager cm = repositoryManager.getContextManager();

		String alias;
		if (GraphType.Context.equals(gt)) {
			alias = cm.getName(entry.getResourceURI());
		} else {
			alias = EntryUtil.getTitle(entry, language);
		}

		SyndFeed feed = Syndication.createFeedFromEntries(repositoryManager.getPrincipalManager(),
				syndicationProperties.template(urlTemplate), recursiveEntries, language, feedSize);
		feed.setTitle(Syndication.sanitizeFeedTitle(Objects.requireNonNullElse(feedTitle, "Feed of " + alias)));
		feed.setLink(entry.getResourceURI().toString());
		feed.setFeedType(type);

		return feed;
	}

	private List<Entry> findSyndicationEntriesInSolr(Entry entry) {

		String solrQueryValue = "lists:";
		if (GraphType.Context.equals(entry.getGraphType())) {
			solrQueryValue = "context:";
		}

		solrQueryValue += ClientUtils.escapeQueryChars(entry.getResourceURI().toString());
		SolrQuery solrQuery = new SolrQuery(solrQueryValue);
		solrQuery.setStart(0);
		solrQuery.setRows(1000);
		solrQuery.setSort("modified", SolrQuery.ORDER.desc);

		Set<Entry> searchEntries = ((SolrSearchIndex) repositoryManager.getIndex()).sendQuery(solrQuery).getEntries();
		List<Entry> recursiveEntries = feedEntries(searchEntries);
		EntryUtil.sortAfterModificationDate(recursiveEntries, false, null);

		return recursiveEntries;
	}

	/**
	 * Each hit itself, or for a local list the entries reachable through its children that the caller may read. A list
	 * is expanded at most once, so one reached from several hits is walked once and adds its children once.
	 */
	List<Entry> feedEntries(Collection<Entry> hits) {
		Set<URI> visitedLists = new HashSet<>();
		List<Entry> result = new ArrayList<>();
		for (Entry hit : hits) {
			result.addAll(feedEntries(hit, visitedLists));
		}
		return result;
	}

	private Set<Entry> feedEntries(Entry entry, Set<URI> visitedLists) {
		if (entry.getGraphType() != GraphType.List || entry.getEntryType() != EntryType.Local) {
			return Set.of(entry);
		}
		if (!visitedLists.add(entry.getEntryURI())) {
			return Set.of();
		}
		List<URI> children;
		try {
			children = ((org.entrystore.List) entry.getResource()).getChildren();
		} catch (AuthorizationException e) {
			return Set.of();
		}
		Set<Entry> result = new HashSet<>();
		for (URI uri : children) {
			Entry child = readableEntry(uri);
			if (child != null) {
				result.addAll(feedEntries(child, visitedLists));
			}
		}
		return result;
	}

	/**
	 * Checks ReadMetadata itself, since {@code getEntry} checks it only for an entry that was already cached. Like
	 * search, it also checks the entry a reference takes its metadata from, and skips the reference if that entry is
	 * corrupt.
	 */
	private @Nullable Entry readableEntry(URI uri) {
		URI referencedEntryURI = null;
		try {
			ContextManager cm = repositoryManager.getContextManager();
			PrincipalManager pm = repositoryManager.getPrincipalManager();
			Entry entry = cm.getEntry(uri);
			if (entry == null) {
				return null;
			}
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
			if ((entry.getEntryType() == EntryType.Reference || entry.getEntryType() == EntryType.LinkReference)
					&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper wrapper) {
				referencedEntryURI = wrapper.getReferencedEntryURI();
				Entry referenced = cm.getEntry(entry.getExternalMetadataURI());
				if (referenced != null) {
					pm.checkAuthenticatedUserAuthorized(referenced, AccessProperty.ReadMetadata);
				}
			}
			return entry;
		} catch (AuthorizationException e) {
			return null;
		} catch (RuntimeException e) {
			if (CorruptData.isCorruptDataOf(e, referencedEntryURI, repositoryManager.getRepositoryURL())) {
				log.warn("Leaving reference {} out of the feed because its referenced entry {} is corrupt: {}", uri,
						referencedEntryURI, CorruptData.describe(e));
				return null;
			}
			throw e;
		}
	}
}
