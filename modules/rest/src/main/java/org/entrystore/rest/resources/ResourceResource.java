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

import com.google.common.html.HtmlEscapers;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.QuotaException;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.ListImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.UserTempLockoutCache;
import org.entrystore.rest.serializer.ResourceJsonSerializer;
import org.entrystore.rest.serializer.ResourceJsonSerializer.ListParams;
import org.entrystore.rest.util.Email;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Uniform;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 */
public class ResourceResource extends BaseResource {

	private final Logger log = LoggerFactory.getLogger(ResourceResource.class);

	private  final EmptyRepresentation EMPTY_REPRESENTATION = new EmptyRepresentation();
	private final List<MediaType> supportedMediaTypes = java.util.List.of(
			MediaType.APPLICATION_RDF_XML,
			MediaType.APPLICATION_JSON,
			MediaType.TEXT_RDF_N3,
			new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()),
			new MediaType(RDFFormat.TRIX.getDefaultMIMEType()),
			new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()),
			new MediaType(RDFFormat.TRIG.getDefaultMIMEType()),
			new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()),
			new MediaType("application/rdf+json")
	);

	private UserTempLockoutCache userTempLockoutCache;
	private ResourceJsonSerializer resourceSerializer;

	@Override
	public void doInit() {
		this.userTempLockoutCache = getUserTempLockoutCache();
		this.resourceSerializer = new ResourceJsonSerializer(getPM(), getCM(), userTempLockoutCache);
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
					result.setTag(Util.createTag(lastMod));
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

		try {
			modifyResource();
			entry.updateModificationDate();
			getResponse().setEntity(createEmptyRepresentationWithLastModified(entry.getModifiedDate()));
		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Delete
	public void removeRepresentation() {
		if (entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		EntryType entryType = entry.getEntryType();

		try {
			if ((entryType == EntryType.Link || entryType == EntryType.Reference || entryType == EntryType.LinkReference)
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

	@Post
	public void acceptRepresentation(Representation r) {
		if (entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		GraphType graphType = entry.getGraphType();

		try {
			if (graphType == GraphType.List
				 && parameters.containsKey("import")
				 && MediaType.APPLICATION_ZIP.equals(getRequestEntity().getMediaType())) {

			getResponse().setStatus(importFromZIP(getRequestEntity()));
		} else if (graphType == GraphType.List
							 && parameters.containsKey("moveEntry")
							 && parameters.containsKey("fromList")) {

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
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			}
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
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

		/*
		 * Resource
		 */

		MediaType rdfFormat = MediaType.APPLICATION_JSON;
		if (RDFFormat.JSONLD.getDefaultMIMEType().equals(parameters.get("rdfFormat"))) {
			rdfFormat = new MediaType(RDFFormat.JSONLD.getDefaultMIMEType());
		}

		EntryType entryType = entry.getEntryType();
		GraphType graphType = entry.getGraphType();
		ResourceType resourceType = entry.getResourceType();

		// Resource missing
		if (graphType == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return new JsonRepresentation("{\"error\":\"No resource available for resource " + entry.getResourceURI());
		}

		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (preferredMediaType == null) {
			preferredMediaType = MediaType.APPLICATION_RDF_XML;
		}
		preferredMediaType = (format != null) ? format : preferredMediaType;

		// Graph and List resource

		if (graphType == GraphType.Graph || graphType == GraphType.List) {
			boolean isList = (entry.getGraphType() == GraphType.List);
			Model graph = null;

			if (isList) {
				graph = ((org.entrystore.List) entry.getResource()).getGraph();
			} else {
				graph = ((RDFResource) entry.getResource()).getGraph();
			}

			if (graph != null) {
				String serializedGraph = null;
				if (MediaType.APPLICATION_JSON.equals(preferredMediaType)) {
					if (isList) {
						return serializeJsonRepresentationResourceList(entry, new ListParams(parameters));
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

		// Remote Document (GraphType == None) Resource

		if (entryType == EntryType.Link || entryType == EntryType.LinkReference || entryType == EntryType.Reference) {
			if (graphType == GraphType.None) {
				getResponse().setLocationRef(new Reference(entry.getResourceURI().toString()));
				getResponse().setStatus(Status.REDIRECTION_SEE_OTHER);
				return null;
			}
		}

		/*
		 * Local Resource
		 */

		if (entryType == EntryType.Local) {

			// Named Local Resource
			if (resourceType == ResourceType.NamedResource) {
				redirectSeeOther(entry.getLocalMetadataURI().toString());
				return new EmptyRepresentation();
			}

			try {
				Resource resource = entry.getResource();
				return switch (graphType) {
					case None -> serializeFileRepresentationResourceNone(entry);
					case User -> new JsonRepresentation(resourceSerializer.serializeResourceUser(resource));
					case Group -> new JsonRepresentation(resourceSerializer.serializeResourceGroup(resource, rdfFormat));
					case String -> new JsonRepresentation(resourceSerializer.serializeResourceString(resource));
					case Context -> new JsonRepresentation(resourceSerializer.serializeResourceContext(resource));
					case SystemContext -> new JsonRepresentation(resourceSerializer.serializeResourceSystemContext(resource));
					case Pipeline -> {
						if (resource instanceof RDFResource pipeline) {
							if (pipeline.getGraph() == null) {
								getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
								yield new JsonRepresentation("{\"error\":\"The pipeline has not been set\"}");
							}
							yield new JsonRepresentation(resourceSerializer.serializeResourcePipeline(pipeline, rdfFormat));
						}
						yield EMPTY_REPRESENTATION;
					}
					case ResultList, PipelineResult -> EMPTY_REPRESENTATION;
                    default -> EMPTY_REPRESENTATION;
                };
			} catch (IllegalArgumentException e){
				log.error(e.getMessage(), e);
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return new JsonRepresentation("{\"error\":\"Internal Server Error\"}");
			}
		}
		return EMPTY_REPRESENTATION;
	}

	private FileRepresentation serializeFileRepresentationResourceNone(Entry entry) {
		if (entry.getResourceType() == ResourceType.InformationResource) {
			// Local data
			File file = ((Data)entry.getResource()).getDataFile();
			if  (file != null) {
				String medTyp = entry.getMimetype();
				FileRepresentation rep = null;
				if (medTyp != null) {
					try {
						rep = new FileRepresentation(file, MediaType.valueOf(medTyp));
					} catch (IllegalArgumentException iae) {
						log.warn("Invalid media type for {}: {}", entry.getEntryURI().toString(), iae.getMessage());
						rep = new FileRepresentation(file, MediaType.ALL);
					}
				} else {
					rep = new FileRepresentation(file, MediaType.ALL);
				}
				String fileName = entry.getFilename();
				if (fileName == null) {
					fileName = entry.getId();
				}
				Disposition disp = rep.getDisposition();
				disp.setFilename(fileName);
				if (!getRM().getConfiguration().getBoolean(Settings.HTTP_ALLOW_CONTENT_DISPOSITION_INLINE, true)
						|| parameters.containsKey("download")) {
					disp.setType(Disposition.TYPE_ATTACHMENT);
				} else {
					disp.setType(Disposition.TYPE_INLINE);
				}

				DataImpl data = new DataImpl(entry);
				String digest = data.readDigest();
				if (digest != null) {
					getResponse().getHeaders().set("Digest", "sha-256=" + digest);
				} else {
					log.debug("Digest does not exist for [{}]", entry.getResourceURI());
				}

				return rep;
			}
		} else if (entry.getResourceType() == ResourceType.NamedResource) {
			// If there is no resource we redirect to the metadata
			getResponse().setLocationRef(new Reference(entry.getLocalMetadataURI()));
			getResponse().setStatus(Status.REDIRECTION_SEE_OTHER);
		}
		// NOT USED YET
		//	if (ResourceType.Unknown.equals(entry.getResourceType())) {}
		return null;
	}

	private JsonRepresentation serializeJsonRepresentationResourceList(Entry entry, ListParams listParams) {
		JSONArray array = new JSONArray();
		org.entrystore.List l = (org.entrystore.List) this.entry.getResource();
		List<URI> uris = l.getChildren();
		Set<String> IDs = new HashSet<>();
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
			boolean asc = !"desc".equalsIgnoreCase(parameters.get("order"));
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

	public Set<Entry> getListChildrenRecursively(Entry listEntry) {
		Set<Entry> result = new HashSet<>();
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
			if (entry.getResourceType() == ResourceType.InformationResource) {
				Data data = (Data) entry.getResource();
				if (!data.delete()) {
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
							IOUtils.copy(fileIS, writer, StandardCharsets.UTF_8);
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
						if (nameLC.endsWith(".rdf")) {
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
		OutputStream fos = Files.newOutputStream(tmpFile.toPath());
		FileOperations.copyFile(is, fos);
		return tmpFile;
	}

	private void importRDFResource(String rdfString) {
		// TODO
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

		GraphType gt = entry.getGraphType();
		if (!GraphType.Context.equals(gt) && !GraphType.List.equals(gt)) {
			return null;
		}

		String solrQueryValue;
		String alias;

		if (GraphType.Context.equals(gt)) {
			alias = getCM().getName(entry.getResourceURI());
			solrQueryValue = "context:";
		} else {
			alias = EntryUtil.getTitle(entry, "en");
			solrQueryValue = "lists:";
		}

		feed.setTitle("Feed of \"" + alias + "\"");
		feed.setDescription("A syndication feed containing the 50 most recent items from \"" + alias + "\"");
		feed.setLink(entry.getResourceURI().toString());

		solrQueryValue += ClientUtils.escapeQueryChars(entry.getResourceURI().toString());
		SolrQuery solrQuery = new SolrQuery(solrQueryValue);
		solrQuery.setStart(0);
		solrQuery.setRows(1000);
		solrQuery.setSort("modified", ORDER.desc);

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
			Set<Map.Entry<String,String>> descEntrySet = descriptions.entrySet();
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

	/**
	 * Set a resource to an entry.
	 */
	private void modifyResource() throws AuthorizationException {
		GraphType gt = entry.getGraphType();
		MediaType mediaType = getRequestEntity().getMediaType();
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
					ArrayList<URI> newResource = new ArrayList<>();

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
				Model graph = GraphUtil.deserializeGraph(requestBody, mediaType);
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
		if (gt == GraphType.None) {
			boolean textarea = this.parameters.containsKey("textarea");
			String error = null;

			if (MediaType.MULTIPART_FORM_DATA.equals(mediaType, true)) {
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
						if (name != null && !name.isEmpty()) {
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
					String mimeType = MediaType.APPLICATION_OCTET_STREAM.toString();
					if (parameters.containsKey("mimeType")) {
						mimeType = parameters.get("mimeType");
					} else if (mediaType != null) {
						mimeType = mediaType.toString();
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
					getResponse().setEntity("<textarea>{\"error\":\"" + error + "\"}</textarea>", MediaType.TEXT_HTML);
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

			JSONObject result = new JSONObject();
			result.put("success", "The file was uploaded");
			result.put("format", HtmlEscapers.htmlEscaper().escape(entry.getMimetype()));
			if (textarea) {
				getResponse().setEntity("<textarea>" + result + "</textarea>", MediaType.TEXT_HTML);
			} else {
				getResponse().setEntity(new JsonRepresentation(result));
			}
			getResponse().setStatus(Status.SUCCESS_CREATED);
		}

		/*** String  ***/
		if (gt == GraphType.String) {
			try {
				StringResource stringResource = (StringResource)entry.getResource();
				stringResource.setString(getRequest().getEntity().getText());
			} catch (IOException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"Problem with input.\"}"));
			}
		}

		/*** Graph and Pipeline ***/
		if (gt == GraphType.Graph || gt == GraphType.Pipeline) {
			RDFResource graphResource = (RDFResource) entry.getResource();
			if (graphResource != null) {
				Model graph = null;
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
					String oldName = resourceUser.getName();
					String newName = entityJSON.getString("name");
					if (resourceUser.setName(newName)) {
						// the username was successfully changed, so we need to update the UserInfo
						// objects in the LoginTokenCache to not invalidate logged in user sessions
						LoginTokenCache loginTokenCache = ((EntryStoreApplication)getApplication()).getLoginTokenCache();
						loginTokenCache.renameUser(oldName, newName);
					} else {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Name is already in use\"}"));
						return;
					}
				}
				if (entityJSON.has("password")) {
					boolean requireCurrentPassword = getRM().getConfiguration().getBoolean(Settings.AUTH_PASSWORD_REQUIRE_CURRENT_PASSWORD, true);
					String newPassword =  entityJSON.getString("password");

					if (requireCurrentPassword) {
						// we require the current password if:
						// (1) the user is a non-admin user, or
						// (2) the user is an admin user and wants to set her own password
						if (!getPM().currentUserIsAdminOrAdminGroup() ||
								(getPM().currentUserIsAdminOrAdminGroup() && getPM().getAuthenticatedUserURI().equals(resourceUser.getURI()))) {
							if (!entityJSON.has("currentPassword")) {
								getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
								getResponse().setEntity(new JsonRepresentation("{\"error\":\"Current password is required\"}"));
								return;
							}
							String currentPassword = entityJSON.getString("currentPassword");
							String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(getPM(), resourceUser.getName());
							if (saltedHashedSecret == null || !Password.check(currentPassword, saltedHashedSecret)) {
								getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
								getResponse().setEntity(new JsonRepresentation("{\"error\":\"No password set or incorrect current password provided\"}"));
								return;
							}
						}
					}

					if (resourceUser.setSecret(newPassword)) {
						LoginTokenCache loginTokenCache = ((EntryStoreApplication)getApplication()).getLoginTokenCache();
						loginTokenCache.removeTokensButOne(CookieVerifier.getAuthToken(getRequest()));
						Email.sendPasswordChangeConfirmation(getRM().getConfiguration(), entry);
						return;
					} else {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Password needs to be at least 8 characters long.\"}"));
						return;
					}
				}
				if (entityJSON.has("language")) {
					String prefLang = entityJSON.getString("language");
					if (prefLang.isEmpty()) {
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
				if (entityJSON.has("disabled")) {
					if (this.entry.getResourceURI().equals(getPM().getAuthenticatedUserURI())) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Users cannot set their own disabled status\"}"));
						return;
					}
					boolean disabled = entityJSON.optBoolean("disabled", false);
					resourceUser.setDisabled(disabled);
					if (disabled) {
						String userName = getPM().getPrincipalName(this.entry.getResourceURI());
						LoginTokenCache loginTokenCache = ((EntryStoreApplication)getApplication()).getLoginTokenCache();
						loginTokenCache.removeTokens(userName);
					}
				}
				if (entityJSON.has("customProperties")) {
					Map<String, String> customPropMap = new HashMap<>();
					JSONObject customPropJson = entityJSON.getJSONObject("customProperties");
					for (Iterator<String> cPIt = customPropJson.keys(); cPIt.hasNext();) {
						String key = cPIt.next();
						customPropMap.put(key, customPropJson.getString(key));
					}
					resourceUser.setCustomProperties(customPropMap);
				}
				getResponse().setStatus(Status.SUCCESS_OK);
			} catch (JSONException e) {
				log.debug("Wrong JSON syntax: {}", e.getMessage());
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