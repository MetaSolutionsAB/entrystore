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

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.entrystore.rest.util.RDFJSON.graphToRdfJson;
import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
import static org.restlet.data.Status.CLIENT_ERROR_METHOD_NOT_ALLOWED;
import static org.restlet.data.Status.SERVER_ERROR_INTERNAL;
import static org.restlet.data.Status.SERVER_ERROR_SERVICE_UNAVAILABLE;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.common.SolrException;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.util.JSONErrorMessages;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
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

	static int DEFAULT_LIMIT = 50;

	static int MAX_LIMIT = -1;

	@Override
	public void doInit() {
		if (MAX_LIMIT == -1) {
			MAX_LIMIT = getRM().getConfiguration().getInt(Settings.SOLR_MAX_LIMIT, 100);
		}
	}

	@Get
	public Representation represent() throws ResourceException {
		try {
			if (!parameters.containsKey("type") ||
					!parameters.containsKey("query") ||
					parameters.get("type") == null ||
					parameters.get("query") == null) {
				log.info("Got invalid query");
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Invalid query\"}");
			}

			// Query parameter: type
			String type;
			if (parameters.get("type") == null) {
				log.info("Mandatory query parameter 'type' is missing");
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Mandatory query parameter 'type' is missing\"}");
			}
			type = parameters.get("type").toLowerCase();

			// Query parameter: query
			String queryValue;
			if (parameters.get("query") == null) {
				log.info("Mandatory query parameter 'query' is missing");
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Mandatory query parameter 'query' is missing\"}");
			}
			queryValue = URLDecoder.decode(parameters.get("query"), UTF_8);
			if (queryValue.length() < 3) {
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Query too short\"}");
			}

			// Query parameter: sort
			String sorting = null;
			if (parameters.containsKey("sort")) {
				if (parameters.containsKey("syndication")) {
					log.info("Sort query parameter not suported with syndication");
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return new JsonRepresentation(
						"{\"error\":\"Sort query parameter not suported with syndication\"}");
				}
				sorting = URLDecoder.decode(parameters.get("sort"), UTF_8);
			}

			// Query parameter: offset
			int offset = 0;
			if (parameters.containsKey("offset")) {
				if (parameters.containsKey("syndication")) {
					log.info("Query parameter 'offset' not suported with syndication");
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return new JsonRepresentation(
						"{\"error\":\"Query parameter 'offset' not suported with syndication\"}");
				}
				try {
					offset = Integer.valueOf(parameters.get("offset"));
				} catch (NumberFormatException ignored) {}
				if (offset < 0) {
					offset = 0;
				}
			}

			// Query parameter: limit
			int limit = DEFAULT_LIMIT;
			if (parameters.containsKey("limit")) {
				try {
					limit = Integer.valueOf(parameters.get("limit"));
					if (limit > MAX_LIMIT) {
						limit = MAX_LIMIT;
					} else if (limit < 0) { // we allow 0 on purpose, this enables requests for the purpose of getting a result count only
						limit = DEFAULT_LIMIT;
					}
				} catch (NumberFormatException ignored) {}
			}

			// Query parameter: facetFields
			String facetFields = null;
			if (parameters.containsKey("facetFields")) {
				facetFields = URLDecoder.decode(parameters.get("facetFields"), UTF_8);
			}

			// Query parameter: filterQuery
			List<String> filterQueries = new ArrayList();
			if (parameters.containsKey("filterQuery")) {
				// We URLDecode after the split because we want to be able to use comma
				// as separator (unencoded) for FQs and as content inside FQs (encoded)
				for (String fq : parameters.get("filterQuery").split(",")) {
					filterQueries.add(URLDecoder.decode(fq, UTF_8));
				}
			}

			int facetMinCount = 1;
			if (parameters.containsKey("facetMinCount")) {
				try {
					facetMinCount = Integer.valueOf(parameters.get("facetMinCount"));
				} catch (NumberFormatException e) {
					log.info(e.getMessage());
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return null;
				}
			}

			List<Entry> entries = new ArrayList<>();
			long results = 0;
			List<FacetField> responseFacetFields = null;

			if ("sparql".equalsIgnoreCase(type)) {
				try {
					String query =
							"PREFIX dc:<http://purl.org/dc/terms/> " +
							"SELECT ?x " +
							"WHERE { " +
							"?x " + queryValue + " ?y }";
					entries = getCM().search(query, null, null);
				} catch (Exception e) {
					log.error(e.getMessage());
					return new JsonRepresentation(
						"{\"error\":\"" + URLEncoder.encode(e.getMessage(), UTF_8) + "\"}");
				}
			} else if ("solr".equalsIgnoreCase(type)) {
				if (getRM().getIndex() == null) {
					getResponse().setStatus(SERVER_ERROR_SERVICE_UNAVAILABLE, "Solr search deactivated");
					return new JsonRepresentation("{\"error\":\"Solr search is deactivated\"}");
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
					entries = new LinkedList<>(qResult.getEntries());
					results = qResult.getHits();
					responseFacetFields = qResult.getFacetFields();
				} catch (SolrException se) {
					log.warn(se.getMessage());
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return new StringRepresentation("{\"error\":\"Search failed due to wrong parameters\"}", MediaType.APPLICATION_JSON);
				}
			}

			// RSS feed
			if (parameters.containsKey("syndication")) {
				try {
					StringRepresentation rep = getSyndicationSolr(entries, parameters.get("syndication"), limit);
					if (rep == null) {
						getResponse().setStatus(CLIENT_ERROR_METHOD_NOT_ALLOWED);
						return new JsonRepresentation(JSONErrorMessages.errorNotAContext);
					}
					return rep;
				} catch (IllegalArgumentException e) {
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return new JsonRepresentation(JSONErrorMessages.syndicationFormat);
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
								try {
									if (u.isDisabled()) {
										childJSON.put("disabled", true);
									}
								} catch (AuthorizationException ae) {
									log.debug("Not allowed to read disabled status of " + e.getEntryURI());
								}
							} else if (btChild == GraphType.Group && locChild == EntryType.Local) {
								Resource groupResource = e.getResource();
								if (groupResource != null ) {
									childJSON.put("name", ((Group) groupResource).getName());
								}
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
										Model cachedExternalMDGraph = cachedExternalMD.getGraph();
										if (cachedExternalMDGraph != null) {
											//TODO Might fail with a NullpointerException
											JSONObject childCachedExternalMDJSON = new JSONObject(graphToRdfJson(cachedExternalMDGraph));
											childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, childCachedExternalMDJSON);
										}
									}
								}

								if (EntryType.Link.equals(ltC) || EntryType.Local.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
									// get the local metadata
									Metadata localMD = e.getLocalMetadata();
									if (localMD != null) {
										Model localMDGraph = localMD.getGraph();
										if (localMDGraph != null) {
											//TODO Might fail with a NullpointerException
											JSONObject localMDJSON = new JSONObject(graphToRdfJson(localMDGraph));
											childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
										}
									}
								}
							} catch (AuthorizationException ae) {
								childJSON.accumulate("noAccessToMetadata", true);
							}

							try {
								//TODO Might fail with a NullpointerException
								JSONObject childInfo = new JSONObject(graphToRdfJson(e.getGraph()));
								if (childInfo != null) {
									childJSON.accumulate("info", childInfo);
								} else {
									childJSON.accumulate("info", new JSONObject());
								}
							} catch (AuthorizationException ae) {
								childJSON.accumulate("noAccessToEntryInfo", true);
							}

							try {
								if (e.getRelations() != null) {
									Model childRelationsGraph = new LinkedHashModel(e.getRelations());
									//TODO Might fail with a NullpointerException
									JSONObject childRelationObj = new JSONObject(graphToRdfJson(childRelationsGraph));
									childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
								}
							} catch (AuthorizationException ae) {
								childJSON.accumulate("noAccessToRelations", true);
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
				getResponse().setStatus(SERVER_ERROR_INTERNAL);
				return new JsonRepresentation("\"error\"");
			}
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	public StringRepresentation getSyndicationSolr(List<Entry> entries, String type, int limit) {
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(type);

		feed.setTitle("Syndigation feed of search");
		feed.setDescription(format("Syndication feed containing max %d items", limit));
		feed.setLink(getRequest().getResourceRef().getIdentifier());

		List<SyndEntry> syndEntries = new ArrayList<>();
		int limitedCount = 0;
		for (Entry entry : entries) {
			SyndEntry syndEntry;
			syndEntry = new SyndEntryImpl();
			syndEntry.setTitle(EntryUtil.getTitle(entry, "en"));
			syndEntry.setPublishedDate(entry.getCreationDate());
			syndEntry.setUpdatedDate(entry.getModifiedDate());
			syndEntry.setLink(entry.getResourceURI().toString());

			SyndContent description = new SyndContentImpl();
			description.setType("text/plain");

			Map<String, String> descriptions = EntryUtil.getDescriptions(entry);
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

			URI creator = entry.getCreator();
			if (creator != null) {
				Entry creatorEntry = getRM().getPrincipalManager().getByEntryURI(creator);
				String creatorName = EntryUtil.getName(creatorEntry);
				if (creatorName != null) {
					syndEntry.setAuthor(creatorName);
				}
			}

			syndEntries.add(syndEntry);

			if (limitedCount++ >= limit) {
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
}
