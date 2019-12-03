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
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.SearchIndex;
import org.entrystore.impl.LocalMetadataWrapper;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentMap;


/**
 * @author Hannes Ebner
 */
public class SolrSearchIndex implements SearchIndex {

	private static Logger log = LoggerFactory.getLogger(SolrSearchIndex.class);

	private static final int BATCH_SIZE_ADD = 500;

	private static final int BATCH_SIZE_DELETE = 100;

	private volatile boolean reindexing = false;

	private boolean extractFulltext = false;

	private boolean related = false;

	private List<org.openrdf.model.URI> relatedProperties = null;

	private RepositoryManager rm;

	private final SolrClient solrServer;

	private Thread documentSubmitter;

	private final Cache<URI, SolrInputDocument> postQueue = Caffeine.newBuilder().build();

	private final Queue<URI> deleteQueue = Queues.newConcurrentLinkedQueue();

	private SimpleDateFormat solrDateFormatter;

	public class SolrInputDocumentSubmitter extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				postQueue.cleanUp();
				int batchCount = 0;

				if (postQueue.estimatedSize() > 0 || deleteQueue.size() > 0) {

					if (deleteQueue.size() > 0) {
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
							try {
								log.info("Sending request to delete " + batchCount + " entries from Solr, " + deleteQueue.size() + " entries remaining in delete queue");
								delReq.process(solrServer);
							} catch (SolrServerException sse) {
								log.error(sse.getMessage(), sse);
							} catch (IOException ioe) {
								log.error(ioe.getMessage(), ioe);
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
							log.info("Sending " + addReq.getDocuments().size() + " entries to Solr, " + postQueue.estimatedSize() + " entries remaining in post queue");
							addReq.process(solrServer);
						} catch (SolrServerException sse) {
							log.error(sse.getMessage(), sse);
						} catch (IOException ioe) {
							log.error(ioe.getMessage(), ioe);
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

	public SolrSearchIndex(RepositoryManager rm, SolrClient solrServer) {
		solrDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		solrDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.rm = rm;
		this.solrServer = solrServer;
		extractFulltext = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_EXTRACT_FULLTEXT, "off"));
		related = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_RELATED, "off"));
		if (related) {
			List<String> relPropsSetting = rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<String>());
			if (relPropsSetting.isEmpty()) {
				related = false;
			} else {
				relatedProperties = new ArrayList<>();
				for (String relProp : rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<String>())) {
					relatedProperties.add(rm.getValueFactory().createURI(relProp));
				}
			}
		}

		documentSubmitter = new SolrInputDocumentSubmitter();
		documentSubmitter.start();
	}

	public void shutdown() {
		if (documentSubmitter != null) {
			documentSubmitter.interrupt();
		}
	}

	public void clearSolrIndex(SolrClient solrServer) {
		UpdateRequest req = new UpdateRequest();
		req.deleteByQuery("*:*");
		try {
			req.process(solrServer);
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	public void clearSolrIndexFromContextEntries(SolrClient solrServer, Entry contextEntry) {
		UpdateRequest req = new UpdateRequest();
		req.deleteByQuery("context:" + ClientUtils.escapeQueryChars(contextEntry.getResourceURI().toString()));
		try {
			req.process(solrServer);
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	/**
	 * Reindexes the Solr index. Does not return before the process is
	 * completed. All subsequent calls to this method are ignored until other
	 * eventually running reindexing processes are completed.
	 */
	public void reindex() {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return;
		}

		if (reindexing) {
			log.warn("Solr is already being reindexed: ignoring additional reindexing request");
			return;
		} else {
			reindexing = true;
		}

		try {
			clearSolrIndex(solrServer);

			PrincipalManager pm = rm.getPrincipalManager();
			URI currentUser = pm.getAuthenticatedUserURI();
			try {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				ContextManager cm = rm.getContextManager();
				Set<URI> contexts = cm.getEntries();

				for (URI contextURI : contexts) {
					String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
					Context context = cm.getContext(id);
					if (context != null) {
						Set<URI> entries = context.getEntries();
						for (URI entryURI : entries) {
							if (entryURI != null) {
								Entry entry = cm.getEntry(entryURI);
								if (entry == null) {
									continue;
								}
								synchronized (postQueue) {
									log.info("Adding document to Solr post queue: " + entryURI);
									postQueue.put(entryURI, constructSolrInputDocument(entry, extractFulltext));
								}
							}
						}
					}
				}
			} finally {
				pm.setAuthenticatedUserURI(currentUser);
			}
		} finally {
			reindexing = false;
		}
	}

	public SolrInputDocument constructSolrInputDocument(Entry entry, boolean extractFulltext) {
		Graph mdGraph = entry.getMetadataGraph();
		URI resourceURI = entry.getResourceURI();

		SolrInputDocument doc = new SolrInputDocument();

		// URI
		doc.setField("uri", entry.getEntryURI().toString());

		// resource URI
		doc.setField("resource", resourceURI.toString());

		// resource URI of the surrounding context
		doc.setField("context", entry.getContext().getEntry().getResourceURI().toString());

		// RDF type
		Iterator<Statement> rdfTypeE = entry.getGraph().match(new URIImpl(resourceURI.toString()), RDF.TYPE, null);
		while (rdfTypeE.hasNext()) {
			doc.addField("rdfType", rdfTypeE.next().getObject().stringValue());
		}
		Iterator<Statement> rdfTypeM = mdGraph.match(new URIImpl(resourceURI.toString()), RDF.TYPE, null);
		while (rdfTypeM.hasNext()) {
			doc.addField("rdfType", rdfTypeM.next().getObject().stringValue());
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

		// creator
		URI creator = entry.getCreator();
		if (creator != null) {
			doc.setField("creator", creator.toString());
		}

		// contributors
		doc.addField("contributors", entry.getContributors());

		// lists
		doc.addField("lists", entry.getReferringListsInSameContext());

		// ACL: admin, metadata r/w, resource r/w
		doc.addField("acl.admin", entry.getAllowedPrincipalsFor(AccessProperty.Administer));
		doc.addField("acl.metadata.r", entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata));
		doc.addField("acl.metadata.rw", entry.getAllowedPrincipalsFor(AccessProperty.WriteMetadata));
		doc.addField("acl.resource.r", entry.getAllowedPrincipalsFor(AccessProperty.ReadResource));
		doc.addField("acl.resource.rw", entry.getAllowedPrincipalsFor(AccessProperty.WriteResource));

		//Status
		URI status = entry.getStatus();
		if (status != null) {
			doc.setField("status", status.toString());
		}

		// titles
		Map<String, String> titles = EntryUtil.getTitles(entry);
		if (titles != null && titles.size() > 0) {
			Set<String> langs = new HashSet<>();
			for (String title : titles.keySet()) {
				doc.addField("title", title);
				// we also store title.{lang} as dynamic field to be able to
				// sort after titles in a specific language
				String lang = titles.get(title);
				if (lang == null) {
					lang = "nolang";
				}
				// we only want one title per language, otherwise sorting will not work
				if (!langs.contains(lang)) {
					doc.addField("title." + lang, title);
					langs.add(lang);
				}
			}
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
		if (name.length() > 0) {
			doc.addField("title", name);
		}

		// description
		Map<String, String> descriptions = EntryUtil.getDescriptions(entry);
		if (descriptions != null && descriptions.size() > 0) {
			for (String description : descriptions.keySet()) {
				doc.addField("description", description);
				String lang = descriptions.get(description);
				if (lang != null) {
					doc.addField("description." + lang, description);
				}
			}
		}

		// tag.literal[.*]
		Map<String, String> tagLiterals = EntryUtil.getTagLiterals(entry);
		if (tagLiterals != null) {
			for (String tag : tagLiterals.keySet()) {
				doc.addField("tag.literal", tag);
				String lang = tagLiterals.get(tag);
				if (lang != null) {
					doc.addField("tag.literal." + lang, tag);
				}
			}
		}

		// tag.uri
		Iterator<String> tagResources = EntryUtil.getTagResources(entry).iterator();
		while (tagResources.hasNext()) {
			doc.addField("tag.uri", tagResources.next());
		}

		// language of the resource
		String dcLang = EntryUtil.getLabel(mdGraph, resourceURI, new URIImpl(NS.dc + "language"), null);
		if (dcLang != null) {
			doc.addField("lang", dcLang);
		}
		String dctLang = EntryUtil.getLabel(mdGraph, resourceURI, new URIImpl(NS.dcterms + "language"), null);
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
		} catch (AuthorizationException ae) {
		}
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		doc.setField("public", guestReadable);

		addGenericMetadataFields(doc, mdGraph, false);

		if (related) {
			addRelatedFields(doc, entry);
		}

		// Full text extraction using Apache Tika
		if (extractFulltext && EntryType.Local.equals(entry.getEntryType())
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
		}

		return doc;
	}

	private void addGenericMetadataFields(SolrInputDocument doc, Graph metadata, boolean related) {
		if (doc == null || metadata == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Graph must be null");
		}

		String prefix = new String();
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
			if (s.getObject() instanceof org.openrdf.model.URI) {
				String objString = s.getObject().stringValue();
				if (!related) {
					addFieldValueOnce(doc,prefix + "metadata.object.uri", objString);
				}

				// predicate value is included in the parameter name, the object value is the field value
				addFieldValueOnce(doc,prefix + "metadata.predicate.uri." + predMD5Trunc8, objString);
			} else if (s.getObject() instanceof Literal) {
				Literal l = (Literal) s.getObject();
				if (!related) {
					if (l.getDatatype() == null) { // we only index plain literals (human-readable text)
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
						log.debug("Unable to index integer literal: " + nfe.getMessage() + ". (Subject: " + s.getSubject() + ", Predicate: " + predString + ", Object: " + l.getLabel() + ")");
					}
				}

				if (MetadataUtil.isDateLiteral(l)) {
					try {
						doc.setField(prefix + "metadata.predicate.date." + predMD5Trunc8, dateToSolrDateString(l.calendarValue()));
					} catch (IllegalArgumentException iae) {
						log.debug("Unable to index date literal: " + iae.getMessage() + ". (Subject: " + s.getSubject() + ", Predicate: " + predString + ", Object: " + l.getLabel() + ")");
					}
				}
			}
		}
	}

	private void addRelatedFields(SolrInputDocument doc, Entry entry) {
		if (doc == null || entry == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Entry must be null");
		}

		Context c = entry.getContext();
		Set mainEntryACL = entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata);
		List<String> relatedURIs = EntryUtil.getResourceValues(entry, new HashSet(relatedProperties));

		// we remove all resources that are outside of this instance
		// relatedURIs.removeIf(e -> !e.startsWith(rm.getRepositoryURL().toString()));
		Set<Entry> relatedEntries = new HashSet();
		for (String relEntURI : relatedURIs) {
			relatedEntries.addAll(c.getByResourceURI(URI.create(relEntURI)));
		}

		for (Entry relE : relatedEntries) {
			if (mainEntryACL.equals(relE.getAllowedPrincipalsFor(AccessProperty.ReadMetadata))) {
				log.debug("Adding " + relE.getEntryURI() + " to related property index of " + entry.getEntryURI());
				addGenericMetadataFields(doc, relE.getMetadataGraph(), true);
			} else {
				log.debug("ACLs of " + entry.getEntryURI() + " and " + relE.getEntryURI() + " do not match, not adding to related property index");
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
				log.info("Adding document to Solr post queue: " + entryURI);
				if (postQueue.getIfPresent(entryURI) != null) {
					log.debug("Entry " + entryURI + " already exists in post queue");
				}
				postQueue.put(entryURI, constructSolrInputDocument(entry, extractFulltext));
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public void removeEntry(Entry entry) {
		URI entryURI = entry.getEntryURI();
		synchronized (deleteQueue) {
			log.info("Adding entry to Solr delete queue: " + entryURI);
			deleteQueue.add(entryURI);
		}
		// if entry is a context, also remove all entries inside
		if (GraphType.Context.equals(entry.getGraphType())) {
			clearSolrIndexFromContextEntries(solrServer, entry);
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

		Date before = new Date();
		QueryResponse r = null;
		try {
			r = solrServer.query(query);
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
		} catch (SolrServerException | IOException e) {
			if (e instanceof SolrServerException && ((SolrServerException) e).getRootCause() instanceof IllegalArgumentException) {
				log.info(e.getMessage());
			} else {
				log.error(e.getMessage());
			}
		}
		log.info("Solr query took " + (new Date().getTime() - before.getTime()) + " ms");

		return hits;
	}

	public QueryResult sendQuery(SolrQuery query) throws SolrException {
		Set<URI> entries = new LinkedHashSet<>();
		Set<Entry> result = new LinkedHashSet<>();
		long hits = -1;
		long inaccessibleHits = 0;
		int limit = query.getRows();
		int offset = query.getStart();
		List<FacetField> facetFields = new ArrayList();
		query.setIncludeScore(true);
		query.setRequestHandler("dismax");
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
				if (limit <= 50) {
					offset += limit;
				} else {
					offset += 50;
				}
				log.warn("Increasing offset to " + offset + " in an attempt to fill the result limit");
			}
			hits = sendQueryForEntryURIs(query, entries, facetFields, solrServer, offset, -1);
			Date before = new Date();
			for (URI uri : entries) {
				try {
					Entry entry = rm.getContextManager().getEntry(uri);
					if (entry != null) {
						PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
						// If linkReference or reference to a entry in the same
						// repository
						// check that the referenced metadata is accessible.
						if ((entry.getEntryType() == EntryType.Reference || entry.getEntryType() == EntryType.LinkReference)
								&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper) {
							Entry refEntry = entry.getRepositoryManager().getContextManager()
									.getEntry(entry.getExternalMetadataURI());
							pm.checkAuthenticatedUserAuthorized(refEntry, AccessProperty.ReadMetadata);
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
				} catch (AuthorizationException ae) {
					inaccessibleHits++;
					continue;
				}
			}
			log.info("Entry fetching took " + (new Date().getTime() - before.getTime()) + " ms");
		} while ((limit > result.size()) && (hits > (offset + limit)));

		return new QueryResult(result, (hits - inaccessibleHits), facetFields);
	}

	public SolrDocument fetchDocument(String uri) {
		try {
			SolrQuery q = new SolrQuery("uri:" + ClientUtils.escapeQueryChars(uri));
			q.setStart(0);
			q.setRows(1);
			QueryResponse r = solrServer.query(q);
			SolrDocumentList docs = r.getResults();
			if (!docs.isEmpty()) {
				return docs.get(0);
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
		 * InputStream mimeIS = null; try { mimeIS = new FileInputStream(f);
		 * mimeType = tc.getMimeRepository().getMimeType(mimeIS).getName(); }
		 * finally { if (mimeIS != null) { mimeIS.close(); } }
		 * 
		 * if (mimeType != null) { stream = new BufferedInputStream(new
		 * FileInputStream(f)); Parser parser = tc.getParser(mimeType); if
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