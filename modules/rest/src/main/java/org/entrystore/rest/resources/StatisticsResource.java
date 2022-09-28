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

import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.util.GraphUtil;
import org.eclipse.rdf4j.model.util.GraphUtilException;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.JSONErrorMessages;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class StatisticsResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(StatisticsResource.class);

	/** The entrys ID. */
	String statType = null;

	String labels = null;
	
	Config config = null;
	
	public static String STAT_CONFIG_KEY = "se.kmr.scam.rest.statistics";

	@Override
	public void doInit() {
		this.statType = (String) getRequest().getAttributes().get("stat-type");
		this.labels = parameters.get("labels");
		
		this.config = (Config) getContext().getAttributes().get(STAT_CONFIG_KEY);
		if (this.config == null) {
			ConfigurationManager confManager = null;
			try {
				URI confURI = ConfigurationManager.getConfigurationURI("statistics.properties");
				log.info("Attempting to load configuration from " + confURI);
				confManager = new ConfigurationManager(confURI);
			} catch (IOException e) {
				log.error("Unable to load configuration: " + e.getMessage());
				return;
			}
			this.config = confManager.getConfiguration();
			if (this.config != null) {
				getContext().getAttributes().put(STAT_CONFIG_KEY, this.config);
			} else {
				log.error("Could not load configuration");
			}
		}
	}

	@Get
	public Representation represent() {
		try {
			if (context == null) {
				log.error("Cannot find a context with that ID");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(JSONErrorMessages.errorWrongContextIDmsg);
			}
			
			JSONObject result = new JSONObject();

			try {
				if ("properties".equals(statType)) {
					Date before = new Date();
					result = getPropertyStatistics(context);
					Date after = new Date();
					log.info("Creation of property statistics took " + (after.getTime() - before.getTime()) + " ms");
				} else if ("ontology".equals(statType)) {
					Date before = new Date();
					result = getOntologyStatistics(context);
					Date after = new Date();
					log.info("Creation of ontology statistics took " + (after.getTime() - before.getTime()) + " ms");
				} else if ("keywords".equals(statType)) {
					Date before = new Date();
					result = getKeywordStatistics(context);
					Date after = new Date();
					log.info("Creation of keyword statistics took " + (after.getTime() - before.getTime()) + " ms");
				} else if ("propertiesDetailed".equals(statType)) {
					result.put("error", "This statistics type is not supported yet");
				} else if ("relations".equals(statType)) {
					result.put("error", "This statistics type is not supported yet");
				} else if ("competence".equals(statType)){
					Date before = new Date();
					result = getCompetenceStatistics(context);
					Date after = new Date();
					log.info("Creation of keyword statistics took " + (after.getTime() - before.getTime()) + " ms");
				} else if ("type".equals(statType)) {
					result.put("error", "This statistics type is not supported yet");
				} else {
					result.put("error", "Unknown statistics type");
				}
				
				return new StringRepresentation(result.toString(2), MediaType.APPLICATION_JSON);
			} catch (JSONException e) {
				log.warn(e.getMessage());
				return new JsonRepresentation("{\"error\":\"" + e.getMessage() + "\"");
			}
		} catch (AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}
	
	public JSONObject getPropertyStatistics(Context context) {
		JSONObject result = new JSONObject();
		ValueFactory vf = getRM().getValueFactory();
		
		URI currentUserURI = getPM().getAuthenticatedUserURI();
		getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
		try {
			Set<URI> entries = context.getEntries();
			
			// Map<predicate, Set<entryID>>
			Map<String, Set<String>> propertyUsedIn = new HashMap<String, Set<String>>();
			
			Map<String, Integer> propertyUsage = new HashMap<String, Integer>();
			int validatedCount = 0;
			int entryCount = 0;
			int mandatoryCount = 0;
			int recommendedCount = 0;
			Map<String, String> labelMap = getLabels(labels);
			
			Set<String> mandatoryProperties = null;
			Set<String> recommendedProperties = null;
			if (parameters.containsKey("profile")) { 
				mandatoryProperties = getPropertyGroup(parameters.get("profile"), "mandatory");
				recommendedProperties = getPropertyGroup(parameters.get("profile"), "recommended");
			}
			
			for (URI uri : entries) {
				Entry entry = context.getByEntryURI(uri);
				if (entry == null || !GraphType.None.equals(entry.getGraphType()) || EntryUtil.isDeleted(entry)) {
					continue;
				}
				
				entryCount++;
				Set<String> entryProperties = new HashSet<String>();
				String entryID = entry.getId();
				URI resourceURI = entry.getResourceURI();
				Graph metadata = entry.getMetadataGraph();
				Iterator<Statement> firstLevel = metadata.match(vf.createIRI(resourceURI.toString()), null, null);
				while (firstLevel.hasNext()) {
					IRI predicate = firstLevel.next().getPredicate();
					String predStr = predicate.toString();
					if (labelMap != null && labelMap.containsKey(predStr)) {
						predStr = labelMap.get(predStr);
					}
					
					entryProperties.add(predStr);
					
					// propertyUsage
					int count = 0;
					if (propertyUsage.containsKey(predStr)) {
						count = propertyUsage.get(predStr);
					}
					propertyUsage.put(predStr, ++count);
					
					// usedIn
					if (propertyUsedIn.containsKey(predStr)) {
						Set<String> usedIn = propertyUsedIn.get(predStr);
						usedIn.add(entryID);
						propertyUsedIn.put(predStr, usedIn);
					} else {
						Set<String> usedIn = new HashSet<String>();
						usedIn.add(entryID);
						propertyUsedIn.put(predStr, usedIn);
					}
				}

				if (mandatoryProperties != null && hasAllPropertiesOfGroup(mandatoryProperties, entryProperties)) {
					mandatoryCount++;
				}
				
				if (recommendedProperties != null && hasAllPropertiesOfGroup(recommendedProperties, entryProperties)) {
					recommendedCount++;
				}
			}
			
			// construct JSON
			
			result.put("entryCount", entryCount);
			result.put("entryCountValidated", validatedCount);
			
			if (mandatoryProperties != null) {
				result.put("entryCountMandatory", mandatoryCount);
			}
			if (recommendedProperties != null) {
				result.put("entryCountRecommended", recommendedCount);
			}
			
			JSONArray propertyUsageArray = new JSONArray();
			
			Set<String> properties = propertyUsage.keySet();
			for (String key : properties) {
				JSONObject propStats = new JSONObject();
				propStats.put("property", key);
				int statementCount = propertyUsage.get(key).intValue();
				int usedInCount = propertyUsedIn.get(key).size();
				propStats.put("statements", statementCount);
				propStats.put("usedInCount", usedInCount);
				propertyUsageArray.put(propStats);
			}
			result.put("propertyUsage", propertyUsageArray);
		} catch (JSONException e) {
			log.error(e.getMessage());
		} finally {
			getPM().setAuthenticatedUserURI(currentUserURI);
		}
		
		return result;
	}
	
	public JSONObject getOntologyStatistics(Context context) {
		JSONObject result = new JSONObject();
		ValueFactory vf = getRM().getValueFactory();
		
		URI currentUserURI = getPM().getAuthenticatedUserURI();
		getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
		try {
			Set<URI> entries = context.getEntries();
			
			// Map<predicate, Set<entryID>>
			Map<String, Set<String>> predicateUsedIn = new HashMap<String, Set<String>>();
			Map<String, Set<String>> ontologyTermUsedIn = new HashMap<String, Set<String>>();
			
			Map<String, Integer> ontPredUsage = new HashMap<String, Integer>();
			Map<String, Integer> ontTermUsage = new HashMap<String, Integer>();
			int entryCount = 0;
			Set<URI> entriesWithOntTerm = new HashSet<URI>();
			
			for (URI uri : entries) {
				Entry entry = context.getByEntryURI(uri);
				if (entry == null || !GraphType.None.equals(entry.getGraphType()) || EntryUtil.isDeleted(entry)) {
					continue;
				}
				
				Set<IRI> allowedPredicates = new HashSet<>();
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Supports"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesAlternativeViewOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesExamplesOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Methodology"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Summarizes"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesDataOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesBackgroundOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Details"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#IsAbout"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesTheoreticalInformationOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesNewInformationOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#CommentsOn"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Refutes"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#Explains"));
				allowedPredicates.add(vf.createIRI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesExamplesOf"));
				
				entryCount++;
				Set<String> ontPredicates = new HashSet<String>();
				Set<String> ontTerms = new HashSet<String>();
				String entryID = entry.getId();
				URI resourceURI = entry.getResourceURI();
				Graph metadata = entry.getMetadataGraph();
				Iterator<Statement> firstLevel = metadata.match(vf.createIRI(resourceURI.toString()), null, null);
				while (firstLevel.hasNext()) {
					Statement stmnt = firstLevel.next();
					IRI predicate = stmnt.getPredicate();
					
					if (!allowedPredicates.contains(predicate)) {
						continue;
					}
					
					entriesWithOntTerm.add(resourceURI);
					
					String predStr = predicate.stringValue().substring(predicate.stringValue().lastIndexOf("#") + 1);
					Value object = stmnt.getObject();
					String objStr = object.stringValue().substring(object.stringValue().lastIndexOf("#") + 1);
					
					ontPredicates.add(predStr);
					ontTerms.add(objStr);
					
					// ontology predicate usage
					int ontPredCount = 0;
					if (ontPredUsage.containsKey(predStr)) {
						ontPredCount = ontPredUsage.get(predStr);
					}
					ontPredUsage.put(predStr, ++ontPredCount);
					
					// ontology term usage
					int ontTermCount = 0;
					if (ontTermUsage.containsKey(objStr)) {
						ontTermCount = ontTermUsage.get(objStr);
					}
					ontTermUsage.put(objStr, ++ontTermCount);
					
					// usedIn
					if (predicateUsedIn.containsKey(predStr)) {
						Set<String> usedIn = predicateUsedIn.get(predStr);
						usedIn.add(entryID);
						predicateUsedIn.put(predStr, usedIn);
					} else {
						Set<String> usedIn = new HashSet<String>();
						usedIn.add(entryID);
						predicateUsedIn.put(predStr, usedIn);
					}
					
					if (ontologyTermUsedIn.containsKey(objStr)) {
						Set<String> usedIn = ontologyTermUsedIn.get(objStr);
						usedIn.add(entryID);
						ontologyTermUsedIn.put(objStr, usedIn);
					} else {
						Set<String> usedIn = new HashSet<String>();
						usedIn.add(entryID);
						ontologyTermUsedIn.put(objStr, usedIn);
					}
				}
			}
			
			// construct JSON
			
			result.put("entryCount", entryCount);
			result.put("entryCountWithOntologyTerm", entriesWithOntTerm.size());
			
			JSONArray ontPredUsageArray = new JSONArray();
			Set<String> predicates = ontPredUsage.keySet();
			for (String key : predicates) {
				JSONObject propStats = new JSONObject();
				propStats.put("title", key);
				int ontPredCount = ontPredUsage.get(key).intValue();
				int usedInCount = predicateUsedIn.get(key).size();
				propStats.put("totalCount", ontPredCount);
				propStats.put("usedInCount", usedInCount);
				ontPredUsageArray.put(propStats);
			}
			result.put("predicateUsage", ontPredUsageArray);
			
			JSONArray ontTermUsageArray = new JSONArray();
			Set<String> ontologyTerms = ontTermUsage.keySet();
			for (String key : ontologyTerms) {
				JSONObject propStats = new JSONObject();
				propStats.put("title", key);
				int ontTermCount = ontTermUsage.get(key).intValue();
				int usedInCount = ontologyTermUsedIn.get(key).size();
				propStats.put("totalCount", ontTermCount);
				propStats.put("usedInCount", usedInCount);
				ontTermUsageArray.put(propStats);
			}
			result.put("ontologyTermUsage", ontTermUsageArray);
			
		} catch (JSONException e) {
			log.error(e.getMessage());
		} finally {
			getPM().setAuthenticatedUserURI(currentUserURI);
		}
		
		return result;
	}
	
	public JSONObject getKeywordStatistics(Context context) {
		JSONObject result = new JSONObject();
		ValueFactory vf = getRM().getValueFactory();
		
		URI currentUserURI = getPM().getAuthenticatedUserURI();
		getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
		try {
			Set<URI> entries = context.getEntries();
			
			// Map<predicate, Set<entryID>>
			Map<String, Set<String>> keywordUsedIn = new HashMap<String, Set<String>>();
			
			Map<String, Integer> keywordUsage = new HashMap<String, Integer>();
			int entryCount = 0;
			Set<URI> entriesWithKeyword = new HashSet<URI>();
			
			for (URI uri : entries) {
				Entry entry = context.getByEntryURI(uri);
				if (entry == null || !GraphType.None.equals(entry.getGraphType()) || EntryUtil.isDeleted(entry)) {
					continue;
				}
				
				Set<IRI> allowedPredicates = new HashSet<>();
				allowedPredicates.add(vf.createIRI(NS.dc + "subject"));
				allowedPredicates.add(vf.createIRI(NS.dcterms + "subject"));
				
				entryCount++;
				Set<String> keywords = new HashSet<String>();
				String entryID = entry.getId();
				URI resourceURI = entry.getResourceURI();
				Graph metadata = entry.getMetadataGraph();
				Iterator<Statement> firstLevel = metadata.match(vf.createIRI(resourceURI.toString()), null, null);
				while (firstLevel.hasNext()) {
					Statement stmnt = firstLevel.next();
					IRI predicate = stmnt.getPredicate();
					
					if (!allowedPredicates.contains(predicate)) {
						continue;
					}
					
					entriesWithKeyword.add(resourceURI);
					
					Value object = stmnt.getObject();
					
					if (object instanceof Resource) {
						Iterator<Statement> secondLevel = metadata.match((Resource) object, RDF.VALUE, null);
						while (secondLevel.hasNext()) {
							Value o2 = secondLevel.next().getObject();
							if (o2 instanceof Literal) {
								String objStr = o2.stringValue().substring(o2.stringValue().lastIndexOf("#") + 1).toLowerCase();
								keywords.add(objStr);
								
								int keywordCount = 0;
								if (keywordUsage.containsKey(objStr)) {
									keywordCount = keywordUsage.get(objStr);
								}
								keywordUsage.put(objStr, ++keywordCount);
								
								if (keywordUsedIn.containsKey(objStr)) {
									Set<String> usedIn = keywordUsedIn.get(objStr);
									usedIn.add(entryID);
									keywordUsedIn.put(objStr, usedIn);
								} else {
									Set<String> usedIn = new HashSet<String>();
									usedIn.add(entryID);
									keywordUsedIn.put(objStr, usedIn);
								}
							}
						}
					} else if (object instanceof Literal) {
						String objStr = object.stringValue().substring(object.stringValue().lastIndexOf("#") + 1).toLowerCase();
						keywords.add(objStr);
						
						int keywordCount = 0;
						if (keywordUsage.containsKey(objStr)) {
							keywordCount = keywordUsage.get(objStr);
						}
						keywordUsage.put(objStr, ++keywordCount);
						
						if (keywordUsedIn.containsKey(objStr)) {
							Set<String> usedIn = keywordUsedIn.get(objStr);
							usedIn.add(entryID);
							keywordUsedIn.put(objStr, usedIn);
						} else {
							Set<String> usedIn = new HashSet<String>();
							usedIn.add(entryID);
							keywordUsedIn.put(objStr, usedIn);
						}
					}
				}
			}
			
			// construct JSON
			
			result.put("entryCount", entryCount);
			result.put("entryCountWithKeyword", entriesWithKeyword.size());
			
			JSONArray keywordUsageArray = new JSONArray();
			Set<String> keywords = keywordUsage.keySet();
			for (String key : keywords) {
				JSONObject propStats = new JSONObject();
				propStats.put("title", key);
				int keywordCount = keywordUsage.get(key).intValue();
				int usedInCount = keywordUsedIn.get(key).size();
				propStats.put("totalCount", keywordCount);
				propStats.put("usedInCount", usedInCount);
				keywordUsageArray.put(propStats);
			}
			result.put("keywordUsage", keywordUsageArray);
			
		} catch (JSONException e) {
			log.error(e.getMessage());
		} finally {
			getPM().setAuthenticatedUserURI(currentUserURI);
		}
		
		return result;
	}
	
	private boolean hasAllPropertiesOfGroup(Set<String> group, Set<String> entryProperties) {
		if (group == null) {
			return false;
		}
		for (String prop : group) {
			if (!entryProperties.contains(prop)) {
				return false;
			}
		}
		return true;
	}
	
	private Set<String> getPropertyGroup(String group, String subgroup) {
		String mProps = config.getString("scam.statistics.group." + group + "." + subgroup, new String());
		return new HashSet<String>(Arrays.asList(mProps.split(",")));
	}
	
	private Map<String, String> getLabels(String label) {
		if (label == null) {
			return null;
		}
		
		List<String> labelList = config.getStringList("scam.statistics.label." + label, new ArrayList<String>());
		Map<String, String> labelMap = new HashMap<String, String>();
		
		for (String labelKeyValue : labelList) {
			String[] keyValue = labelKeyValue.split(",");
			if (keyValue.length == 2) {
				labelMap.put(keyValue[0], keyValue[1]);
			}
		}
		
		return labelMap;
	}
	private JSONObject getCompetenceStatistics(Context context2) {
		HashMap<String, HashMap<String, Integer>> CompDefToCount = new HashMap<String, HashMap<String, Integer>>();
		
		PrincipalManager pm = this.getPM();
		ContextManager cm = this.getCM();
		List<User> users = pm.getUsers();
		int nrOfUsers = users.size();
		//Iterate over all users
		for (User user: users) {
			//The competence-profile is stored in a separate entry
			//and can be found in relations
			List<Statement> stats = user.getEntry().getRelations();
			for (Statement s : stats){
				IRI pred = s.getPredicate();
				if("http://scam.sf.net/schema#aboutPerson".equals(pred.toString())){
					URI resourceURI = URI.create(s.getSubject().toString());
					String contextId = org.entrystore.impl.Util.getContextIdFromURI(this.getRM(), resourceURI);
					Context context = cm.getContext(contextId);
					Set<Entry> competenceEntries = context.getByResourceURI(resourceURI);
					Entry competenceEntry = competenceEntries.iterator().next(); //Should only be one!
					Graph graph = competenceEntry.getMetadataGraph();
					Iterator<Statement> compDefsStatements = graph.match( null,
							graph.getValueFactory().createIRI("http://scam.sf.net/schema#competencyDefinition"), (Resource) null);
					
					while (compDefsStatements.hasNext()){
						Statement stat = compDefsStatements.next();
						try{
							Value compLevel  = GraphUtil.getUniqueObject(graph, stat.getSubject(), 
									graph.getValueFactory().createIRI("http://scam.sf.net/schema#competenceLevel"));
							HashMap<String, Integer> current = CompDefToCount.get(stat.getObject().toString());
							if (current == null){
								current = new HashMap<String, Integer>();
								CompDefToCount.put(stat.getObject().toString(), current);
							}
							Integer currentInt = current.get(compLevel.toString());
							if(currentInt == null){
								currentInt = 0;
							}
							currentInt++;
							current.put(compLevel.toString(), currentInt);
						} catch (GraphUtilException gue) {
							continue;
						}
					}
					break;
				}
			}
		}
		JSONObject returnObject = new JSONObject(CompDefToCount);
		try  {
			returnObject.put("nrOfPersons", nrOfUsers);
		} catch (JSONException jsone){
			return null;
		}
		return returnObject;
	}

}
