/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.repository.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Queues;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.BaseHttpSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.SearchIndex;
import org.entrystore.User;
import org.entrystore.impl.LocalMetadataWrapper;
import org.entrystore.impl.RegularContext;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.lang.Thread.interrupted;


/**
 * @author Hannes Ebner
 */
public class SolrSearchIndex implements SearchIndex {

	private static final Logger log = LoggerFactory.getLogger(SolrSearchIndex.class);

	private static final int BATCH_SIZE_ADD = 100;

	private static final int BATCH_SIZE_DELETE = 100;

	private static final int SOLR_COMMIT_WITHIN = 1000;

	private static final int SOLR_COMMIT_WITHIN_MAX = 10000;

	private String defaultSortLang = null;

	private boolean extractFulltext = false;

	private boolean related = false;

	private Map<IRI, Boolean> relatedProperties = null;

	private boolean relatedContainsGlobal = false;

	private final RepositoryManager rm;

	private final SolrClient solrServer;

	private final Thread documentSubmitter;

	private final Thread delayedContextIndexer;

	private final Cache<URI, SolrInputDocument> postQueue = Caffeine.newBuilder().build();

	private final Queue<URI> deleteQueue = Queues.newConcurrentLinkedQueue();

	private final Map<URI, Future> reindexing = Collections.synchronizedMap(new HashMap<>());

	private final SimpleDateFormat solrDateFormatter;

	private final ExecutorService reindexExecutor = Executors.newSingleThreadExecutor();

	private final Map<URI, DelayedContextIndexerInfo> delayedReindex = Collections.synchronizedMap(new HashMap<>());

	private ValueFactory valueFactory;

	public class SolrInputDocumentSubmitter extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				postQueue.cleanUp();
				int batchCount = 0;

				if (postQueue.estimatedSize() > 0 || !deleteQueue.isEmpty()) {

					if (!deleteQueue.isEmpty()) {
						StringBuilder deleteQuery = new StringBuilder("uri:(");
						synchronized (deleteQueue) {
							while (batchCount < BATCH_SIZE_DELETE) {
								URI uri = deleteQueue.poll();
								if (uri == null) {
									break;
								}
								if (batchCount > 0) {
									deleteQuery.append(" OR ");
								}
								deleteQuery.append(ClientUtils.escapeQueryChars(uri.toString()));
								batchCount++;
							}
						}
						deleteQuery.append(")");

						if (batchCount > 0) {
							UpdateRequest delReq = new UpdateRequest();
							String deleteQueryStr = deleteQuery.toString();
							delReq.deleteByQuery(deleteQueryStr);
							delReq.setCommitWithin(SOLR_COMMIT_WITHIN);
							try {
								log.info("Sending request to delete " + batchCount + " entries from Solr, " + deleteQueue.size() + " entries remaining in delete queue");
								delReq.process(solrServer);
							} catch (SolrServerException | IOException e) {
								log.error(e.getMessage(), e);
							}
						}
					}

					if (postQueue.estimatedSize() > 0) {
						UpdateRequest addReq = new UpdateRequest();
						synchronized (postQueue) {
							ConcurrentMap<URI, SolrInputDocument> postQueueMap = postQueue.asMap();
							Iterator<URI> it = postQueueMap.keySet().iterator();
							while (batchCount < BATCH_SIZE_ADD && it.hasNext()) {
								URI key = it.next();
								SolrInputDocument doc = postQueueMap.get(key);
								postQueueMap.remove(key, doc);
								if (doc == null) {
									log.warn("Value for key " + key + " is null in Solr submit queue");
								}
								addReq.add(doc);
								batchCount++;
							}
						}

						try {
							postQueue.cleanUp();
							log.info("Sending {} entries to Solr, {} entries remaining in post queue", addReq.getDocuments() != null ? addReq.getDocuments().size() : 0, postQueue.estimatedSize());
							// when BATCH_SIZE_ADD * 5 we assume we are indexing large batches
							if (postQueue.estimatedSize() > BATCH_SIZE_ADD * 5) {
								addReq.setCommitWithin(SOLR_COMMIT_WITHIN_MAX);
							} else {
								addReq.setCommitWithin(SOLR_COMMIT_WITHIN);
							}
							addReq.process(solrServer);
						} catch (BaseHttpSolrClient.RemoteSolrException | SolrServerException | IOException e) {
							log.error(e.getMessage(), e);
						}
					}

				} else {
					try {
						Thread.sleep(500);
					} catch (InterruptedException ie) {
						log.info("Solr document submitter got interrupted, shutting down submitter thread");
						return;
					}
				}
			}
		}

	}

	public static class DelayedContextIndexerInfo {

		LocalDateTime submitted;

		boolean guestReadable;

	}

	public static class FacetSettings {

		public String fields;

		public int minCount;

		public int limit;

		public String matches;

		public boolean missing;

	}

	public class DelayedContextIndexer extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				synchronized (delayedReindex) {
					Iterator<URI> it = delayedReindex.keySet().iterator();
					while (it.hasNext()) {
						URI contextURI = it.next();
						DelayedContextIndexerInfo info = delayedReindex.get(contextURI);
						if (info.submitted.until(LocalDateTime.now(), ChronoUnit.SECONDS) >= 10) {
							log.info("Submitting context for reindexing after 10 seconds delay");
							reindex(contextURI, false);
							it.remove();
						}
					}
				}

				try {
					Thread.sleep(1000);
				} catch (InterruptedException ie) {
					log.info("Solr delayed context indexer got interrupted, shutting down thread");
					return;
				}
			}
		}

	}

	public SolrSearchIndex(RepositoryManager rm, SolrClient solrServer) {
		solrDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		solrDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.rm = rm;
		valueFactory = this.rm.getValueFactory();
		this.solrServer = solrServer;
		extractFulltext = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_EXTRACT_FULLTEXT, "off"));
		related = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_RELATED, "off"));
		defaultSortLang = rm.getConfiguration().getString(Settings.SOLR_DEFAULT_SORTING_LANG);
		if (related) {
			List<String> relPropsSetting = rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<>());
			if (relPropsSetting.isEmpty()) {
				related = false;
			} else {
				relatedProperties = new HashMap<>();
				for (String relProp : rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<>())) {
					if (relProp.endsWith(",global")) {
						relatedProperties.put(valueFactory.createIRI(relProp.substring(0, relProp.indexOf(","))), true);
					} else {
						relatedProperties.put(valueFactory.createIRI(relProp), false);
					}
				}
				relatedContainsGlobal = relatedProperties.containsValue(true);
			}
		}

		documentSubmitter = new SolrInputDocumentSubmitter();
		documentSubmitter.start();

		delayedContextIndexer = new DelayedContextIndexer();
		delayedContextIndexer.start();
	}

	public void shutdown() {
		if (documentSubmitter != null) {
			documentSubmitter.interrupt();
		}

		if (delayedContextIndexer != null) {
			delayedContextIndexer.interrupt();
		}

		reindexExecutor.shutdown();

		try {
			log.debug("Sending commit to Solr");
			solrServer.commit(true, false);
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage());
		}
	}

	public void clearSolrIndex(SolrClient solrServer) {
		UpdateRequest req = new UpdateRequest();
		req.deleteByQuery("*:*");
		req.setCommitWithin(SOLR_COMMIT_WITHIN);
		try {
			req.process(solrServer);
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	public void clearSolrIndex(SolrClient solrServer, Date expirationDate, Entry contextEntry) {
		if (solrServer == null || (expirationDate == null && contextEntry == null)) {
			throw new IllegalArgumentException("Too many parameters are null");
		}
		UpdateRequest req = new UpdateRequest();
		String deleteQuery = "";
		if (expirationDate != null) {
			String solrExpirationDate = ClientUtils.escapeQueryChars(solrDateFormatter.format(expirationDate));
			deleteQuery += "indexedAt:[* TO " + solrExpirationDate + "}";
		}
		if (contextEntry != null) {
			if (!deleteQuery.isEmpty()) {
				deleteQuery += " AND ";
			}
			deleteQuery += "context:" + ClientUtils.escapeQueryChars(contextEntry.getResourceURI().toString());
		}
		req.deleteByQuery(deleteQuery);
		req.setCommitWithin(SOLR_COMMIT_WITHIN);
		try {
			req.process(solrServer);
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	/**
	 * Re-indexes a context and all of its entries in Solr. Starts a new indexing thread and
	 * ends an eventually existing reindexing thread if there is one for the same scope.
	 *
	 * @param purgeAllBeforeReindex If true, the index will be emptied before re-indexation
	 *                                 starts. If false, expired entries will be removed after
	 *                                 re-indexation is finished.
	 */
	public void reindex(boolean purgeAllBeforeReindex) {
		reindex(purgeAllBeforeReindex, false);
	}

	/**
	 * Re-indexes a context and all of its entries in Solr. Starts a new indexing thread and
	 * ends an eventually existing reindexing thread if there is one for the same scope.
	 *
	 * @param contextURI The URI of the context to be re-indexed. Use "null" to reindex the whole repository.
	 * @param purgeAllBeforeReindex If true, the index will be emptied before re-indexation
	 *                                 starts. If false, expired entries will be removed after
	 *                                 re-indexation is finished.
	 */
	public void reindex(URI contextURI, boolean purgeAllBeforeReindex) {
		synchronized (reindexing) {
			if (reindexing.containsKey(contextURI)) {
				Future existingIndexer = reindexing.get(contextURI);
				if (!existingIndexer.isDone()) {
					log.info("Cancelling existing indexer thread for {}", contextURI);
					existingIndexer.cancel(true);
				}
				reindexing.remove(contextURI);
			}
			Future indexer = reindexExecutor.submit(() -> {
				reindexSync(contextURI, false);
				reindexing.remove(contextURI);
			});
			reindexing.put(contextURI, indexer);
		}
	}

	public void reindexSync(boolean purgeAllBeforeReindex) {
		reindex(purgeAllBeforeReindex, true);
	}

	private void reindex(boolean purgeAllBeforeReindex, boolean sync) {
		Set<URI> contexts = new HashSet<>();
		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			contexts = rm.getContextManager().getEntries();
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		for (URI contextURI : contexts) {
			if (sync) {
				reindexSync(contextURI, purgeAllBeforeReindex);
			} else {
				reindex(contextURI, purgeAllBeforeReindex);
			}
		}
	}

	public void reindexSync(URI contextURI, boolean purgeAllBeforeReindex) {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return;
		}
		if (contextURI == null) {
			throw new IllegalArgumentException("Context URI must not be null");
		}

		log.info("Starting Solr reindexing of context " + contextURI);

		Entry contextEntry = rm.getContextManager().getByEntryURI(contextURI);

		if (purgeAllBeforeReindex) {
			clearSolrIndex(solrServer, null, contextEntry);
		}

		Date reindexStart = new Date();

		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			URI lastIndexedEntryURI = postContextEntriesToQueue(contextURI);
			if (lastIndexedEntryURI != null) {
				if (!purgeAllBeforeReindex) {
					new Thread(() -> {
						// We need to wait until the last entry of the context is indexed, otherwise this would leave a gap of some time
						// (between milliseconds to seconds or even minutes) where the entries of that particular context are not in the index
						while (postQueue.asMap().containsKey(lastIndexedEntryURI)) {
							try {
								log.debug("Entries of context {} are still in submission queue, sleeping 5 seconds before attempting new purge of expired entries", contextURI);
								Thread.sleep(5000);
							} catch (InterruptedException e) {
								log.error("Cleanup of context index was interrupted, a full Solr reindex may be necessary: {}", e.getMessage());
							}
							postQueue.cleanUp();
						}
						clearSolrIndex(solrServer, reindexStart, contextEntry);
						log.info("Expired entries of context {} have been purged from the index", contextURI);
					}).start();
				}
				log.info("Finished Solr reindexing of context {}, took {} ms; the Solr submission queue may still contain yet to be processed documents", contextURI, new Date().getTime() - reindexStart.getTime());
			} else {
				log.debug("Solr reindexing of context {} could not be completed, either the context could not be loaded or (most likely) another process started reindexing the same context before the ongoing process was complete", contextURI);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public boolean isIndexing() {
		return isIndexing(null);
	}

	public boolean isIndexing(URI contextURI) {
		return reindexing.containsKey(contextURI);
	}

	public Set<URI> getIndexingContexts() {
		return reindexing.keySet();
	}

	public long getPostQueueSize() {
		postQueue.cleanUp();
		return postQueue.estimatedSize();
	}

	public long getDeleteQueueSize() {
		return deleteQueue.size();
	}

	@Override
	public boolean ping() {
		try {
			SolrPingResponse pingResponse = this.solrServer.ping();
			if (pingResponse.getStatus() == 0) {
				return true;
			}
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage());
		}
		return false;
	}

	@Override
	public boolean isUp() {
		return ping() && documentSubmitter.isAlive() && delayedContextIndexer.isAlive() && !reindexExecutor.isShutdown();
	}

	/**
	 * This method was originally developed for ACL changes, but we also use it for project types now. If ACL changes
	 * are reverted within a short period of time then the context is removed from the reindexing queue again. This
	 * does not apply for other reindexing triggers such as a changed project type.
	 */
	public void submitContextForDelayedReindex(Entry contextEntry, Model entryGraph) {
		synchronized (delayedReindex) {
			IRI guestURI = valueFactory.createIRI(rm.getPrincipalManager().getGuestUser().getURI().toString());
			URI contextURI = contextEntry.getEntryURI();
			Model m = new LinkedHashModel(entryGraph);
			boolean newGuestReadable = m.contains(valueFactory.createIRI(contextEntry.getLocalMetadataURI().toString()), RepositoryProperties.Read, guestURI) ||
					m.contains(valueFactory.createIRI(contextEntry.getLocalMetadataURI().toString()), RepositoryProperties.Write, guestURI);
			if (delayedReindex.containsKey(contextURI) && (delayedReindex.get(contextURI).guestReadable != newGuestReadable)) {
				// the context has been switched back to the previous guest ACL and does not need to be reindexed anymore
				log.info("Removing context from delayed reindexing queue due to reverted ACL change within grace period");
				delayedReindex.remove(contextURI);
			} else {
				log.info("Enqueueing context for delayed reindexing due to ACL change");
				DelayedContextIndexerInfo info = new DelayedContextIndexerInfo();
				info.submitted = LocalDateTime.now();
				info.guestReadable = newGuestReadable;
				delayedReindex.put(contextURI, info);
			}
		}
	}

	private URI postContextEntriesToQueue(URI contextURI) {
		String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
		ContextManager cm = rm.getContextManager();
		Context context = cm.getContext(id);
		if (context != null) {
			URI lastEntryURI = null;
			for (URI entryURI : context.getEntries()) {
				if (interrupted()) {
					log.info("Indexer thread received interrupt, stopping reindexing of " + contextURI);
					return null;
				}
				if (entryURI != null) {
					Entry entry = cm.getEntry(entryURI);
					if (entry == null) {
						continue;
					}
					synchronized (postQueue) {
						if (!entry.isDeleted() && !entry.getContext().isDeleted()) {
							log.info("Adding entry to Solr post queue: {}", entryURI);
							try {
								postQueue.put(entryURI, constructSolrInputDocument(entry, extractFulltext));
							} catch (Exception e) {
								log.error("Not indexing {} due to error: {}", entryURI, e.getMessage());
							}
						} else {
							log.debug("Not adding deleted entry to post queue: {}", entryURI);
						}
					}
					lastEntryURI = entryURI;
				}
			}
			return lastEntryURI;
		}
		return null;
	}

	private void storeLiteralsWithLanguages(SolrInputDocument doc, Map<String, Set<String>> literals, String literalType) {
		final String missingLanguageString = "nolang";
		final String defaultString = "default";

		Set<String> alreadySetLanguages = new HashSet<>();

		for (String literal : literals.keySet()) {
			doc.addField(literalType, literal);

			// we also store title.{lang} as dynamic field to be able to
			// sort after titles in a specific language
			Set<String> literalLanguages = literals.get(literal);

			if (literalLanguages.isEmpty() || ObjectUtils.allNull(literalLanguages)) {
				if (!alreadySetLanguages.contains(missingLanguageString)) {
					doc.addField(String.format("%s.%s", literalType, missingLanguageString), literal);
					alreadySetLanguages.add(missingLanguageString);
				}
			} else {
				for (String language : literalLanguages) {
					if (language != null && language.equalsIgnoreCase(defaultSortLang) && !alreadySetLanguages.contains(defaultString)) {
						// if a default sorting language is configured, we create a field for that
						doc.addField(String.format("%s.%s", literalType, defaultString), literal);
						alreadySetLanguages.add(defaultString);
					}

					// we only want one title per language, otherwise sorting will not work
					if (!alreadySetLanguages.contains(language)) {
						doc.addField(String.format("%s.%s", literalType, language == null ? missingLanguageString : language), literal);
						alreadySetLanguages.add(language);
					}
				}
			}
		}
	}

	public SolrInputDocument constructSolrInputDocument(Entry entry, boolean extractFulltext) {
		Model mdGraph = entry.getMetadataGraph();
		Model entryGraph = entry.getGraph();
		URI resourceURI = entry.getResourceURI();

		SolrInputDocument doc = new SolrInputDocument();

		// URI
		doc.setField("uri", entry.getEntryURI().toString());

		// resource URI
		doc.setField("resource", resourceURI.toString());

		// resource URI of the surrounding context
		doc.setField("context", entry.getContext().getEntry().getResourceURI().toString());

		// RDF type
		for (Statement value : entryGraph.filter(valueFactory.createIRI(resourceURI.toString()), RDF.TYPE, null)) {
			doc.addField("rdfType", value.getObject().stringValue());
		}
		for (Statement statement : mdGraph.filter(valueFactory.createIRI(resourceURI.toString()), RDF.TYPE, null)) {
			doc.addField("rdfType", statement.getObject().stringValue());
		}

		// creation date
		Date creationDate = entry.getCreationDate();
		if (creationDate != null) {
			doc.setField("created", creationDate);
		}

		// modification date
		Date modificationDate = entry.getModifiedDate();
		if (modificationDate != null) {
			doc.setField("modified", modificationDate);
		}

		// types
		doc.setField("graphType", entry.getGraphType().name());
		doc.setField("entryType", entry.getEntryType().name());
		doc.setField("resourceType", entry.getResourceType().name());

		// profile
		if (entry.getLocalMetadataURI() != null) {
			Set<IRI> profilePreds = new HashSet<>();
			profilePreds.add(valueFactory.createIRI("http://entryscape.com/terms/entityType"));
			profilePreds.add(valueFactory.createIRI("http://entrystore.org/terms/profile"));
			for (String profileURI : EntryUtil.getResourceValues(entryGraph, entry.getLocalMetadataURI(), profilePreds)) {
				doc.setField("profile", profileURI);
				break; // we only need the first match
			}
		} else {
			log.warn("Local metadata URI of entry is null: {}", entry.getEntryURI());
		}

		// project type
		// FIXME this can be optimized by not checking the context for every single indexed entry, perhaps we can
		// remember the context status for a limited amount of time instead of fetching and querying the context graph
		// every time
		Model graphWithProjectType;
		URI resourceUriForProjectType;
		if (GraphType.Context.equals(entry.getGraphType())) {
			graphWithProjectType = entryGraph;
			resourceUriForProjectType = resourceURI;
		} else {
			Entry contextEntry = entry.getContext().getEntry();
			graphWithProjectType = contextEntry.getGraph();
			resourceUriForProjectType = contextEntry.getResourceURI();
		}

		for (String projectTypeURI : EntryUtil.getResourceValues(
				graphWithProjectType,
				resourceUriForProjectType,
				Collections.singleton(valueFactory.createIRI("http://entryscape.com/terms/projectType")))) {
			doc.setField("projectType", projectTypeURI);
			break; // we only need the first match
		}

		// creator
		URI creator = entry.getCreator();
		if (creator != null) {
			doc.setField("creator", creator.toString());
		}

		// contributors
		for (URI c : entry.getContributors()) {
			doc.addField("contributors", c.toString());
		}

		// lists
		for (URI l : entry.getReferringListsInSameContext()) {
			doc.addField("lists", l.toString());
		}

		// ACL: admin, metadata r/w, resource r/w
		try {
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.Administer)) {
				doc.addField("acl.admin", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata)) {
				doc.addField("acl.metadata.r", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.WriteMetadata)) {
				doc.addField("acl.metadata.rw", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.ReadResource)) {
				doc.addField("acl.resource.r", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.WriteResource)) {
				doc.addField("acl.resource.rw", p.toString());
			}
		} catch (IllegalArgumentException iae) {
			log.warn("Unable to index ACL for entry {}: {}", entry.getEntryURI().toString(), iae.getMessage());
		}

		// status
		URI status = entry.getStatus();
		if (status != null) {
			doc.setField("status", status.toString());
		}

		// titles
		Map<String, Set<String>> titles = EntryUtil.getTitles(entry);
		if (titles != null && !titles.isEmpty()) {
			storeLiteralsWithLanguages(doc, titles, "title");
		}

		String firstName = EntryUtil.getFirstName(entry);
		String lastName = EntryUtil.getLastName(entry);
		String name = "";
		if (firstName != null) {
			name += firstName;
		}
		if (lastName != null) {
			name += " " + lastName;
		}
		if (!name.isEmpty()) {
			doc.addField("title", name);
		}

		// user name
		if (GraphType.User.equals(entry.getGraphType())) {
			User user = (org.entrystore.User) entry.getResource();
			if (user != null) {
				String username = user.getName();
				if (username != null) {
					doc.addField("username", username);
				}
			} else {
				log.warn("User resource of {} is null", entry.getEntryURI().toString());
			}
		}

		// context name
		if (GraphType.Context.equals(entry.getGraphType()) || GraphType.SystemContext.equals(entry.getGraphType())) {
			String contextName = rm.getContextManager().getName(entry.getResource().getURI());
			if (contextName != null) {
				doc.addField("contextname", contextName);
			}
		}

		// description
		Map<String, Set<String>> descriptions = EntryUtil.getDescriptions(entry);
		if (descriptions != null && !descriptions.isEmpty()) {
			storeLiteralsWithLanguages(doc, descriptions, "description");
		}

		// tag.literal[.*]
		Map<String, Set<String>> tagLiterals = EntryUtil.getTagLiterals(entry);
		if (tagLiterals != null) {
			storeLiteralsWithLanguages(doc, tagLiterals, "tag.literal");
		}

		// tag.uri
		for (String s : EntryUtil.getTagResources(entry)) {
			doc.addField("tag.uri", s);
		}

		// language of the resource
		String dcLang = EntryUtil.getLabel(mdGraph, resourceURI, valueFactory.createIRI(NS.dc + "language"), null);
		if (dcLang != null) {
			doc.addField("lang", dcLang);
		}
		String dctLang = EntryUtil.getLabel(mdGraph, resourceURI, valueFactory.createIRI(NS.dcterms + "language"), null);
		if (dctLang != null) {
			doc.addField("lang", dctLang);
		}

		// email (foaf:mbox)
		String email = EntryUtil.getEmail(entry);
		if (email != null) {
			doc.addField("email", email);
		}

		// publicly viewable metadata?
		boolean guestReadable = false;
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		try {
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
			guestReadable = true;
		} catch (AuthorizationException ignored) {
		} catch (IllegalArgumentException iae) {
			log.warn(iae.getMessage());
		}
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		doc.setField("public", guestReadable);

		addGenericMetadataFields(doc, mdGraph, false);

		if (related) {
			addRelatedFields(doc, entry);
		}

		// Full text extraction using Apache Tika
        /* if (extractFulltext && EntryType.Local.equals(entry.getEntryType())
				&& ResourceType.InformationResource.equals(entry.getResourceType())
				&& entry.getResource() instanceof Data) {
			Data d = (Data) entry.getResource();
			File f = d.getDataFile();
			if (f != null && f.exists()) {
				String textContent = extractFulltext(f);
				if (textContent != null) {
					doc.addField("fulltext", textContent);
				}
			}
		} */

		return doc;
	}

	private void addGenericMetadataFields(SolrInputDocument doc, Model metadata, boolean related) {
		if (doc == null || metadata == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Graph must be null");
		}

		String prefix = "";
		if (related) {
			prefix = "related.";
		}

		// All subject, predicates and objects in the metadata graph
		//
		// We also provide an index for all predicate-object tuples, stored in dynamic fields.
		for (Statement s : metadata) {
			// predicate
			String predString = s.getPredicate().stringValue();
			String predMD5Trunc8 = Hashing.md5(predString).substring(0, 8);

			// object
			if (s.getObject() instanceof IRI) {
				String objString = s.getObject().stringValue();
				if (!related) {
					addFieldValueOnce(doc,prefix + "metadata.object.uri", objString);
				}

				// predicate value is included in the parameter name, the object value is the field value
				addFieldValueOnce(doc,prefix + "metadata.predicate.uri." + predMD5Trunc8, objString);
			} else if (s.getObject() instanceof Literal l) {
				if (!related) {
					if (MetadataUtil.isStringLiteral(l)) { // we only index plain literals (human-readable text)
						addFieldValueOnce(doc,prefix + "metadata.object.literal", l.getLabel());
					}
				}

				// predicate value is included in the parameter name, the object value is the field value
				addFieldValueOnce(doc,prefix + "metadata.predicate.literal_s." + predMD5Trunc8, l.getLabel());

				// special handling of integer values, to be used for e.g. sorting
				if (MetadataUtil.isIntegerLiteral(l)) {
					try {
						// it's a single-value field so we call setField instead of addField just in case there should be
						doc.setField(prefix + "metadata.predicate.integer." + predMD5Trunc8, l.longValue());
					} catch (NumberFormatException nfe) {
						log.debug("Unable to index integer literal: {}. (Subject: {}, Predicate: {}, Object: {})", nfe.getMessage(), s.getSubject(), predString, l.getLabel());
					}
				}

				if (MetadataUtil.isDateLiteral(l)) {
					try {
						doc.setField(prefix + "metadata.predicate.date." + predMD5Trunc8, dateToSolrDateString(l.calendarValue()));
					} catch (IllegalArgumentException iae) {
						log.debug("Unable to index date literal: {}. (Subject: {}, Predicate: {}, Object: {})", iae.getMessage(), s.getSubject(), predString, l.getLabel());
					}
				}
			}
		}
	}

	private void addRelatedFields(SolrInputDocument doc, Entry entry) {
		if (doc == null || entry == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Entry must be null");
		}

		Set<Context> contexts = new HashSet<>();
		if (relatedContainsGlobal) {
			// TODO there is room for optimization, the contexts are loaded now once per indexed entry.
			//  possible solution: provide getter method and load only if set is null; set to null when there are
			//  relevant changes, e.g. context removed or added (ContextManager needs to tell SolrSearchIndex that
			//  contexts have changed)
			ContextManager cm = entry.getRepositoryManager().getContextManager();
			for (URI contextURI : cm.getEntries()) {
				Context c = cm.getContext(contextURI);
				if (c instanceof RegularContext) {
					contexts.add(c);
				}
			}
		}

		Set<Entry> relatedEntries = new HashSet<>();
		for (IRI relProp : relatedProperties.keySet()) {
			List<String> relatedURIs = EntryUtil.getResourceValues(entry, Collections.singleton(relProp));
			if (relatedURIs.isEmpty()) {
				continue;
			}
			if (relatedContainsGlobal && relatedProperties.get(relProp)) {
				for (Context context : contexts) {
					for (String relEntURI : relatedURIs) {
						relatedEntries.addAll(context.getByResourceURI(URI.create(relEntURI)));
					}
				}
			} else {
				for (String relEntURI : relatedURIs) {
					relatedEntries.addAll(entry.getContext().getByResourceURI(URI.create(relEntURI)));
				}
			}
		}

		if (!relatedEntries.isEmpty()) {
			Set<URI> mainEntryACL = entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata);
			for (Entry relE : relatedEntries) {
				if (mainEntryACL.equals(relE.getAllowedPrincipalsFor(AccessProperty.ReadMetadata))) {
					log.debug("Adding " + relE.getEntryURI() + " to related property index of " + entry.getEntryURI());
					addGenericMetadataFields(doc, relE.getMetadataGraph(), true);
				} else {
					log.debug("ACLs of " + entry.getEntryURI() + " and " + relE.getEntryURI() + " do not match, not adding to related property index");
				}
			}
		}
	}

	private void addFieldValueOnce(SolrInputDocument doc, String name, Object value) {
		Collection fieldValues = doc.getFieldValues(name);
		if (fieldValues == null || !fieldValues.contains(value)) {
			doc.addField(name, value);
		}
	}

	private String dateToSolrDateString(XMLGregorianCalendar c) {
		if (c.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
			c.setTimezone(0);
		}
		return solrDateFormatter.format(c.toGregorianCalendar().getTime());
	}

	public void postEntry(Entry entry) {
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			URI entryURI = entry.getEntryURI();
			synchronized (postQueue) {
				if (postQueue.getIfPresent(entryURI) != null) {
					log.debug("Entry {} already exists in post queue, attempting replacement", entryURI);
				}
				if (!entry.isDeleted() && !entry.getContext().isDeleted()) {
					log.info("Adding document to Solr post queue: {}", entryURI);
					try {
						postQueue.put(entryURI, constructSolrInputDocument(entry, extractFulltext));
					} catch (Exception e) {
						log.error("Not indexing {} due to error: {}", entryURI, e.getMessage());
					}
				} else {
					log.debug("Not adding deleted entry to post queue: {}", entryURI);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public void removeEntry(Entry entry) {
		URI entryURI = entry.getEntryURI();

		synchronized (postQueue) {
			// we make sure that the entry is not added again after deletion
			// if the queues are handled at different times
			postQueue.invalidate(entryURI);
		}

		synchronized (deleteQueue) {
			log.info("Adding entry to Solr delete queue: " + entryURI);
			deleteQueue.add(entryURI);
		}

		// if entry is a context, also remove all entries inside
		if (GraphType.Context.equals(entry.getGraphType())) {
			clearSolrIndex(solrServer, null, entry);
		}
	}

	private long sendQueryForEntryURIs(SolrQuery query, Set<URI> result, List<FacetField> facetFields, SolrClient solrServer, int offset, int limit) {
		if (query == null) {
			throw new IllegalArgumentException("Query object must not be null");
		}

		if (offset > -1) {
			query.setStart(offset);
		}
		if (limit > -1) {
			query.setRows(limit);
		}

		// We only need the "uri" field in the response,
		// so we skip the rest (default is "*")
		query.setFields("uri");

		long hits = -1;
		QueryResponse r;
		try {
			r = solrServer.query(query);
			r.getElapsedTime();
			if (r.getFacetFields() != null) {
				facetFields.addAll(r.getFacetFields());
			}
			SolrDocumentList docs = r.getResults();
			hits = docs.getNumFound();
			for (SolrDocument solrDocument : docs) {
				if (solrDocument.containsKey("uri")) {
					String uri = (String) solrDocument.getFieldValue("uri");
					if (uri != null) {
						result.add(URI.create(uri));
					}
				}
			}
			log.debug("Query time: {} ms, elapsed time: {} ms", r.getQTime(), r.getElapsedTime());
		} catch (SolrServerException | IOException e) {
			if (e instanceof SolrServerException && ((SolrServerException) e).getRootCause() instanceof IllegalArgumentException) {
				log.info(e.getMessage());
			} else {
				log.error(e.getMessage());
			}
		}

		return hits;
	}

	public QueryResult sendQuery(SolrQuery query) throws SolrException {
		Set<URI> entries = new LinkedHashSet<>();
		Set<Entry> result = new LinkedHashSet<>();
		long hits = -1;
		long inaccessibleHits = 0;
		int limit = query.getRows();
		int offset = query.getStart();
		List<FacetField> facetFields = new ArrayList<>();
		query.setIncludeScore(true);
		int resultFillIteration = 0;
		do {
			if (resultFillIteration++ > 0) {
				// We have a small limit and we don't get enough results with permissive ACL per iteration,
				// so we need to increase the result size windows, but not the result limit itself
				// (i.e., we only change the rows towards Solr, but not the query limit of EntryStore.
				// We only need to do this once when resultFillIteration equals 1.
				if (resultFillIteration == 1 && limit <= 10) {
					query.setRows(100);
				}
				if (resultFillIteration > 10) {
					log.warn("Breaking after 10 result fill interations to prevent too many loops");
					break;
				}
				offset += Math.min(limit, 50);
				log.warn("Increasing offset to " + offset + " in an attempt to fill the result limit");
			}
			hits = sendQueryForEntryURIs(query, entries, facetFields, solrServer, offset, -1);
			Date before = new Date();
			for (URI uri : entries) {
				try {
					Entry entry = rm.getContextManager().getEntry(uri);
					if (entry != null) {
						if (entry.isDeleted()) {
							log.warn("Deleted entry {} is still in Solr index, removing it now", uri);
							removeEntry(entry);
							throw new IllegalStateException("Cannot return deleted entry in search result: " + uri);
						}
						PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
						// If linkReference or reference to an entry in the same repository
						// check that the referenced metadata is accessible.
						if ((entry.getEntryType() == EntryType.Reference || entry.getEntryType() == EntryType.LinkReference)
								&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper) {
							Entry refEntry = entry.getRepositoryManager().getContextManager().getEntry(entry.getExternalMetadataURI());
							if (refEntry != null) {
								pm.checkAuthenticatedUserAuthorized(refEntry, AccessProperty.ReadMetadata);
							} else {
								log.error("Entry {} contains reference to non-existing resource.", entry.getEntryURI());
							}
						} else {
							// Check that the local metadata is accessible.
							pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
						}
						result.add(entry);
						if (result.size() == limit) {
							// we have enough results
							break;
						}
					}
				} catch (AuthorizationException | IllegalStateException e) {
					inaccessibleHits++;
				}
			}
			log.info("Entry fetching took " + (new Date().getTime() - before.getTime()) + " ms");
		} while ((limit > result.size()) && (hits > (offset + limit)));

		long adjustedHitCount = hits - inaccessibleHits;

		// We prevent possible information leakage (i.e., "Can we get to know whether a resource
		// with a certain name exists even though we are not allowed to access it?") by manually
		// setting the hit count to zero in certain conditions. Should protect against malicious
		// probing requests.
		//
		// Test if the condition covers to much and add "offset == 0 &&" if necessary
		if (result.isEmpty() && hits > 0) {
			adjustedHitCount = 0;
		}

		return new QueryResult(result, adjustedHitCount, facetFields);
	}

	public SolrDocument fetchDocument(String uri) {
		try {
			SolrQuery q = new SolrQuery("uri:" + ClientUtils.escapeQueryChars(uri));
			q.setStart(0);
			q.setRows(1);
			QueryResponse r = solrServer.query(q);
			SolrDocumentList docs = r.getResults();
			if (!docs.isEmpty()) {
				return docs.getFirst();
			}
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public static String extractFulltext(File f) {
		return null;

		// FIXME this method works but is deactivated. the needed apache tika
		// framework has many dependencies so it is not activated in the pom.xml
		// of scam-core for now. please activate it there before enabling the
		// following code again.

		/*
		 * InputStream stream = null; String textContent = null; String mimeType
		 * = null; try { TikaConfig tc = TikaConfig.getDefaultConfig();
		 * InputStream mimeIS = null; try { mimeIS = Files.newInputStream(f.toPath());
		 * mimeType = tc.getMimeRepository().getMimeType(mimeIS).getName(); }
		 * finally { if (mimeIS != null) { mimeIS.close(); } }
		 *
		 * if (mimeType != null) { stream = new BufferedInputStream(
		 * Files.newInputStream(f.toPath())); Parser parser = tc.getParser(mimeType); if
		 * (parser != null) { ContentHandler handler = new BodyContentHandler();
		 * try { log.info("Parsing document with MIME type " + mimeType + ": " +
		 * f.toString()); parser.parse(stream, handler, new Metadata(), new
		 * ParseContext()); textContent = handler.toString(); } catch (Exception
		 * e) { log.error("Unable to parse document: " + e.getMessage()); } }
		 * else { log.warn("Unable to detect parser for MIME type " + mimeType);
		 * } } else { log.warn("Unable to detect the MIME type"); } } catch
		 * (IOException e) { log.error(e.getMessage()); } finally { try { if
		 * (stream != null) { stream.close(); } } catch (IOException e) {
		 * log.error(e.getMessage()); } } return textContent;
		 */
	}

}
