/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.common.SolrException;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.User;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.AuthorizationException;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.util.GraphUtil;
import org.openrdf.model.vocabulary.RDF;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
			} else if ("simple".equalsIgnoreCase(type)) {
				// check for context URI in query				
				URI contextURI = null;
				if (parameters.containsKey("context")) {
					try {
						contextURI = new URI(URLDecoder.decode(parameters.get("context"), "UTF-8"));
					} catch (Exception ignored) {
						log.warn(ignored.getMessage());
					}
				}
				List<URI> contextList = null;
				if (contextURI != null) {
					contextList = new ArrayList<URI>(1);
					contextList.add(contextURI);
				}
				
				// check language in query
				String lang = null;
				if (parameters.containsKey("lang")) {
					lang = parameters.get("lang");
				}
				
				boolean validatedOnly = false;
				boolean unvalidatedOnly = false;
				boolean readyForValidation = false;
				if (parameters.containsKey("validation")) {
					String validation = parameters.get("validation");
					if ("validated".equalsIgnoreCase(validation)) {
						validatedOnly = true;
					} else if ("unvalidated".equalsIgnoreCase(validation)) {
						unvalidatedOnly = true;
					} else if ("annotated".equalsIgnoreCase(validation)) {
						readyForValidation = true;
					}
				}
				
				// filter for EntryType
				Set<EntryType> entryType = null;
				if (parameters.containsKey("entrytype")) {
					entryType = new HashSet<EntryType>();
					StringTokenizer tok = new StringTokenizer(parameters.get("entrytype"), ",");
					while (tok.hasMoreTokens()) {
						String entryTypeToken = tok.nextToken();
						if ("Reference".equalsIgnoreCase(entryTypeToken)) {
							entryType.add(EntryType.Reference);
						} else if ("LinkReference".equalsIgnoreCase(entryTypeToken)) {
							entryType.add(EntryType.LinkReference);
						} else if ("Link".equalsIgnoreCase(entryTypeToken)) {
							entryType.add(EntryType.Link);
						} else if ("Local".equalsIgnoreCase(entryTypeToken)) {
							entryType.add(EntryType.Local);
						}
					}
				}
				Set<GraphType> resourceType = null;
				if(parameters.containsKey("resourcetype")){
					resourceType = new HashSet<GraphType>();
					StringTokenizer tokenizer = new StringTokenizer(parameters.get("resourcetype"),",");
					while (tokenizer.hasMoreTokens()){
						String resourceTypeToken = tokenizer.nextToken();
						if("context".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.Context);
						}else if("group".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.Group);
						}else if("user".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.User);
						}else if("list".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.List);
						}else if("resultList".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.ResultList);
						}else if("string".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.String);
						}else if("none".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.None);
						}else if("graph".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(GraphType.Graph);
						}
					}
				}

				String filter = "regex(str(?y), \"" + queryValue + "\", \"i\")";
				if (lang != null) {
					filter = " langMatches(lang(?y),\"" + lang + "\") && " + filter;
				}
				if (contextURI != null) {
					String contextFilter = contextURI.toString();
					if (!contextFilter.endsWith("/")) {
						contextFilter += "/";
					}
					filter = "regex(str(?g), \"^" + contextFilter + "\", \"\") && " + filter;
				}
				
				String entryQuery = null;

				if (readyForValidation) {
					entryQuery = new String(
						"PREFIX es:<http://entrystore.org/terms/> \n" +
						"SELECT ?g \n" +
						"WHERE { GRAPH ?g { \n" +
						" ?x es:status \"annotated\" \n" +
						"} }");
				}
				
				String userQueryAddition = "";
				if(resourceType != null && resourceType.contains(GraphType.User)){
					userQueryAddition += " UNION {?x foaf:name ?y} UNION {?x foaf:surname ?y} UNION {?x foaf:firstname ?y} ";
				}
				
				String metadataQuery = new String(
						"PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#> \n" +
						"PREFIX dcterms:<http://purl.org/dc/terms/> \n" +
						"PREFIX dc:<http://purl.org/dc/elements/1.1/> \n" +
						"PREFIX foaf:<http://xmlns.com/foaf/0.1/> \n" +
						"PREFIX lom:<" + NS.lom + "> \n" +
						"SELECT DISTINCT ?g \n" +
						"WHERE { GRAPH ?g { \n" +
						"{ ?x dc:title ?y } UNION \n" +
						"{ ?x dc:description ?y } UNION \n" +
						"{ ?x dcterms:title ?y } UNION \n" +
						"{ ?x dcterms:description ?b. \n" +
						"  ?b rdf:value ?y } UNION \n" +
						"{ ?x lom:keyword ?b. \n" +
						"  ?b rdf:value ?y } UNION \n" +
						"{ ?x dc:subject ?y } \n" + 
						userQueryAddition+
						"FILTER(" + filter + ") \n" +
						" } } \n" +
						"ORDER BY ASC(?g)");
//						"ORDER BY ASC(?g) \n" +
//						"LIMIT " + limit + "\n" + // FIXME temporarily disabled
//						"OFFSET " + offset); // FIXME temp disabled

				try {
					String storeType = getRM().getConfiguration().getString(Settings.STORE_TYPE);
					
					Date before = new Date();
					List<Entry> searchResult = null;
					if ("memory".equalsIgnoreCase(storeType) || "native".equalsIgnoreCase(storeType)) {
						log.info("Performing literal iteration");
						Set<org.openrdf.model.URI> preds = new HashSet<org.openrdf.model.URI>();
						preds.add(new URIImpl(NS.dc + "title"));
						preds.add(new URIImpl(NS.dc + "subject"));
						preds.add(new URIImpl(NS.dc + "description"));
						preds.add(new URIImpl(NS.dcterms + "title"));
						preds.add(RDF.VALUE);
						if (resourceType != null && resourceType.contains(GraphType.User)) {
							preds.add(new URIImpl(NS.foaf + "name"));
							preds.add(new URIImpl(NS.foaf + "surname"));
							preds.add(new URIImpl(NS.foaf + "firstname"));
						}
						searchResult = new ArrayList<Entry>(getCM().searchLiterals(preds, queryValue.split(" "), lang, contextList, true).keySet());
					} else {
						log.info("Performing SPARQL search");
						searchResult = new ArrayList<Entry>(getCM().search(entryQuery, metadataQuery, contextList));	
					}
					Date after = new Date();
					long timeDiff = after.getTime() - before.getTime();
					log.info("Query took " + timeDiff + " ms");
					log.info("Returned " + searchResult.size() + " results");
					
					// Filter for EntryType
					
					if (entryType != null && resourceType != null){
						entries = new ArrayList<Entry>();
						for(Entry entry : searchResult){
							if(entryType.contains(entry.getEntryType()) && resourceType.contains(entry.getGraphType())){
								entries.add(entry);
							}
						}
						Date afterContextFilter = new Date();
						timeDiff = afterContextFilter.getTime() - after.getTime();
						log.info("Context filtering took " + timeDiff + " ms (both ResourceType and EntryType)");
					} else if (entryType != null) {
						entries = new ArrayList<Entry>();
						for (Entry entry : searchResult) {
							if (entryType.contains(entry.getEntryType())) {
								entries.add(entry);
							}
						}
						Date afterContextFilter = new Date();
						timeDiff = afterContextFilter.getTime() - after.getTime();
						log.info("Context filtering took " + timeDiff + " ms (only entry type)");
					} else if (resourceType != null) {
						entries = new ArrayList<Entry>();
						for (Entry entry : searchResult) {
							if (resourceType.contains(entry.getGraphType())) {
								entries.add(entry);
							}
						}
						Date afterContextFilter = new Date();
						timeDiff = afterContextFilter.getTime() - after.getTime();
						log.info("Context filtering took " + timeDiff + " ms");
					} else {
						entries = searchResult;
					}
					
					results = entries.size();
				} catch (Exception e1) {
					log.error(e1.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					try {
						return new JsonRepresentation("{\"error\":\"" + URLEncoder.encode(e1.getMessage(), "UTF-8") + "\"}");
					} catch (UnsupportedEncodingException ignored) {
						return new JsonRepresentation("{\"error\": \"Unknown error\"}");
					} catch (NullPointerException npe) {
						return new JsonRepresentation("{\"error\": \"Unknown error\"}");
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
				
				SolrQuery q = new SolrQuery(queryValue);
				q.setStart(offset);
				q.setRows(limit);
				
				if (sorting != null) {
					String[] sortFields = sorting.split(",");
					for (String string : sortFields) {
						String[] fieldAndOrder = string.split(" ");
						if (fieldAndOrder.length == 2) {
							String field = fieldAndOrder[0];
							if ("title".equalsIgnoreCase(field)) {
								// we need this hack to be able to sort after
								// title as this requires untokenized field type
								// string (title_sort) instead of text (title)
								field = "title_exact";
							}
							ORDER order = ORDER.asc;
							try {
								order = ORDER.valueOf(fieldAndOrder[1].toLowerCase());
							} catch (IllegalArgumentException iae) {
								log.warn("Unable to parse sorting value, using ascending by default");
							}
							q.addSortField(field, order);
						}
					}
				} else {
					q.addSortField("score", ORDER.desc);
					q.addSortField("modified", ORDER.desc);
				}
				
				try {
					QueryResult qResult = ((SolrSearchIndex) getRM().getIndex()).sendQuery(q);
					entries = new LinkedList<Entry>(qResult.getEntries());
					results = qResult.getHits();
				} catch (SolrException se) {
					log.warn(se.getMessage());
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return new StringRepresentation("{\"error\":\"Search failed due to wrong parameters\"}", MediaType.APPLICATION_JSON);
				}
			}

			try {
				// the following iteration adds pagination support
				if (!"solr".equalsIgnoreCase(type)) {
					List<Entry> truncatedEntries = new ArrayList<Entry>();
					if (offset < entries.size()) {
						int i = 0;
						for (Entry entry : entries) {
							i++;
							if (i > (offset + limit)) {
								break;
							}
							if (i >= (offset + 1)) {
								truncatedEntries.add(entry);
							}
						}
						entries = truncatedEntries;
					}
				}
				// <- pagination
				
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
								childJSON.put("name", ((User) e.getResource()).getName());
							} else if (btChild == GraphType.Group && locChild == EntryType.Local) {
								childJSON.put("name", ((Group) e.getResource()).getName());								
							}
							PrincipalManager PM = this.getPM();
							Set<AccessProperty> rights =PM.getRights(e);
							if(rights.size() >0){
								JSONArray ja = new JSONArray();
								for(AccessProperty ap : rights){
									if(ap == PrincipalManager.AccessProperty.Administer)
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
								Graph childRelationsGraph = new GraphImpl(e.getRelations());
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
				
				JSONArray jaRights = new JSONArray();
				jaRights.put("readmetadata");
				jaRights.put("readresource");
				result.put("rights", jaRights);
				
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