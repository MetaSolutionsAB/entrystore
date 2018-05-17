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

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.common.SolrException;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.LinkedHashModel;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;


/**
 * Handles searches
 * 
 * @author Hannes Ebner
 */
public class SearchResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(SearchResource.class);
	
	@Override
	public void doInit() {
		
	}

	@Get
	public Representation represent() throws ResourceException {
		try {
			if (!parameters.containsKey("type") ||
					!parameters.containsKey("query") ||
					parameters.get("type") == null ||
					parameters.get("query") == null) {
				log.info("Got invalid query");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Invalid query\"}");
			}
			
			List<Entry> entries = new ArrayList<Entry>();
			String type = parameters.get("type").toLowerCase();
			String queryValue = null;
			try {
				queryValue = URLDecoder.decode(parameters.get("query"), "UTF-8");
			} catch (UnsupportedEncodingException e1) {
				log.error(e1.getMessage());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return new JsonRepresentation("{\"error\":\"" + e1.getMessage() + "\"}");
			}
			
			if (queryValue.length() < 3) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Query too short\"}");
			}
			
			int offset = 0;
			int limit = 50;
			long results = 0;
			List<FacetField> responseFacetFields = null;
			
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

			if ("sparql".equalsIgnoreCase(type)) {
				try {
					String query = new String(
							"PREFIX dc:<http://purl.org/dc/terms/> " +
							"SELECT ?x " +
							"WHERE { " +
							"?x " + queryValue + " ?y }"); 
					entries = getCM().search(query, null, null);
				} catch (Exception e) {
					log.error(e.getMessage());
					try {
						return new JsonRepresentation("{\"error\":\"" + URLEncoder.encode(e.getMessage(), "UTF-8") + "\"}");
					} catch (UnsupportedEncodingException ignored) {
						return new JsonRepresentation("error");
					}
				}
			} else if ("solr".equalsIgnoreCase(type)) {
				if (getRM().getIndex() == null) {
					getResponse().setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE, "Solr search deactivated");
					return new JsonRepresentation("{\"error\":\"Solr search is deactivated\"}");
				}
				
				String sorting = null;
				if (parameters.containsKey("sort")) {
					try {
						sorting = URLDecoder.decode(parameters.get("sort"), "UTF-8");
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
				}

				String facetFields = null;
				if (parameters.containsKey("facetFields")) {
					try {
						facetFields = URLDecoder.decode(parameters.get("facetFields"), "UTF-8");
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
				}

				List<String> filterQueries = new ArrayList();
				if (parameters.containsKey("filterQuery")) {
					try {
						// We URLDecode after the split because we want to be able to use comma
						// as separator (unencoded) for FQs and as content inside FQs (encoded)
						for (String fq : parameters.get("filterQuery").split(",")) {
							filterQueries.add(URLDecoder.decode(fq, "UTF-8"));
						}
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
				}

				int facetMinCount = 1;
				if (parameters.containsKey("facetMinCount")) {
					try {
						facetMinCount = Integer.valueOf(parameters.get("facetMinCount"));
					} catch (NumberFormatException nfe) {
						log.info(nfe.getMessage());
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						return null;
					}
				}
				
				SolrQuery q = new SolrQuery(queryValue);
				q.setStart(offset);
				q.setRows(limit);
				
				if (sorting != null) {
					for (String string : sorting.split(",")) {
						String[] fieldAndOrder = string.split(" ");
						if (fieldAndOrder.length == 2) {
							String field = fieldAndOrder[0];
							if (field.startsWith("title.")) {
								field = field.replace("title.", "title_sort.");
							}
							ORDER order = ORDER.asc;
							try {
								order = ORDER.valueOf(fieldAndOrder[1].toLowerCase());
							} catch (IllegalArgumentException iae) {
								log.warn("Unable to parse sorting value, using ascending by default");
							}
							q.addSort(field, order);
						}
					}
				} else {
					q.addSort("score", ORDER.desc);
					q.addSort("modified", ORDER.desc);
				}

				if (facetFields != null) {
					q.setFacet(true);
					q.setFacetMinCount(facetMinCount);
					for (String ff : facetFields.split(",")) {
						q.addFacetField(ff.replace("metadata.predicate.literal.", "metadata.predicate.literal_s."));
					}
				}

				for (String fq : filterQueries) {
					q.addFilterQuery(fq);
				}
				
				try {
					QueryResult qResult = ((SolrSearchIndex) getRM().getIndex()).sendQuery(q);
					entries = new LinkedList<Entry>(qResult.getEntries());
					results = qResult.getHits();
					responseFacetFields = qResult.getFacetFields();
				} catch (SolrException se) {
					log.warn(se.getMessage());
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return new StringRepresentation("{\"error\":\"Search failed due to wrong parameters\"}", MediaType.APPLICATION_JSON);
				}
			}

			try {
				Date before = new Date();
				JSONArray children = new JSONArray();
				if (entries != null) {
					for (Entry e : entries) {
						if (e != null) {
							JSONObject childJSON = new JSONObject(); 
							childJSON.put("entryId", e.getId());
							childJSON.put("contextId", e.getContext().getEntry().getId());
							GraphType btChild = e.getGraphType();
							EntryType locChild = e.getEntryType();
							if (btChild == GraphType.Context || btChild == GraphType.SystemContext) {
								childJSON.put("alias", getCM().getName(e.getResourceURI()));
							} else if (btChild == GraphType.User && locChild == EntryType.Local) {
								User u = (User) e.getResource();
								childJSON.put("name", u.getName());
								if (u.isDisabled()) {
									childJSON.put("disabled", true);
								}
							} else if (btChild == GraphType.Group && locChild == EntryType.Local) {
								childJSON.put("name", ((Group) e.getResource()).getName());								
							}
							PrincipalManager PM = this.getPM();
							Set<AccessProperty> rights =PM.getRights(e);
							if (rights.size() > 0) {
								for(AccessProperty ap : rights){
									if (ap == PrincipalManager.AccessProperty.Administer)
										childJSON.append("rights", "administer");
									else if (ap == PrincipalManager.AccessProperty.WriteMetadata)
										childJSON.append("rights", "writemetadata");
									else if (ap == PrincipalManager.AccessProperty.WriteResource)
										childJSON.append("rights","writeresource");
									else if (ap == PrincipalManager.AccessProperty.ReadMetadata)
										childJSON.append("rights","readmetadata");
									else if (ap == PrincipalManager.AccessProperty.ReadResource)
										childJSON.append("rights","readresource");
								}
							}

							try {
								EntryType ltC = e.getEntryType();
								if (EntryType.Reference.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
									// get the external metadata
									Metadata cachedExternalMD = e.getCachedExternalMetadata();
									if (cachedExternalMD != null) {
										Graph cachedExternalMDGraph = cachedExternalMD.getGraph();
										if (cachedExternalMDGraph != null) {
											JSONObject childCachedExternalMDJSON = new JSONObject(RDFJSON.graphToRdfJson(cachedExternalMDGraph));
											childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, childCachedExternalMDJSON);
										}
									}
								}
								
								if (EntryType.Link.equals(ltC) || EntryType.Local.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
									// get the local metadata
									Metadata localMD = e.getLocalMetadata();
									if (localMD != null) {
										Graph localMDGraph = localMD.getGraph();
										if (localMDGraph != null) {
											JSONObject localMDJSON = new JSONObject(RDFJSON.graphToRdfJson(localMDGraph));
											childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
										}
									}
								}
							} catch (AuthorizationException ae) {
								childJSON.accumulate("noAccessToMetadata", true);
							}

							JSONObject childInfo = new JSONObject(RDFJSON.graphToRdfJson(e.getGraph()));
							if (childInfo != null) {
								childJSON.accumulate("info", childInfo);   
							} else {
								childJSON.accumulate("info", new JSONObject());
							}
							
							if (e.getRelations() != null) {
								Graph childRelationsGraph = new LinkedHashModel(e.getRelations());
								JSONObject childRelationObj = new JSONObject(RDFJSON.graphToRdfJson(childRelationsGraph));
								childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
							}
							children.put(childJSON);
						}
					}
				}
				
				JSONObject result = new JSONObject();
				JSONObject resource = new JSONObject();
				resource.put("children", children);
				result.put("resource", resource);
				result.put("results", results);
				result.put("limit", limit);
				result.put("offset", offset);

				JSONArray facetFieldsArr = new JSONArray();
				for (FacetField ff : responseFacetFields) {
					JSONObject ffObj = new JSONObject();
					ffObj.put("name", ff.getName());
					ffObj.put("valueCount", ff.getValueCount());
					JSONArray ffValArr = new JSONArray();
					for (FacetField.Count ffVal : ff.getValues()) {
						JSONObject ffValObj = new JSONObject();
						ffValObj.put("name", ffVal.getName());
						ffValObj.put("count", ffVal.getCount());
						ffValArr.put(ffValObj);
					}
					ffObj.put("values", ffValArr);
					facetFieldsArr.put(ffObj);
				}
				result.put("facetFields", facetFieldsArr);

				// TODO remove the commented four lines below if there is no use found for it
				/*
				JSONArray jaRights = new JSONArray();
				jaRights.put("readmetadata");
				jaRights.put("readresource");
				result.put("rights", jaRights);
				*/
				
				long timeDiff = new Date().getTime() - before.getTime();
				log.debug("Graph fetching and serialization took " + timeDiff + " ms");

				return new JsonRepresentation(result.toString(2));
			} catch (JSONException e) {
				log.error(e.getMessage());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return new JsonRepresentation("\"error\"");
			}
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}