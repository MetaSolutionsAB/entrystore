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

import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Handles entries.
 * 
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class EntryResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(EntryResource.class);
	public static Config config;
	
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

	@Override
	public Representation head() {
		return head(null);
	}

	@Override
	public Representation head(Variant v) {
		try {
			Representation repr = new EmptyRepresentation();
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			} else {
				getResponse().setStatus(Status.SUCCESS_OK);
				repr.setModificationDate(entry.getModifiedDate());
			}
			return repr;
		} catch (AuthorizationException e) {
			return unauthorizedHEAD();
		}
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
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			Representation result = null;
			if (Method.GET.equals(getRequest().getMethod())) {
				MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
				if (preferredMediaType == null) {
					preferredMediaType = MediaType.APPLICATION_RDF_XML;
				}
				result = getEntry((format != null) ? format : preferredMediaType);
			} else {
				result = new EmptyRepresentation();
			}
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
			if (getRequest().getEntity().isEmpty()) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}

			modifyEntry((format != null) ? format : getRequestEntity().getMediaType());
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (entry == null || context == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}

			if (parameters.containsKey("method")) {
				if ("delete".equalsIgnoreCase(parameters.get("method"))) {
					removeRepresentations();
					return;
				} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
					storeRepresentation(r);
					return;
				}
			}

			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry != null && context != null) {
				if (GraphType.List.equals(entry.getGraphType()) && parameters.containsKey("recursive")) {
					org.entrystore.List l = (org.entrystore.List) entry.getResource();
					if (l != null) {
						l.removeTree();
					} else {
						log.warn("Resource of the following list is null: " + entry.getEntryURI());
					}
				} else {
					context.remove(entry.getEntryURI());
				}
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			}
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	private Representation getEntry(MediaType mediaType) {
		String serializedGraph = null;
		Graph graph = entry.getGraph();
		/* if (MediaType.TEXT_HTML.equals(mediaType)) {
			return getEntryInHTML();
		} else */ if (MediaType.APPLICATION_JSON.equals(mediaType)) {
			return getEntryInJSON();
		} else {
			serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
		}

		if (serializedGraph != null) {
			getResponse().setStatus(Status.SUCCESS_OK);
			return new StringRepresentation(serializedGraph, mediaType);
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new EmptyRepresentation();
	}

	/* Temporarily disabled code, see ENTRYSTORE-435 for details
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
			log.error(e.getMessage());
		}
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
	}
	*/
	
	/**
	 * Gets the entry JSON
	 * 
	 * @return JSON representation
	 */
	private Representation getEntryInJSON() {
		try {	
			JSONObject jobj = this.getEntryAsJSONObject();
			if (jobj != null) {
				return new JsonRepresentation(jobj.toString(2));
			}
		} catch (JSONException e) {
			log.error(e.getMessage());
		}

		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
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
			GraphType bt = entry.getGraphType();
			if ((bt == GraphType.Context || bt == GraphType.SystemContext) && EntryType.Local.equals(entry.getEntryType())) {
				jdilObj.put("name", getCM().getName(entry.getResourceURI()));
				if (entry.getRepositoryManager().hasQuotas()) {
					JSONObject quotaObj = new JSONObject();
					Context c = getCM().getContext(this.entryId);
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

			EntryType lt = entry.getEntryType();
			
			/*
			 * Cached External Metadata
			 */
			JSONObject cachedExternalMdObj = null;
			if (EntryType.LinkReference.equals(lt) || EntryType.Reference.equals(lt)) {
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
			 * Inferred Metadata
			 */
		    JSONObject inferredMdObj = null;
		    Metadata inferred = entry.getInferredMetadata();
		    if (inferred != null) {
				try {
					Graph infG = inferred.getGraph();
					if (infG != null) {
						inferredMdObj = new JSONObject(RDFJSON.graphToRdfJson(infG));
						jdilObj.accumulate(RepositoryProperties.INFERRED, inferredMdObj);
					}
				} catch (AuthorizationException ae) {
				}
			}

			/*
			 * Local Metadata
			 */
			JSONObject localMdObj = null;
			if (EntryType.Local.equals(lt) || EntryType.Link.equals(lt) || EntryType.LinkReference.equals(lt)) {
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
			JSONObject resourceObj = null;
			if (entry.getEntryType() == EntryType.Local) {
                GraphType graphType = entry.getGraphType();
				/*
				 *  String
				 */
				if (graphType == GraphType.String) {
					StringResource stringResource = (StringResource) entry.getResource(); 
					jdilObj.put("resource", stringResource.getString());
				}

				/*
				 *  Graph
				 */
				if (graphType == GraphType.Graph || graphType == GraphType.Pipeline) {
					RDFResource rdfResource = (RDFResource) entry.getResource(); 
					jdilObj.put("resource", new JSONObject(RDFJSON.graphToRdfJson(rdfResource.getGraph())));
				}
				
				/*
				 * List
				 */
				if (graphType == GraphType.List) {

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
						Resource res = entry.getResource();
						org.entrystore.List parent = (org.entrystore.List) res;

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
							GraphType btChild = childEntry.getGraphType();
							EntryType locChild = childEntry.getEntryType();
							if ((btChild == GraphType.Context || btChild == GraphType.SystemContext) && EntryType.Local.equals(locChild)) {
								childJSON.put("name", getCM().getName(childEntry.getResourceURI()));
							} else if (btChild == GraphType.List) {
								Resource childRes = childEntry.getResource();
								if (childRes != null && childRes instanceof org.entrystore.List) {
									org.entrystore.List childList = (org.entrystore.List) childRes;
									try {
										childJSON.put("size", childList.getChildren().size());
									} catch (AuthorizationException ae) {}
								} else {
									log.warn("Entry has ResourceType.List but the resource is either null or not an instance of List");
								}
							} else if (btChild == GraphType.User && locChild == EntryType.Local) {
								childJSON.put("name", ((User) childEntry.getResource()).getName());
							} else if (btChild == GraphType.Group && locChild == EntryType.Local) {
								childJSON.put("name", ((Group) childEntry.getResource()).getName());								
							}
							
							try {
								EntryType ltC = childEntry.getEntryType();
								if (EntryType.Reference.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
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

								/*
			                     * Inferred Metadata
			                     */
								JSONObject inferredChildMdObj = null;
								Metadata childInferred = entry.getInferredMetadata();
								if (childInferred != null) {
									Graph childInfG = childInferred.getGraph();
									if (childInfG != null) {
										inferredChildMdObj = new JSONObject(RDFJSON.graphToRdfJson(childInfG));
										childJSON.accumulate(RepositoryProperties.INFERRED, inferredChildMdObj);
									}
								}


								if (EntryType.Link.equals(ltC) || EntryType.Local.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
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

						resourceObj = new JSONObject();
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
				if (entry.getGraphType() == GraphType.User) {
					try {
						resourceObj = new JSONObject();
						User user = (User) entry.getResource();
						resourceObj.put("name", user.getName());

						// resourceObj.put("password", user.getSecret());

						Context homeContext = user.getHomeContext();
						if (homeContext != null) {
							resourceObj.put("homecontext", homeContext.getEntry().getId());
						}

						String prefLang = user.getLanguage();
						if (prefLang != null) {
							resourceObj.put("language", prefLang);
						}

						JSONObject customProperties = new JSONObject();
						for (java.util.Map.Entry<String, String> propEntry : user.getCustomProperties().entrySet()) {
							customProperties.put(propEntry.getKey(), propEntry.getValue());
						}
						resourceObj.put("customProperties", customProperties);
					} catch (AuthorizationException ae) {
						//jdilObj.accumulate("noAccessToResource", true);
//						TODO: Replaced by using "rights" in json, do something else in this catch-clause
					}
				}

				/*
				 * Group
				 */
				if (entry.getGraphType() == GraphType.Group) {
					try {
						resourceObj = new JSONObject();
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
				
				if (resourceObj != null) {
					jdilObj.accumulate("resource", resourceObj);
				}
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
			log.info(e.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}
		Graph deserializedGraph = null;
		if (MediaType.APPLICATION_JSON.equals(mediaType)) {
			try {
				JSONObject rdfJSON = new JSONObject(graphString);
				if (rdfJSON != null) {
					deserializedGraph = RDFJSON.rdfJsonToGraph(rdfJSON);
				}
			} catch (JSONException jsone) {
				log.info(jsone.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else {
			deserializedGraph = GraphUtil.deserializeGraph(graphString, mediaType);
		}

		if (deserializedGraph == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
			return;
		}

		if (deserializedGraph != null) {
			entry.setGraph(deserializedGraph);
			if (parameters.containsKey("applyACLtoChildren") &&
					GraphType.List.equals(entry.getGraphType()) &&
					EntryType.Local.equals(entry.getEntryType())) {
				((org.entrystore.List) entry.getResource()).applyACLtoChildren(true);
			}
			getResponse().setStatus(Status.SUCCESS_OK);
			return;
		}
	}

}