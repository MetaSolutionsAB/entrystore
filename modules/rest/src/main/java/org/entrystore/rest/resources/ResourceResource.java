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

package org.entrystore.rest.resources;

import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndContentImpl;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndEntryImpl;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.ListImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.impl.StringResource;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.TokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.ieee.ltsc.lom.LOM.Technical.Location;
import org.ieee.ltsc.lom.LOMUtil;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.rio.RDFFormat;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Uniform;
import org.restlet.data.Cookie;
import org.restlet.data.Disposition;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.FileRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This class is the resource for entries. 
 * 
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class ResourceResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ResourceResource.class);

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()));
	}

	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/resource/{entry-id}
	 * </pre>
	 * 
	 * @return The Representation as JSON
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			Representation result = null;

			// the check for resource safety is necessary to avoid an implicit
			// getMetadata() in the case of a PUT on (not yet) existant metadata
			// - this is e.g. the case if conditional requests are issued 
			if (Method.GET.equals(getRequest().getMethod())) {
				result = getResource();
			} else {
				result = new EmptyRepresentation();
			}

			if (result != null) {
				Date lastMod = entry.getModifiedDate();
				if (lastMod != null) {
					result.setModificationDate(lastMod);
				}
			}

			return result;
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		if (entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (preferredMediaType == null) {
			preferredMediaType = MediaType.APPLICATION_RDF_XML;
		}

		try {
			modifyResource(preferredMediaType);
		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		try {
			if (parameters.containsKey("method")) {
				if ("delete".equalsIgnoreCase(parameters.get("method"))) {
					removeRepresentation();
				} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
					storeRepresentation(r);
				}
			} else if (entry.getGraphType().equals(GraphType.List) &&
					parameters.containsKey("import") &&
					MediaType.APPLICATION_ZIP.equals(getRequestEntity().getMediaType())) {
				getResponse().setStatus(importFromZIP(getRequestEntity()));
			} else if (entry.getGraphType().equals(GraphType.List) &&
					parameters.containsKey("moveEntry") &&
					parameters.containsKey("fromList")) {
				// POST 3/resource/45?moveEntry=2/entry/34&fromList=2/resource/67
				ListImpl dest = (ListImpl) this.entry.getResource();
				String movableEntryString = parameters.get("moveEntry");
				String movableEntrySourceString = parameters.get("fromList");
				boolean removeAll = parameters.get("removeAll") != null;

				String baseURI = getRM().getRepositoryURL().toString();
				if (!baseURI.endsWith("/")) {
					baseURI += "/";
				}

				// Entry URI of the Entry to be moved
				URI movableEntry = movableEntryString.startsWith("http://") ? URI.create(movableEntryString) : URI.create(baseURI + movableEntryString);
				// Resource URI of the source List
				
				URI movableEntrySource = null;
				if (movableEntrySourceString != null) {
					movableEntrySource = movableEntrySourceString.startsWith("http://") ? URI.create(movableEntrySourceString) : URI.create(baseURI + movableEntrySourceString);
				}

				Entry movedEntry = null;
				String error = null;
				try {
					movedEntry = dest.moveEntryHere(movableEntry, movableEntrySource, removeAll);
				} catch (QuotaException qe) {
					error = qe.getMessage();
					log.warn(qe.getMessage());
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				} catch (IOException ioe) {
					error = ioe.getMessage();
					log.error(ioe.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
				if (error != null) {
					JSONObject jsonError = new JSONObject();
					try {
						jsonError.put("error", error);
					} catch (JSONException e) {
						log.error(e.getMessage());
					}
					getResponse().setEntity(new JsonRepresentation(jsonError));
					return;
				}
				
				JSONObject result = new JSONObject();
				try {
					result.put("entryURI", movedEntry.getEntryURI());
				} catch (JSONException e) {
					log.error(e.getMessage());
				}
				getResponse().setEntity(new JsonRepresentation(result));
				getResponse().setStatus(Status.SUCCESS_OK);
			} 
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentation() {
		if (entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		}
		
		try {
			if ((EntryType.Link.equals(entry.getEntryType()) ||
					EntryType.Reference.equals(entry.getEntryType()) ||
					EntryType.LinkReference.equals(entry.getEntryType()))
					&& "true".equalsIgnoreCase(parameters.get("proxy"))) {
				final Response delResponse = deleteRemoteResource(entry.getResourceURI().toString(), 0);
				if (delResponse != null) {
					getResponse().setEntity(delResponse.getEntity());
					getResponse().setStatus(delResponse.getStatus());
					getResponse().setOnSent(new Uniform() {
						public void handle(Request request, Response response) {
							try {
								delResponse.release();
							} catch (Exception e) {
								log.error(e.getMessage());
							}
						}
					});
				} else {
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
			} else {
				deleteLocalResource();
			}
		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * Deletes the resource if the entry has any.
	 */
	private void deleteLocalResource() {
		/*
		 * List
		 */
		if (entry.getGraphType() == GraphType.List) {
			ListImpl l = (ListImpl) entry.getResource();
			if (parameters.containsKey("recursive")) {
				l.removeTree();
			} else {
				l.setChildren(new Vector<URI>());
			}
		}

		/*
		 * None
		 */
		if (entry.getGraphType() == GraphType.None ) {
			if(entry.getResourceType() == ResourceType.InformationResource) {
				Data data = (Data) entry.getResource();
				if (data.delete() == false) {
					log.error("Unable to delete resource of entry " + entry.getEntryURI());
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
					getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorUnknownKind));
				}
			}
		}
	}

	private Response deleteRemoteResource(String url, int loopCount) {
		if (loopCount > 10) {
			log.warn("More than 10 redirect loops detected, aborting");
			return null;
		}

		Client client = new Client(Protocol.HTTP);
		client.setContext(new org.restlet.Context());
		client.getContext().getParameters().add("connectTimeout", "10000");
		client.getContext().getParameters().add("readTimeout", "10000");
		client.getContext().getParameters().set("socketTimeout", "10000");
		client.getContext().getParameters().set("socketConnectTimeoutMs", "10000");
		log.info("Initialized HTTP client for proxy request to delete remote resource");

		Request request = new Request(Method.DELETE, url);
		request.getClientInfo().setAcceptedMediaTypes(getRequest().getClientInfo().getAcceptedMediaTypes());
		Response response = client.handle(request);

		if (response.getStatus().isRedirection()) {
			Reference ref = response.getLocationRef();
			response.getEntity().release();
			if (ref != null) {
				String refURL = ref.getIdentifier();
				log.info("Request redirected to " + refURL);
				return deleteRemoteResource(refURL, ++loopCount);
			}
		}

		if (response.getEntity() != null &&
				response.getEntity().getLocationRef() != null &&
				response.getEntity().getLocationRef().getBaseRef() == null) {
			response.getEntity().getLocationRef().setBaseRef(url.substring(0, url.lastIndexOf("/")+1));
		}

		return response;
	}

	protected boolean isFile(Entry entry) {
		if (entry != null) {
			return EntryType.Local.equals(entry.getEntryType()) &&
				GraphType.None.equals(entry.getGraphType()) &&
				ResourceType.InformationResource.equals(entry.getResourceType());
		} else {
			return false;
		}
	}

	private Status importFromZIP(Representation rep) {
		File tmpFile = null;
		try {
			tmpFile = writeStreamToTmpFile(rep.getStream());
			if (tmpFile != null && tmpFile.exists()) {
				ZipFile zipFile = new ZipFile(tmpFile);
				Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
				while (zipEntries.hasMoreElements()) {
					ZipEntry entry = zipEntries.nextElement();
					String nameLC = entry.getName();
					if (!entry.isDirectory() && (nameLC.endsWith(".xml") || nameLC.endsWith(".rdf"))) {
						InputStream fileIS = zipFile.getInputStream(entry);
						if (fileIS == null) {
							log.error("Unable to get InputStream of ZipEntry: " + nameLC);
							continue;
						}
						String fileString = null;
						try {
							StringWriter writer = new StringWriter();
							IOUtils.copy(fileIS, writer);
							fileString = writer.toString();
							if (fileString == null) {
								log.error("[IMPORT] Problem with reading ZipEntry into String");
								continue;
							}
						} finally {
							if (fileIS != null) {
								fileIS.close();
							}
						}
						if (nameLC.endsWith(".xml")) {
							importLOMResource(fileString);
						} else if (nameLC.endsWith(".rdf")) {
							importRDFResource(fileString);
						}
					}
				}
			} else {
				return Status.SERVER_ERROR_INTERNAL;
			}
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
			return Status.SERVER_ERROR_INTERNAL;
		} finally {
			if (tmpFile != null) {
				tmpFile.delete();
			}
		}
		
		return Status.SUCCESS_CREATED;
	}
	
	public File writeStreamToTmpFile(InputStream is) throws IOException {
		File tmpFile = File.createTempFile("scam_import_", ".zip");
		log.info("[IMPORT] Created temporary file: " + tmpFile);
		FileOutputStream fos = new FileOutputStream(tmpFile);
		FileOperations.copyFile(is, fos);
		return tmpFile;
	}
	
	private void importLOMResource(String lomString) {
		LOMImpl lom = ConverterUtil.readLOMfromReader(new StringReader(lomString));
		Location techLoc = LOMUtil.getTechnicalLocation(lom, 0);
		if (techLoc != null) {
			URI resourceURI = null;
			try {
				try {
					resourceURI = new URI(URLDecoder.decode(techLoc.string().trim(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					resourceURI = new URI(techLoc.string().trim());
				}
			} catch (URISyntaxException e) {
				log.info(e.getMessage());
				return;
			}
			if (resourceURI != null) {
				Graph metadataGraph = ConverterUtil.convertLOMtoGraph(lom, resourceURI);
				Set<Entry> entries = context.getByResourceURI(resourceURI);
				if (entries.isEmpty()) {
					Entry newEntry = context.createLink(null, resourceURI, entry.getResourceURI());
					newEntry.getLocalMetadata().setGraph(metadataGraph);
					log.info("[IMPORT] Created new entry with URI: " + newEntry.getEntryURI());
				} else {
					for (Entry existingEntry : entries) {
						if (existingEntry.getReferringListsInSameContext().isEmpty()) {
							((ListImpl) entry).addChild(existingEntry.getEntryURI());
						}
						Metadata existingMetadata = existingEntry.getLocalMetadata();
						if (existingMetadata != null) {
							existingMetadata.setGraph(metadataGraph);
							log.info("[IMPORT] Updated metadata of existing entry: " + existingEntry.getEntryURI());
						}
					}
				}
			}
		} else {
			log.info("[IMPORT] No LOM Technical Location found, unable to construct Resource URI");
		}
	}
	
	private void importRDFResource(String rdfString) {
		// TODO
	}
	
	public Set<Entry> getListChildrenRecursively(Entry listEntry) {
		Set<Entry> result = new HashSet<Entry>();
		if (GraphType.List.equals(listEntry.getGraphType()) && EntryType.Local.equals(listEntry.getEntryType())) {
			org.entrystore.List l = (org.entrystore.List) listEntry.getResource();
			List<URI> c = l.getChildren();
			for (URI uri : c) {
				Entry e = getRM().getContextManager().getEntry(uri);
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
	
	public StringRepresentation getSyndicationSolr(Entry entry, String type) {
		if (getRM().getIndex() == null) {
			return null;
		}
		
		int FEED_SIZE = 250;
		if (parameters.containsKey("feedSize")) {
			try {
				FEED_SIZE = Integer.parseInt(parameters.get("feedSize"));
			} catch (NumberFormatException nfe) {
				log.warn("Feed size parameter was not a legal Integer: " + nfe.getMessage());
			}
		}
		
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(type);

		GraphType bt = entry.getGraphType();
		if (!GraphType.Context.equals(bt) && !GraphType.List.equals(bt)) {
			return null;
		}

		String solrQueryValue;
		String alias;

		if (GraphType.Context.equals(bt)) {
			alias = getCM().getName(entry.getResourceURI());
			solrQueryValue = "context:";
		} else {
			alias = EntryUtil.getTitle(entry, "en");
			solrQueryValue = "lists:";
		}
		
		feed.setTitle("Feed of \"" + alias + "\"");
		feed.setDescription("A syndication feed containing the 50 most recent items from \"" + alias + "\"");
		feed.setLink(entry.getResourceURI().toString());
		
		solrQueryValue += entry.getResourceURI().toString().replaceAll(":", "\\\\:");
		SolrQuery solrQuery = new SolrQuery(solrQueryValue);
		solrQuery.setStart(0);
		solrQuery.setRows(1000);
		solrQuery.setSortField("modified", ORDER.desc);

		List<SyndEntry> syndEntries = new ArrayList<SyndEntry>();
		Set<Entry> searchEntries = ((SolrSearchIndex) getRM().getIndex()).sendQuery(solrQuery).getEntries();
		List<Entry> recursiveEntries = new LinkedList<Entry>();
		for (Entry e : searchEntries) {
			recursiveEntries.addAll(getListChildrenRecursively(e));
		}
		EntryUtil.sortAfterModificationDate(recursiveEntries, false, null);
		
		int limitedCount = 0;
		for (Entry e : recursiveEntries) {
			SyndEntry syndEntry;
			syndEntry = new SyndEntryImpl();
			syndEntry.setTitle(EntryUtil.getTitle(e, "en"));
			syndEntry.setPublishedDate(e.getCreationDate());
			syndEntry.setUpdatedDate(e.getModifiedDate());
			syndEntry.setLink(e.getResourceURI().toString());

			SyndContent description = new SyndContentImpl();
			description.setType("text/plain");
			
			Map<String, String> descriptions = EntryUtil.getDescriptions(e);
			Set<java.util.Map.Entry<String,String>> descEntrySet = descriptions.entrySet();
			String desc = null;
			for (Map.Entry<String, String> descEntry : descEntrySet) {
				desc = descEntry.getKey();
				if ("en".equals(descEntry.getValue())) {
					break;
				}
			}
			
			if (desc != null) {  
				description.setValue(desc);
			}

			syndEntry.setDescription(description);
			
			URI creator = e.getCreator();
			if (creator != null) {
				Entry creatorEntry = getRM().getPrincipalManager().getByEntryURI(creator);
				String creatorName = EntryUtil.getName(creatorEntry);
				if (creatorName != null) {
					syndEntry.setAuthor(creatorName);
				}
			}
			
			syndEntries.add(syndEntry);
			
			if (limitedCount++ >= FEED_SIZE) {
				break;
			}
		}

		feed.setEntries(syndEntries);
		String s = null;
		try {
			s = new SyndFeedOutput().outputString(feed, true);
		} catch (FeedException fe) {
			log.error(fe.getMessage());
			s = fe.getMessage();
		}
		
		String feedType = feed.getFeedType();
		MediaType mediaType = null;
		if (feedType != null) {
			if (feedType.startsWith("rss_")) {
				mediaType = MediaType.APPLICATION_RSS;
			} else if (feedType.startsWith("atom_")) {
				mediaType = MediaType.APPLICATION_ATOM;
			}
		}

		if (mediaType != null) {
			return new StringRepresentation(s, mediaType);
		} else {
			return new StringRepresentation(s);
		}
	}

	private Representation getListRepresentation() {
		JSONArray array = new JSONArray();
		org.entrystore.List l = (org.entrystore.List) entry.getResource();
		List<URI> uris = l.getChildren();
		Set<String> IDs = new HashSet<String>();
		for (URI u : uris) {
			String id = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/') + 1);
			IDs.add(id);
		}

		if (parameters.containsKey("sort") && (IDs.size() < 501)) {
			List<Entry> childrenEntries = new ArrayList<Entry>();
			for (String id : IDs) {
				Entry childEntry = this.context.get(id);
				if (childEntry != null) {
					childrenEntries.add(childEntry);
				} else {
					log.warn("Child entry " + id + " in context " + context.getURI() + " does not exist, but is referenced by a list.");
				}
			}

			Date before = new Date();
			boolean asc = true;
			if ("desc".equalsIgnoreCase(parameters.get("order"))) {
				asc = false;
			}
			GraphType prioritizedResourceType = null;
			if (parameters.containsKey("prio")) {
				prioritizedResourceType = GraphType.valueOf(parameters.get("prio"));
			}
			String sortType = parameters.get("sort");
			if ("title".equalsIgnoreCase(sortType)) {
				String lang = parameters.get("lang");
				EntryUtil.sortAfterTitle(childrenEntries, lang, asc, prioritizedResourceType);
			} else if ("modified".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterModificationDate(childrenEntries, asc, prioritizedResourceType);
			} else if ("created".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterCreationDate(childrenEntries, asc, prioritizedResourceType);
			} else if ("size".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterFileSize(childrenEntries, asc, prioritizedResourceType);
			}
			long sortDuration = new Date().getTime() - before.getTime();
			log.debug("List entry sorting took " + sortDuration + " ms");

			for (Entry childEntry : childrenEntries) {
				URI childURI = childEntry.getEntryURI();
				String id = (childURI.toASCIIString()).substring((childURI.toASCIIString()).lastIndexOf('/') + 1);
				array.put(id);
			}
		} else {
			if (IDs.size() > 500) {
				log.warn("No sorting performed because of list size bigger than 500 children");
			}
			for (String id : IDs) {
				array.put(id);
			}
		}

		return new JsonRepresentation(array.toString());
	}

	/**
	 * Gets the resource's JSON representation
	 * 
	 * @return JSON representation
	 */
	private Representation getResource() throws AuthorizationException {
		// RSS feed
		if (parameters.containsKey("syndication")) {
			try {
				if (getRM().getIndex() == null) {
					getResponse().setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
					return new JsonRepresentation("{\"error\":\"Feeds are not supported by this installation\"}");
				}
				StringRepresentation rep = getSyndicationSolr(entry, parameters.get("syndication"));
				if (rep == null) {
					getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
					return new JsonRepresentation(JSONErrorMessages.errorNotAContext);
				}
				return rep;
			} catch (IllegalArgumentException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(JSONErrorMessages.syndicationFormat);
			}
		}

		// Graph and List
		if (GraphType.Graph.equals(entry.getGraphType()) || GraphType.List.equals(entry.getGraphType())) {
			boolean list = GraphType.List.equals(entry.getGraphType());
			MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
			if (preferredMediaType == null) {
				preferredMediaType = MediaType.APPLICATION_RDF_XML;
			}
			Graph graph = null;
			if (list) {
				graph = ((org.entrystore.List) entry.getResource()).getGraph();
			} else {
				graph = ((RDFResource) entry.getResource()).getGraph();
			}

			if (graph != null) {
				String serializedGraph = null;
				if (preferredMediaType.equals(MediaType.APPLICATION_JSON)) {
					if (list) {
						return getListRepresentation();
					}
					serializedGraph = RDFJSON.graphToRdfJson(graph);
				} else {
					serializedGraph = GraphUtil.serializeGraph(graph, preferredMediaType);
				}

				if (serializedGraph != null) {
					getResponse().setStatus(Status.SUCCESS_OK);
					return new StringRepresentation(serializedGraph, preferredMediaType);
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
					return new JsonRepresentation(JSONErrorMessages.errorUnknownFormat);
				}
			}
		}

		if (ResourceType.NamedResource.equals(entry.getResourceType())
				&& EntryType.Local.equals(entry.getEntryType())) {
			redirectSeeOther(entry.getLocalMetadataURI().toString());
			return new EmptyRepresentation();
		}

		if (EntryType.Link.equals(entry.getEntryType()) ||
				EntryType.LinkReference.equals(entry.getEntryType()) ||
				EntryType.Reference.equals(entry.getEntryType())) {
			if (GraphType.None.equals(entry.getGraphType())) {
				getResponse().setLocationRef(new Reference(entry.getResourceURI().toString()));
				getResponse().setStatus(Status.REDIRECTION_SEE_OTHER);
				return null;
			}
		} else if (EntryType.Local.equals(entry.getEntryType())) {

			GraphType gt = entry.getGraphType();
			/*** String ***/
			if (GraphType.String.equals(gt)) {
				StringResource stringResource = (StringResource)entry.getResource(); 
				return new StringRepresentation(stringResource.getString());
			}

			/*** Graph ***/
			if (GraphType.Graph.equals(gt) || GraphType.Pipeline.equals(gt)) {
				RDFResource graphResource = (RDFResource) entry.getResource(); 
				Graph graph = graphResource.getGraph();
				if (graph == null) {
					getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
					return new JsonRepresentation("{\"error\":\"The graph has not been set\"}"); 
				} 
				return new JsonRepresentation(RDFJSON.graphToRdfJson(graph));  
			}

			/*** Context ***/
			if (GraphType.Context.equals(gt) || GraphType.SystemContext.equals(gt)) {
				JSONArray array = new JSONArray();
				Context c = (Context) entry.getResource();
				Set<URI> uris = c.getEntries(); 
				for(URI u: uris) {
					String entryId = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/')+1);
					array.put(entryId); 					
				}
				return new JsonRepresentation(array.toString());	
			}

			/*** None ***/
			if (GraphType.None.equals(gt)) {

				// Local data
				if(entry.getResourceType() == ResourceType.InformationResource) {
					File file = ((Data)entry.getResource()).getDataFile(); 
					if  (file != null) {
						String medTyp = entry.getMimetype();
						FileRepresentation rep = null;
						if (medTyp != null) {
							rep = new FileRepresentation(file, MediaType.valueOf(medTyp));
						} else {
							rep = new FileRepresentation(file, MediaType.ALL);
						}
						String fileName = entry.getFilename();
						if (fileName == null) {
							fileName = entry.getId();
						}
						Disposition disp = rep.getDisposition();
						disp.setFilename(fileName);
						if (parameters.containsKey("download")) {
							disp.setType(Disposition.TYPE_ATTACHMENT);
						} else {
							disp.setType(Disposition.TYPE_INLINE);
						}
						return rep;
					}
				}

				// If there is no resource we redirect to the metadata
				if (ResourceType.NamedResource.equals(entry.getResourceType())) {
					getResponse().setLocationRef(new Reference(entry.getLocalMetadataURI()));
					getResponse().setStatus(Status.REDIRECTION_SEE_OTHER);
					return null;
				}

				// NOT USED YET
				if (ResourceType.Unknown.equals(entry.getResourceType())) {
				}

			}

			/*** User ***/
			if (GraphType.User.equals(gt)) {
				JSONObject jsonUserObj = new JSONObject();  
				User user = (User) entry.getResource(); 
				try {
					jsonUserObj.put("name", user.getName());

					Context homeContext = user.getHomeContext();
					if (homeContext != null) {
						jsonUserObj.put("homecontext", homeContext.getEntry().getId());
					}

					String prefLang = user.getLanguage();
					if (prefLang != null) {
						jsonUserObj.put("language", prefLang);
					}

					JSONObject customProperties = new JSONObject();
					for (java.util.Map.Entry<String, String> propEntry : user.getCustomProperties().entrySet()) {
						customProperties.put(propEntry.getKey(), propEntry.getValue());
					}
					jsonUserObj.put("customProperties", customProperties);

					return new JsonRepresentation(jsonUserObj);
				} catch (JSONException e) {
					log.error(e.getMessage());
				} 
			}

			/*** Group ***/
			if (GraphType.Group.equals(gt)) {
				JSONObject jsonGroupObj = new JSONObject(); 
				Group group = (Group) entry.getResource(); 
				JSONArray userArray = new JSONArray(); 
				try {
					for(User u : group.members()) {
						JSONObject childJSON = new JSONObject();
						JSONObject childInfo = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getGraph())); 

						if(childInfo != null) {
							childJSON.accumulate("info_stub", childInfo);   
						} else {	
							childJSON.accumulate("info_stub", new JSONObject());  
						}

						JSONObject childMd = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getLocalMetadata().getGraph())); 

						if(childMd != null) {
							childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, childMd);   
						} else {	
							childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, new JSONObject());  
						}
						userArray.put(childJSON); 
					}
					jsonGroupObj.put("children", userArray); 
					return new JsonRepresentation(jsonGroupObj);
				} catch (JSONException e) {
					log.error(e.getMessage());
				} 
			}

			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return new JsonRepresentation(JSONErrorMessages.errorResourceNotFound);
		}

		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation("{\"error\":\"No resource available for "+entry.getEntryType()+" entries \"}");
	}


	/**
	 * Set a resource to an entry.
	 */
	private void modifyResource(MediaType mediaType) throws AuthorizationException {
		GraphType gt = entry.getGraphType();
		/*
		 * List and Group
		 */
		if (GraphType.List.equals(gt) || GraphType.Group.equals(gt)) {
			String requestBody = null;
			try {
				requestBody = getRequest().getEntity().getText();
			} catch (IOException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
			if (MediaType.APPLICATION_JSON.equals(mediaType)) {
				try {
					JSONArray childrenJSONArray = new JSONArray(requestBody);
					ArrayList<URI> newResource = new ArrayList<URI>();

					// Add new entries to the list.
					for (int i = 0; i < childrenJSONArray.length(); i++) {
						String childId = childrenJSONArray.get(i).toString();
						Entry childEntry = context.get(childId);
						if (childEntry == null) {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							log.debug("Cannot update list, since one of the children does not exist.");
							return;
						} else {
							newResource.add(childEntry.getEntryURI());
						}
					}

					if (entry.getGraphType() == GraphType.List) {
						org.entrystore.List resourceList = (org.entrystore.List) entry.getResource();
						resourceList.setChildren(newResource);
					} else {
						Group resourceGroup = (Group) entry.getResource();
						resourceGroup.setChildren(newResource);
					}
					getResponse().setStatus(Status.SUCCESS_OK);
				} catch (JSONException e) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorJSONSyntax));
				} catch (RepositoryException re) {
					log.warn(re.getMessage());
					getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
					getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorChildExistsInList));
				}
				return; // success!
			} else {
				Graph graph = GraphUtil.deserializeGraph(requestBody, mediaType);
				if (graph != null && GraphType.List.equals(entry.getGraphType())) {
					((org.entrystore.List) entry.getResource()).setGraph(graph);
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				}
				// TODO add support for groups here
			}
		}

		/*
		 * Data
		 */
		if (GraphType.None.equals(gt)){
			boolean textarea = this.parameters.keySet().contains("textarea");
			String error = null;

			if (MediaType.MULTIPART_FORM_DATA.equals(getRequest().getEntity().getMediaType(), true)) {
				try {
					List<FileItem> items = Util.createRestletFileUpload(getContext()).parseRepresentation(getRequest().getEntity());
					Iterator<FileItem> iter = items.iterator();
					if (iter.hasNext()) {
						FileItem item = iter.next();
						long maxFileSize = getRM().getMaximumFileSize();

						// we check if the file is not too big
						if (maxFileSize != -1 && item.getSize() > maxFileSize) {
							throw new QuotaException(QuotaException.QUOTA_FILE_TOO_BIG);
						}

						((Data) entry.getResource()).setData(item.getInputStream());
						entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
						String mimeType = item.getContentType();
						if (parameters.containsKey("mimeType")) {
							mimeType = parameters.get("mimeType");
						}
						entry.setMimetype(mimeType);
						String name = item.getName();
						if (name != null && name.length() != 0) {
							entry.setFilename(name.trim());
						}
					}
				} catch (FileUploadException e) {
					error = e.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				} catch (IOException ioe) {
					error = ioe.getMessage();
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				} catch (QuotaException qe) {
					error = qe.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				}
			} else {
				Request req = getRequest();
				try {
					((Data) entry.getResource()).setData(req.getEntity().getStream());
					entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
					String mimeType = req.getEntity().getMediaType().toString();
					if (parameters.containsKey("mimeType")) {
						mimeType = parameters.get("mimeType");
					}
					entry.setMimetype(mimeType);
					Disposition disp = req.getEntity().getDisposition();
					if (disp != null) {
						String name = disp.getFilename();
						if (name != null && name.length() != 0) {
							entry.setFilename(name.trim());
						}
					}
				} catch (QuotaException qe) {
					error = qe.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				} catch (IOException ioe) {
					error = ioe.getMessage();
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
			}
			
			if (error != null) {
				if (textarea) {
					getResponse().setEntity("<textarea>" + error + "</textarea>", MediaType.TEXT_HTML);
				} else {
					JSONObject jsonError = new JSONObject();
					try {
						jsonError.put("error", error);
					} catch (JSONException jsone) {
						log.error(jsone.getMessage());
					}
					getResponse().setEntity(new JsonRepresentation(error));
				}
				return;
			}

			if (textarea) {
				getResponse().setEntity("<textarea>{\"success\":\"The file is uploaded\", \"format\": \""+entry.getMimetype()+"\"}</textarea>",MediaType.TEXT_HTML);
			} else {
				getResponse().setEntity(new JsonRepresentation("{\"success\":\"The file is uploaded\", \"format\": \""+entry.getMimetype()+"\"}"));				
			}
			getResponse().setStatus(Status.SUCCESS_CREATED);
		}

		/*** String  ***/
		if(GraphType.String.equals(gt)) {
			try {
				StringResource stringResource = (StringResource)entry.getResource(); 
				stringResource.setString(getRequest().getEntity().getText());
			} catch (IOException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"Problem with input.\"}"));
			}
		}
		
		/*** Graph and Pipeline ***/
		if (GraphType.Graph.equals(gt) || GraphType.Pipeline.equals(gt)) {
			RDFResource graphResource = (RDFResource) entry.getResource(); 
			if (graphResource != null) {
				Graph graph = null;
				try {
					graph = GraphUtil.deserializeGraph(getRequest().getEntity().getText(), mediaType);
				} catch (IOException ioe) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					getResponse().setEntity(new JsonRepresentation("{\"error\":\"Unable to read request entity\"}"));
				}
				if (graph != null) {
					graphResource.setGraph(graph);
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				}
			} else {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"No RDF resource found for this entry\"}"));
				log.error("No RDF resource found for entry with ResourceType Graph");
			}
		}

		/*** User ***/
		if (GraphType.User.equals(gt)) {
			JSONObject entityJSON = null; 
			try {
				entityJSON = new JSONObject(getRequest().getEntity().getText());

				User resourceUser = (User) entry.getResource();
				if (entityJSON.has("name")) {
					String name = entityJSON.getString("name");
					if (resourceUser.setName(name)) {
						// the username was successfully changed, so we need to update the
						// UserInfo object to not invalidate logged in user sessions
						Cookie authTokenCookie = getRequest().getCookies().getFirst("auth_token");
						if (authTokenCookie != null) {
							TokenCache<String, UserInfo> tc = LoginTokenCache.getInstance();
							String authToken = authTokenCookie.getValue();
							UserInfo ui = tc.getTokenValue(authToken);
							if (ui != null) {
								ui.setUserName(name);
								tc.putToken(authToken, ui);
							}
						}
					} else {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Name already taken.\"}"));
						return;
					}
				}
				if (entityJSON.has("password")) {
					String passwd =  entityJSON.getString("password");
					if (!resourceUser.setSecret(passwd)) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Password needs to be at least 8 characters long.\"}"));
						return;
					}
				}
				if (entityJSON.has("language")) {
					String prefLang = entityJSON.getString("language");
					if (prefLang.equals("")) {
						resourceUser.setLanguage(null);						
					} else if (!resourceUser.setLanguage(prefLang)) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Preferred language could not be set.\"}"));
						return;
					}
				}
				if (entityJSON.has("homecontext")) {
					String homeContext = entityJSON.getString("homecontext");
					Entry entryHomeContext = getCM().get(homeContext);
					if (entryHomeContext != null) {
						if (!(entryHomeContext.getResource() instanceof Context)
								|| !resourceUser.setHomeContext((Context) entryHomeContext.getResource())) {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							getResponse().setEntity(new JsonRepresentation("{\"error\":\"Given homecontext is not a context.\"}"));
							return;						
						}
					}
				}
				if (entityJSON.has("customProperties")) {
					Map<String, String> customPropMap = new HashMap<>();
					JSONObject customPropJson = entityJSON.getJSONObject("customProperties");
					for (Iterator cPIt = customPropJson.keys(); cPIt.hasNext();) {
						String key = (String) cPIt.next();
						customPropMap.put(key, customPropJson.getString(key));
					}
					resourceUser.setCustomProperties(customPropMap);
				}
				getResponse().setStatus(Status.SUCCESS_OK);
			} catch (JSONException e) {
				log.debug("Wrong JSON syntax: " + e.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorJSONSyntax));
			} catch (IOException e) {
				log.error(e.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"IOException\"}"));
			}
		}
	}

}
