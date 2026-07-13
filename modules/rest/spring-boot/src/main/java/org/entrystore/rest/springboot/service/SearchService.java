/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.service;

import com.rometools.rome.feed.synd.SyndFeed;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.common.SolrException;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.HttpQueryRedactor;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.entrystore.rest.springboot.util.Syndication;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

	private final RepositoryManagerImpl repositoryManager;
	private final Config esConfig;
	private final ResourceJsonSerializer resourceJsonSerializer;


	/**
	 * Valid SPARQL predicate: a full IRI ({@code <http://...>}), a prefixed name ({@code dc:title}),
	 * or the keyword {@code a} (shorthand for rdf:type).
	 */
	private static final Pattern VALID_SPARQL_PREDICATE = Pattern.compile(
			"^(<[^<>\"\\s{}|^`\\\\]+>|[a-zA-Z][\\w.-]*:[a-zA-Z_][\\w.-]*[\\w]|a)$"
	);

	public List<Entry> findEntriesSparql(String queryValue) {

		if (queryValue == null || !VALID_SPARQL_PREDICATE.matcher(queryValue).matches()) {
			log.info("Rejected invalid SPARQL predicate input: '{}'", queryValue);
			throw new BadRequestException("Invalid SPARQL predicate. Expected a full IRI (<http://...>) or prefixed name (prefix:name).");
		}

		try {
			String query = "PREFIX dc:<http://purl.org/dc/terms/> " +
					"SELECT ?x " +
					"WHERE { " +
					"  ?x " + queryValue + " ?y }";
			return repositoryManager.getContextManager().search(query, null, null);

		} catch (AuthorizationException e) {
			throw e;
		} catch (Exception e) {
			throw new BadRequestException("Exception processing SPARQL query", e);
		}
	}

	public QueryResultsDto findEntriesSolr(
			String queryValue,
			String sorting,
			int offset,
			int limit,
			List<String> filterQueries,
			SolrSearchIndex.FacetSettings facetSettings) {

		try {

			List<Entry> entries;
			long results;
			List<FacetField> responseFacetFields;

			if (repositoryManager.getIndex() == null) {
				throw new CustomResponseException("Solr search is deactivated", HttpStatus.SERVICE_UNAVAILABLE);
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
						SolrQuery.ORDER order = SolrQuery.ORDER.asc;
						try {
							order = SolrQuery.ORDER.valueOf(fieldAndOrder[1].toLowerCase());
						} catch (IllegalArgumentException iae) {
							log.warn("Unable to parse sorting value, using ascending by default");
						}
						q.addSort(field, order);
					}
				}
			} else {
				q.addSort("score", SolrQuery.ORDER.desc);
				q.addSort("modified", SolrQuery.ORDER.desc);
			}

			if (facetSettings.fields != null) {
				q.setFacet(true);
				q.setFacetMinCount(facetSettings.minCount);
				q.setFacetLimit(facetSettings.limit);
				q.setFacetMissing(facetSettings.missing);
				if (facetSettings.matches != null) {
					q.setParam("facet.matches", facetSettings.matches);
				}
				for (String ff : facetSettings.fields.split(",")) {
					q.addFacetField(ff.replace("metadata.predicate.literal.", "metadata.predicate.literal_s."));
				}
			}

			for (String fq : filterQueries) {
				q.addFilterQuery(fq);
			}

			try {
				QueryResult qResult = ((SolrSearchIndex) repositoryManager.getIndex()).sendQuery(q);
				entries = new LinkedList<>(qResult.getEntries());
				results = qResult.getHits();
				responseFacetFields = qResult.getFacetFields();
			} catch (SolrException se) {
				log.warn("SolrException: {}", se.getMessage());
				throw new BadRequestException("Search failed due to wrong parameters");
			}
			return new QueryResultsDto(entries, results, responseFacetFields);
		} catch (JSONException e) {
			throw new InternalServerErrorException("Error during Solr search", e);
		}

	}

	public String generateSyndication(HttpServletRequest request, List<Entry> entries, String feedType, String language,
									  int limit, String urlTemplate, String feedTitle) {

		SyndFeed feed = Syndication.createFeedFromEntries(repositoryManager.getPrincipalManager(), esConfig, entries,
				language, limit, urlTemplate);
		feed.setTitle(Syndication.sanitizeFeedTitle(feedTitle));
		feed.setLink(buildRequestUri(request));
		feed.setFeedType(feedType);

		try {
			return Syndication.convertSyndFeedToXml(feed);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("Invalid syndication feed type: '" + feedType + "'");
		}
	}

	public String generateJson(int offset, int limit, QueryResultsDto queryResults, String rdfFormat) {
		Instant startTime = Instant.now();
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
						childJSON.put("alias", repositoryManager.getContextManager().getName(e.getResourceURI()));
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
					JSONArray rights = resourceJsonSerializer.serializeRights(e);
					if (!rights.isEmpty()) {
						// unlike list serialization, search children only carry a "rights" key when
						// at least one right exists
						childJSON.put("rights", rights);
					}

					resourceJsonSerializer.appendMetadataInfoAndRelations(e, childJSON, rdfFormat, true);

					children.put(childJSON);
				}
			}
		}

		JSONObject result = new JSONObject();
		JSONObject resource = new JSONObject();
		resource.put("children", children);
		result.put("resource", resource);
		result.put("results", queryResults.resultsCount());
		result.put("limit", limit);
		result.put("offset", offset);
		result.put("facetFields", getFacetFieldsArr(queryResults));

		log.debug("Graph fetching and serialization took {} ms ", Duration.between(startTime, Instant.now()).toMillis());

		return result.toString(2);
	}

	private static @NotNull JSONArray getFacetFieldsArr(QueryResultsDto queryResults) {
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
		return facetFieldsArr;
	}

	/**
	 * Builds the request URI using the configured base URL from {@code entrystore.baseurl.folder}.
	 * This ensures the URI always uses the canonical public base URL regardless of how the request
	 * arrived (e.g., via reverse proxy with or without X-Forwarded-* headers).
	 *
	 * @return Request URI with scheme, host, and port from the configured base URL.
	 */
	// Package-private for direct unit testing of the redactor-integration contract.
	String buildRequestUri(HttpServletRequest request) {
		var repositoryURL = repositoryManager.getRepositoryURL();
		if (repositoryURL == null) {
			throw new InternalServerErrorException(
					"Repository base URL is not configured; check 'entrystore.baseurl.folder'");
		}
		String baseUrl = repositoryURL.toExternalForm();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}

		// Redact known sensitive parameters (confirm, token, ticket, RelayState, …) before the
		// query string lands in a public Atom/RSS self-link. No current search endpoint accepts
		// any of these names; this is defensive against a future addition.
		String query = HttpQueryRedactor.redact(request.getQueryString());
		return baseUrl + request.getServletPath() + (query != null ? "?" + query : "");
	}
}
