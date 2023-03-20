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
import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.common.SolrException;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
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
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.RDFJSON;
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
			// Query parameter: type
			String type = decodeMandatoryParameter("type").toLowerCase();

			// Query parameter: query
			String queryValue = decodeMandatoryParameter("query");
			if (queryValue.length() < 3) {
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation("{\"error\":\"Query too short\"}");
			}

			MediaType rdfFormat = MediaType.APPLICATION_JSON;
			if (RDFFormat.JSONLD.getDefaultMIMEType().equals(parameters.get("rdfFormat"))) {
				rdfFormat = new MediaType(RDFFormat.JSONLD.getDefaultMIMEType());
			}

			// Query parameter: syndication
			var syndication = decodeOptionalParameter("syndication", null);

			// Query parameter: lang
			var language = decodeOptionalParameter("lang", null);

			// Query parameter: sort
			String sorting = decodeOptionalParameter("sort", null);
			if (syndication != null && sorting != null) {
				String msg = "Query parameter 'sort' not supported with syndication";
				log.info(msg);
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(
						"{\"error\":\"" + msg + "\"}");
			}

			// Query parameter: offset
			int offset = decodeOptionalParameterInteger("offset", 0);
			if (syndication != null && offset > 0) {
				String msg = "Query parameter 'offset' not supported with syndication";
				log.info(msg);
				getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(
						"{\"error\":\"" + msg + "\"}");
			}
			if (offset < 0) {
				offset = 0;
			}

			// Query parameter: limit
			int limit = decodeOptionalParameterInteger("limit", DEFAULT_LIMIT);
			if (limit > MAX_LIMIT) {
				limit = MAX_LIMIT;
			} else if (limit < 0) {
				// we allow 0 on purpose, this enables requests for the purpose of getting a result count only
				limit = DEFAULT_LIMIT;
			}

			// Query parameter: facetFields
			String facetFields = decodeOptionalParameter("facetFields", null);

			// Query parameter: filterQuery
			List<String> filterQueries = new ArrayList<>();
			{
				String filterQueriesStr = decodeOptionalParameter("filterQuery", null);
				if (filterQueriesStr != null) {
					// We URLDecode after the split because we want to be able to use comma
					// as separator (unencoded) for FQs and as content inside FQs (encoded)
					for (String fq : filterQueriesStr.split(",")) {
						filterQueries.add(URLDecoder.decode(fq, UTF_8));
					}
				}
			}

			// Query parameter: facetMinCount
			int facetMinCount = decodeOptionalParameterInteger("facetMinCount", 1);

			// Logic
			QueryResults queryResults = new QueryResults(List.of(), -1, List.of());
			if ("sparql".equalsIgnoreCase(type)) {
				queryResults = searchSparql(queryValue);
			} else if ("solr".equalsIgnoreCase(type)) {
				queryResults = searchSolr(queryValue, sorting, offset, limit, facetFields, filterQueries, facetMinCount);
			}

			if (syndication != null) {
				return writeSyndication(queryResults.entries(), syndication, language, limit);
			} else {
				return writeJson(offset, limit, queryResults, rdfFormat);
			}
		} catch (JsonErrorException e) {
			return e.getRepresentation();
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	public StringRepresentation writeSyndication(List<Entry> entries, String type, String language, int limit) throws JsonErrorException {
		try {

			SyndFeed feed = new SyndFeedImpl();
			feed.setFeedType(type);

			feed.setTitle("Syndication feed of search");
			feed.setDescription(format("Syndication feed containing max %d items", limit));
			feed.setLink(getRequest().getResourceRef().getIdentifier());

			List<SyndEntry> syndEntries = new ArrayList<>();
			int limitedCount = 0;
			for (Entry entry : entries) {

				String title = EntryUtil.getTitle(entry, language);
				String description = EntryUtil.getDescription(entry, language);

				if (title == null && description == null) {
					break;
				}

				SyndContent syndConcentDescription = new SyndContentImpl();
				syndConcentDescription.setType("text/plain");
				syndConcentDescription.setValue(description);

				SyndEntry syndEntry = new SyndEntryImpl();
				syndEntry.setTitle(title);
				syndEntry.setDescription(syndConcentDescription);

				syndEntry.setPublishedDate(entry.getCreationDate());
				syndEntry.setUpdatedDate(entry.getModifiedDate());
				syndEntry.setLink(entry.getResourceURI().toString());

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
			String s;
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

			StringRepresentation rep;
			if (mediaType != null) {
				rep = new StringRepresentation(s, mediaType);
			} else {
				rep = new StringRepresentation(s);
			}

			return rep;
		} catch (IllegalArgumentException e) {
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
			throw new JsonErrorException(new JsonRepresentation("{\"error\":\"" + e.getMessage() + "\"}"));
		}
	}

	private JsonRepresentation writeJson(int offset, int limit, QueryResults queryResults, MediaType rdfFormat) {
		Date before = new Date();
		JSONArray children = new JSONArray();
		if (queryResults.entries() != null) {
			for (Entry e : queryResults.entries()) {
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
						if (groupResource != null) {
							childJSON.put("name", ((Group) groupResource).getName());
						}
					}
					PrincipalManager PM = this.getPM();
					Set<AccessProperty> rights = PM.getRights(e);
					if (rights.size() > 0) {
						for (AccessProperty ap : rights) {
							if (ap == AccessProperty.Administer) {
								childJSON.append("rights", "administer");
							} else if (ap == AccessProperty.WriteMetadata) {
								childJSON.append("rights", "writemetadata");
							} else if (ap == AccessProperty.WriteResource) {
								childJSON.append("rights", "writeresource");
							} else if (ap == AccessProperty.ReadMetadata) {
								childJSON.append("rights", "readmetadata");
							} else if (ap == AccessProperty.ReadResource) {
								childJSON.append("rights", "readresource");
							}
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
									JSONObject childCachedExternalMDJSON = serializeGraph(cachedExternalMDGraph, rdfFormat);
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
									JSONObject localMDJSON = serializeGraph(localMDGraph, rdfFormat);
									childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
								}
							}
						}
					} catch (AuthorizationException ae) {
						childJSON.accumulate("noAccessToMetadata", true);
					}

					try {
						JSONObject childInfo = serializeGraph(e.getGraph(), rdfFormat);
						childJSON.accumulate("info", Objects.requireNonNullElseGet(childInfo, JSONObject::new));
					} catch (AuthorizationException ae) {
						childJSON.accumulate("noAccessToEntryInfo", true);
					}

					try {
						if (e.getRelations() != null) {
							Model childRelationsGraph = new LinkedHashModel(e.getRelations());
							JSONObject childRelationObj = serializeGraph(childRelationsGraph, rdfFormat);
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
		result.put("results", queryResults.results());
		result.put("limit", limit);
		result.put("offset", offset);

		JSONArray facetFieldsArr = new JSONArray();
		for (FacetField ff : queryResults.responseFacetFields()) {
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
	}

	private QueryResults searchSolr(
			String queryValue,
			String sorting,
			int offset,
			int limit,
			String facetFields,
			List<String> filterQueries,
			int facetMinCount) throws JsonErrorException {

		try {

			List<Entry> entries;
			long results ;
			List<FacetField> responseFacetFields;

			if (getRM().getIndex() == null) {
				getResponse().setStatus(SERVER_ERROR_SERVICE_UNAVAILABLE, "Solr search deactivated");
				throw new JsonErrorException("Solr search is deactivated");
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
				throw new JsonErrorException("Search failed due to wrong parameters");
			}
			return new QueryResults(entries, results, responseFacetFields);
		} catch (JSONException e) {
			log.error(e.getMessage());
			getResponse().setStatus(SERVER_ERROR_INTERNAL);
			throw new JsonErrorException("\"error\"");
		}
	}

	private QueryResults searchSparql(String queryValue) throws JsonErrorException {
		List<Entry> entries;
		try {
			String query =
					"PREFIX dc:<http://purl.org/dc/terms/> " +
							"SELECT ?x " +
							"WHERE { " +
							"?x " + queryValue + " ?y }";
			entries = getCM().search(query, null, null);
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new JsonErrorException(URLEncoder.encode(e.getMessage(), UTF_8));
		}
		return new QueryResults(entries, entries.size());
	}

	private String decodeMandatoryParameter(String parameter) throws JsonErrorException {
		return Optional.ofNullable(parameters.get(parameter))
				.map(param -> URLDecoder.decode(param, UTF_8))
				.orElseThrow(() -> {
					String msg = "Mandatory parameter '" + parameter + "' is missing";
					log.info(msg);
					getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
					return new JsonErrorException(msg);
				});
	}

	private String decodeOptionalParameter(String parameter, String defaultValue) {
		return Optional.ofNullable(parameters.get(parameter))
				.map(param -> URLDecoder.decode(param, UTF_8))
				.orElse(defaultValue);
	}

	private Integer decodeOptionalParameterInteger(String parameter, int defaultValue) {
		String value = decodeOptionalParameter(parameter, Integer.valueOf(defaultValue).toString());
		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException e) {
			log.info(e.getMessage());
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
			return defaultValue;
		}
	}

	static class JsonErrorException extends Throwable {

		private final JsonRepresentation representation;

		public JsonErrorException() {
			representation = new JsonRepresentation("{\"error\":\"An error has occurred\"}");
		}

		public JsonErrorException(String error) {
			this.representation = new JsonRepresentation("{\"error\":\"" + error + "\"}");
		}

		public JsonErrorException(JsonRepresentation jsonErrorRepresentation) {
			this.representation = jsonErrorRepresentation;
		}

		public JsonRepresentation getRepresentation() {
			return representation;
		}
	}

	record QueryResults(List<Entry> entries, long results, List<FacetField> responseFacetFields) {
		public QueryResults(List<Entry> entries, long results) {
			this(entries, results, List.of());
		}
	}

	private JSONObject serializeGraph(Model graph, MediaType rdfFormat) {
		if (MediaType.APPLICATION_JSON.equals(rdfFormat)) {
			// We don't use GraphUtil.serializeGraph() because we need a JSONObject here and
			// converting back and forth between String and JSONObject would be very efficient
			return RDFJSON.graphToRdfJsonObject(graph);
		} else if (RDFFormat.JSONLD.getDefaultMIMEType().equals(rdfFormat.getName())) {
			return new JSONObject(GraphUtil.serializeGraph(graph, rdfFormat));
		}
		log.warn("Model could not be serialized, returning empty JSON object");
		return new JSONObject();
	}

}
