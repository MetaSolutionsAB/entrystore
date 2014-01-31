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
import org.entrystore.repository.EntryType;
import org.entrystore.repository.ResourceType;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.Group;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.User;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.repository.util.QueryResult;
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
				Set<ResourceType> resourceType = null;
				if(parameters.containsKey("resourcetype")){
					resourceType = new HashSet<ResourceType>();
					StringTokenizer tokenizer = new StringTokenizer(parameters.get("resourcetype"),",");
					while (tokenizer.hasMoreTokens()){
						String resourceTypeToken = tokenizer.nextToken();
						if("context".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.Context);
						}else if("group".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.Group);
						}else if("user".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.User);
						}else if("list".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.List);
						}else if("resultList".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.ResultList);
						}else if("string".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.String);
						}else if("none".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.None);
						}else if("graph".equalsIgnoreCase(resourceTypeToken)){
							resourceType.add(ResourceType.Graph);
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
						"PREFIX sc:<http://scam.sf.net/schema#> \n" +
						"SELECT ?g \n" +
						"WHERE { GRAPH ?g { \n" +
						" ?x sc:status \"annotated\" \n" +
						"} }");
				}
				
				String userQueryAddition = "";
				if(resourceType != null && resourceType.contains(ResourceType.User)){
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
				
//				String luceneQuerySimple = new String(
//						"PREFIX search:<" + LuceneSailSchema.NAMESPACE + "> \n" +
//						"PREFIX dcterms:<http://purl.org/dc/terms/> \n" +
//						"PREFIX dc:<http://purl.org/dc/elements/1.1/> \n" +
//						"SELECT ?x ?score WHERE { \n" +
//						"?x search:matches ?match. \n" +
//						"?match search:query \"" + queryValue + "\"; \n" +
//						//"  search:property dcterms:title; \n" +
//						"  search:score ?score. \n" +
//						"}");
//				log.info("Lucene SPARQL query:\n" + luceneQuerySimple);
				
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
						if (resourceType != null && resourceType.contains(ResourceType.User)) {
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
							if(entryType.contains(entry.getEntryType()) && resourceType.contains(entry.getResourceType())){
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
							if (resourceType.contains(entry.getResourceType())) {
								entries.add(entry);
							}
						}
						Date afterContextFilter = new Date();
						timeDiff = afterContextFilter.getTime() - after.getTime();
						log.info("Context filtering took " + timeDiff + " ms");
					} else {
						entries = searchResult;
					}
					
					// Filter for validated entries
					
					if (validatedOnly || unvalidatedOnly) {
						List<Entry> validationCheckedEntries = new ArrayList<Entry>();
						long beforeValidationCheck = new Date().getTime();
						for (Entry entry : entries) {
							if (validatedOnly) {
								if (ConverterUtil.isValidated(entry.getMetadataGraph(), entry.getResourceURI())) {
									validationCheckedEntries.add(entry);
								}
							} else if (unvalidatedOnly) {
								if (!ConverterUtil.isValidated(entry.getMetadataGraph(), entry.getResourceURI())) {
									validationCheckedEntries.add(entry);
								}
							}
						}
						entries = validationCheckedEntries;
						timeDiff = new Date().getTime() - beforeValidationCheck;
						log.info("Validation check took " + timeDiff + " ms");
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
			} else if ("competence".equalsIgnoreCase(type)) {
				StringTokenizer st = new StringTokenizer(queryValue,"}{");
				HashMap<String, String> compDef2Level = new HashMap<String, String>();
				if(st.hasMoreElements()){
					String firstString = st.nextToken().trim();
					if(firstString.startsWith("{", 0)){
						firstString = firstString.substring(1).trim();
					}
					if( firstString.length()>2){
						int divider = firstString.indexOf(" ; ");
						String compDef = firstString; 
						String compLevel = null;
						if(divider > 0 ){
							compDef = firstString.substring(0, divider);
							compLevel = firstString.substring(divider+3);
						}
						compDef2Level.put(compDef, compLevel);
					}
				}
				String last = "";
				while (st.hasMoreElements()) {
					String tokenString = st.nextToken().trim();
					int divider = tokenString.indexOf(" ;");
					String compDef = tokenString.trim();
				    String compLevel = null;
				    if(divider > 0 ){
				    	compDef = tokenString.substring(0, divider).trim();
				    	compLevel = tokenString.substring(divider+2).trim();
				    }
				    last = compDef;
				    compDef2Level.put(compDef, compLevel);
				}
				if (last.endsWith("}")) {
					compDef2Level.remove(last);
					compDef2Level.put(last.substring(0,last.length()-1), null);
				} else {
					String lastLevel = compDef2Level.get(last);
					if(lastLevel != null && lastLevel.endsWith("}") && lastLevel.length()>2){
						compDef2Level.put(last, lastLevel.substring(0,lastLevel.length()-2));
					}
				}
				entries = competenceSearch(compDef2Level);
				results = entries.size();
			} else if ("subfield".equalsIgnoreCase(type)) {
				StringTokenizer st = new StringTokenizer(queryValue,"}{");
				List<String> subfields = new ArrayList<String>();
				if(st.hasMoreElements()){
					String firstString = st.nextToken().trim();
					if(firstString.startsWith("{", 0)){
						firstString = firstString.substring(1).trim();
					}
					subfields.add(firstString);
				}
				String last = "";
				while (st.hasMoreElements()) {
					String next = st.nextToken().trim();
					subfields.add(next);
					last = next;
				}
				if (last.endsWith("}") && subfields.size()>0) {
					subfields.remove(subfields.size()-1);
					if(last.length()>2)
						subfields.add(last.substring(0,last.length()-2));
				}
				entries = subfieldSearch(subfields);
				results = entries.size();
			} else if ("solr".equalsIgnoreCase(type)) {
				if (getRM().getSolrSupport() == null) {
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
					QueryResult qResult = getRM().getSolrSupport().sendQuery(q);
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
							ResourceType btChild = e.getResourceType();
							EntryType locChild = e.getEntryType();
							if (btChild == ResourceType.Context || btChild == ResourceType.SystemContext) {
								childJSON.put("alias", getCM().getContextAlias(e.getResourceURI()));
							} else if (btChild == ResourceType.User && locChild == EntryType.Local) {
								childJSON.put("name", ((User) e.getResource()).getName());
							} else if (btChild == ResourceType.Group && locChild == EntryType.Local) {
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
				log.info("Graph fetching and JDIL serialization took " + timeDiff + " ms");

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
	
	/*
	 *The method below is a specific search-funtionality for the H-net project 
	 */
	private List<Entry> subfieldSearch(List<String> subfields){
		if(!(subfields.size() > 0)){
			return null;
		}
		ContextManager cm = getCM();
		String startOfQuery = 
			"PREFIX eha:      <http://www.ehaweb.org/rdf/passport#> \n" +
			"SELECT ?g WHERE{" +
			" GRAPH ?g {\n" ;
		String endOfQuery = "}\n}";
		for (Iterator<String> iter = subfields.iterator(); iter.hasNext();) {
			String subfield = iter.next();
			startOfQuery += "?x eha:passportSubfieldClassification <"+subfield+">.\n";
		}
		startOfQuery += endOfQuery;
		List<Entry> returnList = null;
		try{
			returnList = cm.search(null, startOfQuery, null);
		} catch(Exception e){
			e.printStackTrace();
		}
		
		return returnList;
	}
	
	/*
	 *The method below is a specific search-funtionality for the H-net project 
	 */
	private List<Entry> competenceSearch(HashMap<String, String> queryHashMap){
		ContextManager cm = getCM();
		String startOfQuery = 
			"PREFIX sc:      <http://scam.sf.net/schema#> \n" +
			"SELECT ?g WHERE{" +
			" GRAPH ?g {" +
			"?entries sc:competenceClassification ?CCLass . \n" ;
		String endOfQuery = "}\n}";
		String ExactQuery = startOfQuery + createCompetenceUnion(queryHashMap, true)+endOfQuery;
		String FuzzyQuery = startOfQuery+ createCompetenceUnion(queryHashMap, false)+endOfQuery;
		/*String mdQuery = "PREFIX sc:      <http://scam.sf.net/schema#> " +
				"SELECT ?entries ?CCLass " +
				"WHERE { " +
				"?entries sc:competenceClassification ?CCLass . " +
				"{ { ?CCLass sc:hasOutputCompetence ?PostComp1 . " +
				"      ?PostComp1 sc:competencyDefinition <http://www.ehaweb.org/rdf/passport#Item1AA> . }" +
				"      UNION" +
				"   { ?CCLass sc:hasOutputCompetence ?PostComp2 . " +
				"      ?PostComp2 sc:competencyDefinition <http://www.ehaweb.org/rdf/passport#Item1AB> . }" +
				" }" +
				"}"; */
		try{
			List<Entry> exactEntries  = cm.search(null, ExactQuery, null);
			List<Entry> fuzzyEntries  = cm.search(null,FuzzyQuery,null);
			HashMap<Entry, Integer> ranking = new HashMap<Entry, Integer>();
			HashSet<URI> uriSet = new HashSet<URI>();
			for(Entry e: exactEntries){
				ranking.put(e, new Integer(1));
				uriSet.add(e.getEntryURI());
			}
			if(fuzzyEntries.size()> exactEntries.size()){
				for(Entry f: fuzzyEntries){
					if(!uriSet.contains(f.getEntryURI())){
						ranking.put(f, new Integer(0));
					}
				}
			}
			
			Graph compGraph = getCompetenceGraph();
			if(compGraph != null) {
				//Properties used for locating the required competence
				org.openrdf.model.URI reqProp = compGraph.getValueFactory().createURI("http://scam.sf.net/schema#requiresCompetence");
				org.openrdf.model.URI compDefProp = compGraph.getValueFactory().createURI("http://scam.sf.net/schema#competencyDefinition");
				org.openrdf.model.URI compLevelProp = compGraph.getValueFactory().createURI("http://scam.sf.net/schema#competenceLevel");
				//Going through all the entries matched so far
				for (Entry currEntry : ranking.keySet()) {
					Graph mdGraph = currEntry.getMetadataGraph();
					Iterator<Value> reqObj = GraphUtil.getObjectIterator(mdGraph, (Resource)null, reqProp);
					//Going through all the required competences of the entry, if the users competence matches it will be ranked higher
					while (reqObj.hasNext()) {
						Resource obj =  (Resource) reqObj.next();
						Value compDefValue = GraphUtil.getUniqueObject(mdGraph, obj, compDefProp);
						Value compLevelValue = GraphUtil.getOptionalObject(mdGraph, obj, compLevelProp);
						
						Resource compDefHit = GraphUtil.getOptionalSubject(compGraph, compDefProp, compDefValue);
						if (compDefHit != null && compLevelValue != null){
							Value compLevelHit = GraphUtil.getOptionalObject(compGraph, compDefHit, compLevelProp);
							if(compLevelHit != null && compLevelHit.stringValue().equals(compLevelValue.stringValue())){
								Integer rank = ranking.get(currEntry) +1 ;
								ranking.put(currEntry, rank);
							}
						}
					}
				}
			}
			Collection<Integer> ranks = ranking.values();
			int max = 0;
			for (Integer eint: ranks){
				 if(eint > max){
					 max = eint;
				 }
			}
			
			List[] reverse = new ArrayList[max+1];
			for(Entry e: ranking.keySet()){
				Integer currInt = ranking.get(e);
				List currentList = reverse[currInt];
				if(currentList == null || currentList.size() == 0){
					reverse[currInt] = new ArrayList();
				}
				reverse[currInt].add(e);
			}
			List<Entry> returnList = new ArrayList<Entry>();
			if(reverse.length>0){
				for(int i = max; i>=0; i--){
					List currentRankedList = reverse[i];
					if(currentRankedList != null){
						returnList.addAll(currentRankedList);
					}
				}
			}
			return returnList;
		} catch (Exception e){
			e.printStackTrace();
		}
		return null;
	}
	
	private Graph getCompetenceGraph() {
		User user = getPM().getUser(this.getPM().getAuthenticatedUserURI());
		Entry userEntry = user.getEntry();
		List<Statement> relations = userEntry.getRelations();
		Entry compEntry = null;
		for (Iterator<Statement> iter = relations.iterator(); iter.hasNext();) {
			Statement stat = iter.next();
			if (stat.getPredicate().toString().equals("http://scam.sf.net/schema#aboutPerson")){
				String relEntryString = stat.getSubject().toString();
				try{
					compEntry = getCM().getEntry(new URI(relEntryString));
				} catch (URISyntaxException urise){
					return null;
				}
					break;
			}
		}
		if(compEntry != null){
			return compEntry.getMetadataGraph();
		}
		return null;
	}

	private String createCompetenceUnion(HashMap<String, String> queryHashMap, boolean makeExact) {
		Set<String> keys=  queryHashMap.keySet();
		String returnString = "{ ";
		
		int i = 0;
		boolean first = true;
		for (Iterator<String> iter = keys.iterator(); iter.hasNext();) {
			String compDefURI = iter.next();
			String compLevelURI = queryHashMap.get(compDefURI);
			if(!first){
				returnString +="UNION \n";
			}
			first = false;
			returnString += "{ ?CCLass sc:hasOutputCompetence ?PostComp"+i+"  . \n" +
					"?PostComp"+i+" sc:competencyDefinition <"+compDefURI+"> . \n";
			if(makeExact && compLevelURI != null && compLevelURI.length()>0){
				returnString += "?PostComp"+i+" sc:competenceLevel <"+compLevelURI+"> . }\n";
			}else {
				returnString+="} \n";
			}
			i++;
		}
		returnString += "}";
		return returnString;
	}

}