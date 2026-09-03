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
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.dto.RenderedFeed;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.Syndication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyndicationService {

	private static final int DEFAULT_FEED_SIZE = 50;

	private final RepositoryManagerImpl repositoryManager;

	@Value("${entrystore.solr.max-limit:" + DEFAULT_FEED_SIZE + "}")
	private int maxFeedSize;

	/**
	 * Renders the feed of a context or list entry as XML; an unknown feed type fails as a bad request. Entries of
	 * any other graph type are rejected with {@link MethodNotAllowedException}, installations without a Solr index
	 * answer {@link NotImplementedException}, and {@code feedSize} is clamped to {@code entrystore.solr.max-limit}.
	 */
	public RenderedFeed renderFeed(Entry entry, String feedType, String language, int feedSize) {
		SyndFeed feed = getSyndicationFeedSolr(entry, feedType, language, feedSize);
		String xml = Syndication.convertSyndFeedToXml(feed);
		MediaType mediaType = Objects.requireNonNull(Syndication.convertFeedTypeToMediaType(feed.getFeedType()),
				() -> "No media type for feed type " + feed.getFeedType());
		return new RenderedFeed(xml, mediaType);
	}

	private SyndFeed getSyndicationFeedSolr(Entry entry, String type, String language, int feedSize) {

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

		SyndFeed feed = Syndication.createFeedFromEntries(repositoryManager.getPrincipalManager(), recursiveEntries,
				language, feedSize, e -> e.getResourceURI().toString());
		feed.setTitle("Feed of \"" + alias + "\"");
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
		List<Entry> recursiveEntries = new LinkedList<>();
		for (Entry e : searchEntries) {
			recursiveEntries.addAll(getListChildrenRecursively(e));
		}
		EntryUtil.sortAfterModificationDate(recursiveEntries, false, null);

		return recursiveEntries;
	}

	private Set<Entry> getListChildrenRecursively(Entry listEntry) {
		Set<Entry> result = new HashSet<>();
		if (GraphType.List.equals(listEntry.getGraphType()) && EntryType.Local.equals(listEntry.getEntryType())) {
			org.entrystore.List l = (org.entrystore.List) listEntry.getResource();
			List<URI> c = l.getChildren();
			for (URI uri : c) {
				Entry e = repositoryManager.getContextManager().getEntry(uri);
				if (e != null) {
					if (GraphType.List.equals(e.getGraphType())) {
						result.addAll(getListChildrenRecursively(e));
					} else {
						result.add(e);
					}
				}
			}
		} else {
			result.add(listEntry);
		}
		return result;
	}


}
