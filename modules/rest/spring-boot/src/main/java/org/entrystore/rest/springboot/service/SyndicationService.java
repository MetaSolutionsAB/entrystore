package org.entrystore.rest.springboot.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.entrystore.AuthorizationException;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyndicationService {

	private static final int DEFAULT_FEED_SIZE = 50;

	private final RepositoryManagerImpl repositoryManager;

	private int maxFeedSize = -1;

	@PostConstruct
	public void init() {
		// Runs after class constructor
		maxFeedSize = repositoryManager.getConfiguration().getInt(Settings.SOLR_MAX_LIMIT, DEFAULT_FEED_SIZE);
	}

	public SyndFeed getSyndicationFeedSolr(Entry entry, String type, String language, Integer feedSize) {

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

		SyndFeed feed = createFeedFromEntries(repositoryManager.getPrincipalManager(), recursiveEntries, language, feedSize);
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

	public static SyndFeed createFeedFromEntries(PrincipalManager principalManager,
												 List<Entry> entries,
												 String language,
												 int limit) {

		SyndFeed feed = new SyndFeedImpl();
		feed.setDescription(format("Syndication feed containing max %d items", limit));

		List<SyndEntry> syndEntries = new ArrayList<>();
		int limitedCount = 0;

		for (Entry entry : entries) {
			try {
				String title = EntryUtil.getTitle(entry, language);
				String description = EntryUtil.getDescription(entry, language);

				if (title == null && description == null) {
					log.debug("Entry has neither title, nor description: {}", entry.getEntryURI());
				}

				SyndEntry syndEntry = new SyndEntryImpl();
				syndEntry.setTitle(Objects.requireNonNullElse(title, "Missing title"));

				if (description != null) {
					SyndContent syndContentDescription = new SyndContentImpl();
					syndContentDescription.setType("text/plain");
					syndContentDescription.setValue(description);
					syndEntry.setDescription(syndContentDescription);
				}

				syndEntry.setPublishedDate(entry.getCreationDate());
				syndEntry.setUpdatedDate(entry.getModifiedDate());
				syndEntry.setLink(entry.getResourceURI().toString());

				URI creator = entry.getCreator();
				if (creator != null) {
					try {
						Entry creatorEntry = principalManager.getByEntryURI(creator);
						String creatorName = EntryUtil.getName(creatorEntry);
						if (creatorName != null) {
							syndEntry.setAuthor(creatorName);
						}
					} catch (AuthorizationException ae) {
						log.debug(ae.getMessage());
					}
				}

				syndEntries.add(syndEntry);
			} catch (AuthorizationException e) {
				log.debug(e.getMessage());
				continue;
			}

			if (limitedCount++ >= limit) {
				break;
			}
		}

		feed.setEntries(syndEntries);

		return feed;
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
