/**
 * Copyright (c) 2007-2010
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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.github.jsonldjava.impl.SesameJSONLDParser;
import com.github.jsonldjava.impl.SesameJSONLDWriter;
import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Entry;
import org.entrystore.repository.Group;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.User;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.StringResource;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXParser;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleParser;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Handles entries.
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 * @author Hannes Ebner
 * @see BaseResource
 */
public class EntryResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(EntryResource.class);
	public static Config config;
	
	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.TEXT_HTML);
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()));

		Util.handleIfUnmodifiedSince(entry, getRequest());
	}

	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/entry/{entry-id}
	 * </pre>
	 * 
	 * @return The Representation as JSON
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				log.error("Cannot find an entry with that id");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);
			}

			MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
			if (preferredMediaType == null) {
				preferredMediaType = MediaType.APPLICATION_RDF_XML;
			}
			Representation result = getEntry((format != null) ? format : preferredMediaType);
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				result.setModificationDate(lastMod);
			}
			return result;
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		try {
			modifyEntry((format != null) ? format : getRequestEntity().getMediaType());
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (entry != null && context != null) {
				if (parameters.containsKey("method")) {
					if ("delete".equalsIgnoreCase(parameters.get("method"))) {
						removeRepresentations();		
					} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
						storeRepresentation(r);
					}
				} 
				return;
			}

			/*
			 * Error: If we comes to this point there is an ERROR.
			 */
			log.error("Wrong POST request");
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry != null && context != null) {
				if (BuiltinType.List.equals(entry.getBuiltinType()) && parameters.containsKey("recursive")) {
					org.entrystore.repository.List l = (org.entrystore.repository.List) entry.getResource();
					if (l != null) {
						l.removeTree();
					} else {
						log.warn("Resource of the following list is null: " + entry.getEntryURI());
					}
				} else {
					context.remove(entry.getEntryURI());
				}
				//getResponse().setEntity(new JsonRepresentation("{\"OK\":\"200\"}"));
			}
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	private Representation getEntry(MediaType mediaType) {
		String serializedGraph = null;
		Graph graph = entry.getGraph();
		if (MediaType.TEXT_HTML.equals(mediaType)) {
			return getEntryInHTML();
		} else if (MediaType.APPLICATION_JSON.equals(mediaType)) {
			return getEntryInJSON();
		} else if (MediaType.APPLICATION_RDF_XML.equals(mediaType)) {
			serializedGraph = ConverterUtil.serializeGraph(graph, RDFXMLPrettyWriter.class);
		} else if (MediaType.TEXT_RDF_N3.equals(mediaType)) {
			serializedGraph = ConverterUtil.serializeGraph(graph, N3Writer.class);
		} else if (RDFFormat.TURTLE.getDefaultMIMEType().equals(mediaType.getName())) {
			serializedGraph = ConverterUtil.serializeGraph(graph, TurtleWriter.class);
		} else if (RDFFormat.TRIX.getDefaultMIMEType().equals(mediaType.getName())) {
			serializedGraph = ConverterUtil.serializeGraph(graph, TriXWriter.class);
		} else if (RDFFormat.NTRIPLES.getDefaultMIMEType().equals(mediaType.getName())) {
			serializedGraph = ConverterUtil.serializeGraph(graph, NTriplesWriter.class);
		} else if (RDFFormat.TRIG.getDefaultMIMEType().equals(mediaType.getName())) {
			serializedGraph = ConverterUtil.serializeGraph(graph, TriGWriter.class);
		} else if (RDFFormat.JSONLD.getDefaultMIMEType().equals(mediaType.getName())) {
			serializedGraph = ConverterUtil.serializeGraph(graph, SesameJSONLDWriter.class);
		} else {
			mediaType = MediaType.APPLICATION_RDF_XML;
			serializedGraph = ConverterUtil.serializeGraph(graph, RDFXMLPrettyWriter.class);
		}

		if (serializedGraph != null) {
			getResponse().setStatus(Status.SUCCESS_OK);
			return new StringRepresentation(serializedGraph, mediaType);
		}

		return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);
	}

	private Representation getEntryInHTML() {
		try {	
			if (parameters != null) {
				parameters.put("includeAll", "true");
			}
			JSONObject jobj = this.getEntryAsJSONObject();
			String storejs = config.getString(Settings.STOREJS_JS, "/storejs/storejs.js");
			String storecss = config.getString(Settings.STOREJS_CSS, "/storejs/storejs.css");
			if (jobj != null) {
				return new StringRepresentation(
						"<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\">"+
						"<script type=\"text/javascript\">dojoConfig = {deps: [\"store/boot\"]};</script>"+
						"<script type=\"text/javascript\" src=\""+storejs+"\"></script>"+
						"<link type=\"text/css\" href=\""+storecss+"\" rel=\"stylesheet\"></link></head><body><textarea>" +
						jobj.toString(2) +
						"</textarea></body></html>", MediaType.TEXT_HTML);
			}
		} catch (JSONException e) {	
		}		
		log.error("Can not find the entry. getEntry()");
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);			
	}
	
	
	/**
	 * Gets the entry JSON
	 * 
	 * @return JSON representation
	 */
	private Representation getEntryInJSON() {
		try {	
			JSONObject jobj = this.getEntryAsJSONObject();
			if (jobj != null)
				return new JsonRepresentation(jobj.toString(2));
		} catch (JSONException e) {	
		}		
		log.error("Can not find the entry. getEntry()");
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);
	}

	
	private JSONObject getEntryAsJSONObject() throws JSONException {
		/*
		 * Create a JSONObject to accumulate properties
		 */
		JSONObject jdilObj = new JSONObject();

			/*
			 * Entry id
			 */
			jdilObj.put("entryId", entry.getId());
			BuiltinType bt = entry.getBuiltinType();
			if ((bt == BuiltinType.Context || bt == BuiltinType.SystemContext) && LocationType.Local.equals(entry.getLocationType())) {
				jdilObj.put("alias", getCM().getContextAlias(entry.getResourceURI()));
				if (entry.getRepositoryManager().hasQuotas()) {
					JSONObject quotaObj = new JSONObject();
					org.entrystore.repository.Context c = getCM().getContext(this.entryId);
					if (c != null) {
						quotaObj.put("quota", c.getQuota());
						quotaObj.put("fillLevel", c.getQuotaFillLevel());
					}
					quotaObj.put("hasDefaultQuota", c.hasDefaultQuota());
					jdilObj.put("quota", quotaObj);
				}
			}

			/*
			 * Entry information
			 */
			Graph entryGraph = entry.getGraph();
			JSONObject entryObj = new JSONObject(RDFJSON.graphToRdfJson(entryGraph));
			jdilObj.accumulate("info", entryObj);

			/*
			 * Return if the parameter includeAll is not set
			 */
			if ((parameters != null && parameters.containsKey("includeAll")) == false) {
				return jdilObj;
			}
			/*
			 * If the parameter includeAll is set we must return more JDIl with
			 * example local metadata, cached external metadata and maybe a
			 * resource.
			 */

			LocationType lt = entry.getLocationType();
			
			/*
			 * Cached External Metadata
			 */
			JSONObject cachedExternalMdObj = null;
			if (LocationType.LinkReference.equals(lt) || LocationType.Reference.equals(lt)) {
				try {
					Metadata cachedExternalMd = entry.getCachedExternalMetadata();
					Graph g = cachedExternalMd.getGraph();
					if (g != null) {
						cachedExternalMdObj = new JSONObject(RDFJSON.graphToRdfJson(g));
						jdilObj.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, cachedExternalMdObj);
					}
				} catch (AuthorizationException ae) {
					//jdilObj.accumulate("noAccessToMetadata", true);
					//TODO: Replaced by using "rights" in json, do something else in this catch-clause
				}
			}

			/*
			 * Local Metadata
			 */
			JSONObject localMdObj = null;
			if (LocationType.Local.equals(lt) || LocationType.Link.equals(lt) || LocationType.LinkReference.equals(lt)) {
				try {
					Metadata localMd = entry.getLocalMetadata();
					Graph g = localMd.getGraph();
					if (g != null) {
						localMdObj = new JSONObject(RDFJSON.graphToRdfJson(g));
						jdilObj.accumulate(RepositoryProperties.MD_PATH, localMdObj);
					}
				} catch (AuthorizationException ae) {
					/*if (!jdilObj.has("noAccessToMetadata")) {
						jdilObj.accumulate("noAccessToMetadata", true);
					}*/
//					TODO: Replaced by using "rights" in json, do something else in this catch-clause
				}
			}

			/*
			 *	Relation 
			 */
			if (entry.getRelations() != null) {
				JSONObject relationObj = new JSONObject();
				relationObj = new JSONObject(RDFJSON.graphToRdfJson(new GraphImpl(entry.getRelations())));
				jdilObj.accumulate(RepositoryProperties.RELATION, relationObj);
			}
			
			/*
			 * Rights
			 */
			this.accumulateRights(entry, jdilObj);

			/*
			 * Resource
			 */
			JSONObject resourceObj = new JSONObject();
			if (entry.getLocationType() == LocationType.Local) {
				/*
				 *  String
				 */
				if(entry.getBuiltinType() == BuiltinType.String) {
					StringResource stringResource = (StringResource)entry.getResource(); 
					Graph graph = stringResource.getGraph(); 
					Iterator<Statement> iter = graph.iterator(); 
					while(iter.hasNext()) {
						Statement s = iter.next(); 
						Value value = s.getObject();
						Literal lit = (Literal) value;
						String language = lit.getLanguage();
						org.openrdf.model.URI datatype = lit.getDatatype();
						JSONObject object = new JSONObject();
						object.accumulate("@value", value.stringValue());
						if (language != null) {
							object.accumulate("@language", language);
						} else if (datatype != null) {
							object.accumulate("@datatype", datatype.stringValue());					
						}
						resourceObj.accumulate(s.getPredicate().stringValue(), object);
					}
				}
				/*
				 * List
				 */
				if (entry.getBuiltinType() == BuiltinType.List) {

					int limit = 0;
					int offset = 0;
					boolean sort = parameters.containsKey("sort");

					// size of returned entry list
					if (parameters.containsKey("limit")) {
						try {
							limit = Integer.valueOf(parameters.get("limit"));
							// we don't support more than 100 results per page for now
							if (limit > 100) {
								limit = 100;
							}
						} catch (NumberFormatException ignored) {}
					}

					// offset (needed for pagination)
					if (parameters.containsKey("offset")) {
						try {
							offset = Integer.valueOf(parameters.get("offset"));
						} catch (NumberFormatException ignored) {}
						if (offset < 0) {
							offset = 0;
						}
					}

					try {
						// List<JSONObject> childrenObjects = new ArrayList<JSONObject>();
						JSONArray childrenArray = new JSONArray();
						org.entrystore.repository.Resource res = entry.getResource();
						org.entrystore.repository.List parent = (org.entrystore.repository.List) res;

						int maxPos = offset + limit;
						if (limit == 0) {
							maxPos = Integer.MAX_VALUE;
						}

						List<URI> childrenURIs = parent.getChildren();
						Set<String> childrenIDs = new HashSet<String>();
						List<Entry> childrenEntries = new ArrayList<Entry>();
						for (int i = 0; i < childrenURIs.size(); i++) {
							String u = childrenURIs.get(i).toString();
							String id = u.substring(u.lastIndexOf('/') + 1);
							childrenIDs.add(id);
							Entry childEntry = this.context.get(id);
							if (childEntry != null) {
								childrenEntries.add(childEntry);
							} else {
								log.warn("Child entry " + id + " in context " + context.getURI() + " does not exist, but is referenced by a list.");
							}
						}

						if (sort && (childrenEntries.size() < 501)) {
							Date before = new Date();
							boolean asc = true;
							if ("desc".equalsIgnoreCase(parameters.get("order"))) {
								asc = false;
							}
							BuiltinType prioritizedBuiltinType = null;
							if (parameters.containsKey("prio")) {
								prioritizedBuiltinType = BuiltinType.valueOf(parameters.get("prio"));
							}
							String sortType = parameters.get("sort");
							if ("title".equalsIgnoreCase(sortType)) {
								String lang = parameters.get("lang");
								EntryUtil.sortAfterTitle(childrenEntries, lang, asc, prioritizedBuiltinType);
							} else if ("modified".equalsIgnoreCase(sortType)) {
								EntryUtil.sortAfterModificationDate(childrenEntries, asc, prioritizedBuiltinType);
							} else if ("created".equalsIgnoreCase(sortType)) {
								EntryUtil.sortAfterCreationDate(childrenEntries, asc, prioritizedBuiltinType);
							} else if ("size".equalsIgnoreCase(sortType)) {
								EntryUtil.sortAfterFileSize(childrenEntries, asc, prioritizedBuiltinType);
							}
							long sortDuration = new Date().getTime() - before.getTime();
							log.debug("List entry sorting took " + sortDuration + " ms");
						} else if (sort && (childrenEntries.size() > 500)) {
							log.warn("Ignoring sort parameter for performance reasons because list has more than 500 children");
						}

						//for (int i = 0; i < childrenURIs.size(); i++) {
						for (int i = offset; i < maxPos && i < childrenEntries.size(); i++) {
							JSONObject childJSON = new JSONObject();
							Entry childEntry = childrenEntries.get(i);
							
							/*
							 * Children-rights
							 */
							this.accumulateRights(childEntry, childJSON);

							String uri = childEntry.getEntryURI().toString();
							String entryId = uri.substring(uri.lastIndexOf('/') + 1);
							childJSON.put("entryId", entryId);
							BuiltinType btChild = childEntry.getBuiltinType();
							LocationType locChild = childEntry.getLocationType();
							if ((btChild == BuiltinType.Context || btChild == BuiltinType.SystemContext) && LocationType.Local.equals(locChild)) {
								childJSON.put("alias", getCM().getContextAlias(childEntry.getResourceURI()));
							} else if (btChild == BuiltinType.List) {
								if (!("_unlisted".equals(entryId) || "_latest".equals(entryId))) {
									org.entrystore.repository.Resource childRes = childEntry.getResource();
									if (childRes != null && childRes instanceof org.entrystore.repository.List) {
										org.entrystore.repository.List childList = (org.entrystore.repository.List) childRes;
										try {
											childJSON.put("size", childList.getChildren().size());
										} catch (AuthorizationException ae) {}
									} else {
										log.warn("Entry has BuiltinType.List but the resource is either null or not an instance of List");
									}
								} else {
									log.warn("Not calculating list size of " + entryId + " because of potential performance problems");
								}
							} else if (btChild == BuiltinType.User && locChild == LocationType.Local) {
								childJSON.put("name", ((User) childEntry.getResource()).getName());
							} else if (btChild == BuiltinType.Group && locChild == LocationType.Local) {
								childJSON.put("name", ((Group) childEntry.getResource()).getName());								
							}
							
							try {
								LocationType ltC = childEntry.getLocationType();
								if (LocationType.Reference.equals(ltC) || LocationType.LinkReference.equals(ltC)) {
									// get the external metadata
									Metadata cachedExternalMD = childEntry.getCachedExternalMetadata();
									if (cachedExternalMD != null) {
										Graph cachedExternalMDGraph = cachedExternalMD.getGraph();
										if (cachedExternalMDGraph != null) {
											JSONObject childCachedExternalMDJSON = new JSONObject(RDFJSON.graphToRdfJson(cachedExternalMDGraph));
											childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, childCachedExternalMDJSON);
										}
									}
								}
								
								if (LocationType.Link.equals(ltC) || LocationType.Local.equals(ltC) || LocationType.LinkReference.equals(ltC)) {
									// get the local metadata
									Metadata localMD = childEntry.getLocalMetadata();
									if (localMD != null) {
										Graph localMDGraph = localMD.getGraph();
										if (localMDGraph != null) {
											JSONObject localMDJSON = new JSONObject(RDFJSON.graphToRdfJson(localMDGraph));
											childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
										}
									}
								}
							} catch (AuthorizationException e) {
								//childJSON.accumulate("noAccessToMetadata", true);
//								TODO: Replaced by using "rights" in json, do something else in this catch-clause
								// childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, new JSONObject());
							}

							Graph childEntryGraph = childEntry.getGraph();
							JSONObject childInfo = new JSONObject(RDFJSON.graphToRdfJson(childEntryGraph));

							if (childInfo != null) {
								childJSON.accumulate("info", childInfo);
							} else {
								childJSON.accumulate("info", new JSONObject());
							}
							//							childrenObjects.add(childJSON);
							
							if (childEntry.getRelations() != null) {
								Graph childRelationsGraph = new GraphImpl(childEntry.getRelations());
								JSONObject childRelationObj = new JSONObject(RDFJSON.graphToRdfJson(childRelationsGraph));
								childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
							}
							
							childrenArray.put(childJSON);
						}

						resourceObj.put("children", childrenArray);
						resourceObj.put("size", childrenURIs.size());
						resourceObj.put("limit", limit);
						resourceObj.put("offset", offset);
						
						
						JSONArray childrenIDArray = new JSONArray();
						for (String id : childrenIDs) {
							childrenIDArray.put(id);
						}
						resourceObj.put("allUnsorted", childrenIDArray);
					} catch (AuthorizationException ae) {
						//jdilObj.accumulate("noAccessToResource", true);
//						TODO: Replaced by using "rights" in json, do something else in this catch-clause
					}
				}

				/*
				 * User
				 */
				if (entry.getBuiltinType() == BuiltinType.User) {
					try {
						User user = (User) entry.getResource();
						resourceObj.put("name", user.getName());

						// resourceObj.put("password", user.getSecret());

						org.entrystore.repository.Context homeContext = user.getHomeContext();
						if (homeContext != null) {
							resourceObj.put("homecontext", homeContext.getEntry().getId());
						}

						String prefLang = user.getLanguage();
						if (prefLang != null) {
							resourceObj.put("language", prefLang);
						}
					} catch (AuthorizationException ae) {
						//jdilObj.accumulate("noAccessToResource", true);
//						TODO: Replaced by using "rights" in json, do something else in this catch-clause
					}
				}

				/*
				 * Group
				 */
				if (entry.getBuiltinType() == BuiltinType.Group) {
					try {
						Group group = (Group) entry.getResource();
						resourceObj.put("name", group.getName());
						JSONArray userArray = new JSONArray();
						for (User u : group.members()) {
							JSONObject childJSON = new JSONObject();
							JSONObject childInfo = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getGraph()));

							if (childInfo != null) {
								childJSON.accumulate("info", childInfo);
							} else {
								childJSON.accumulate("info", new JSONObject());
							}

							this.accumulateRights(u.getEntry(), childJSON);
							try {
								JSONObject childMd = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getLocalMetadata().getGraph()));
								if (childMd != null) {
									childJSON.accumulate(RepositoryProperties.MD_PATH, childMd);
								} else {
									childJSON.accumulate(RepositoryProperties.MD_PATH, new JSONObject());
								}
							} catch (AuthorizationException ae) {
								//childJSON.accumulate("noAccessToMetadata", true);
//								TODO: Replaced by using "rights" in json, do something else in this catch-clause
							}

							//Relations for every user in this group.
							if (u.getEntry().getRelations() != null) {
								Graph childRelationsGraph = new GraphImpl(u.getEntry().getRelations());
								JSONObject childRelationObj = new JSONObject(RDFJSON.graphToRdfJson(childRelationsGraph));
								childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
							}
							childJSON.put("entryId", u.getEntry().getId());
							userArray.put(childJSON);
						}

						resourceObj.put("children", userArray);
					} catch (AuthorizationException ae) {
						//jdilObj.accumulate("noAccessToResource", true);
//						TODO: Replaced by using "rights" in json, do something else in this catch-clause
					}
				}

				// TODO other types, for example Context, SystemContext, PrincipalManager, etc
				
				jdilObj.accumulate("resource", resourceObj);
			}
			return jdilObj;
	}
	
	
	private void accumulateRights(Entry entry, JSONObject jdilObj) throws JSONException {
		PrincipalManager pm = this.getPM();
		Set<AccessProperty> rights = pm.getRights(entry);
		if(rights.size() >0){
			for(AccessProperty ap : rights){
				if(ap == PrincipalManager.AccessProperty.Administer)
					jdilObj.append("rights", "administer");
				else if (ap == PrincipalManager.AccessProperty.WriteMetadata)
					jdilObj.append("rights", "writemetadata");
				else if (ap == PrincipalManager.AccessProperty.WriteResource)
					jdilObj.append("rights","writeresource");
				else if (ap == PrincipalManager.AccessProperty.ReadMetadata)
					jdilObj.append("rights","readmetadata");
				else if (ap == PrincipalManager.AccessProperty.ReadResource)
					jdilObj.append("rights","readresource");
			}
		}

	}

	private void modifyEntry(MediaType mediaType) throws AuthorizationException {
		String graphString = null;
		try {
			graphString = getRequest().getEntity().getText();
		} catch (IOException e) {
			log.error(e.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}
		Graph deserializedGraph = null;
		if (mediaType.equals(MediaType.APPLICATION_JSON)) {
			try {
				JSONObject jsonObj = new JSONObject(graphString);
				JSONObject infoObj = null;
				if ((infoObj = jsonObj.getJSONObject("info")) != null) {
					deserializedGraph = RDFJSON.rdfJsonToGraph(infoObj);
				}
			} catch (JSONException jsone) {
				log.error(jsone.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new N3ParserFactory().getParser());
		} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TurtleParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriXParser());
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new NTriplesParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriGParser());
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			deserializedGraph = ConverterUtil.deserializeGraph(graphString, new SesameJSONLDParser());
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
			return;
		}

		if (deserializedGraph != null) {
			entry.setGraph(deserializedGraph);
			if (parameters.containsKey("applyACLtoChildren") &&
					BuiltinType.List.equals(entry.getBuiltinType()) &&
					LocationType.Local.equals(entry.getLocationType())) {
				((org.entrystore.repository.List) entry.getResource()).applyACLtoChildren(true);
			}
			getResponse().setStatus(Status.SUCCESS_OK);
			return;
		}
	}

}