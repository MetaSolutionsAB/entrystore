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

package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.service.SearchRateLimiter;
import org.entrystore.rest.springboot.service.SearchService;
import org.entrystore.rest.springboot.service.SolrSearchInputValidator;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.Syndication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RestController
@RequestMapping("/search")
@Validated
@RequiredArgsConstructor
public class SearchController {

	private static final int DEFAULT_FACET_LIMIT = 100;

	private static int MAX_LIMIT;
	private static int MAX_FACET_LIMIT;

	private final SearchService searchService;
	private final SolrSearchInputValidator solrSearchInputValidator;
	private final SearchRateLimiter searchRateLimiter;
	private final Config esConfig;

	// Bound via the same Spring @Value channel as AuthService so both rate limiters agree on whether to
	// honour X-Forwarded-For. Reading it here via the legacy Config.getBoolean instead would diverge:
	// Config coerces yes/1 to false while Spring's relaxed binding maps them to true.
	@Value("${entrystore.trust.x-forwarded-for:false}")
	private boolean trustForwardedFor;

	@PostConstruct
	public void init() {
		// Runs after class constructor
		MAX_LIMIT = esConfig.getInt(Settings.SOLR_MAX_LIMIT, 100);
		MAX_FACET_LIMIT = esConfig.getInt(Settings.SOLR_FACET_MAX_LIMIT, 1000);
	}

	@Operation(summary = "Searches the repository and returns entries")
	@GetMapping(
			params = "type=sparql"
	)
	public ResponseEntity<String> findEntriesSparql(
			HttpServletRequest request,
			@RequestParam @Size(min = 3, message = "'query' param length must be minimum 3") String query,
			@RequestParam(required = false) String syndication,    // feed type = rss_* or atom_*
			@RequestParam(name = "urltemplate", required = false) String urlTemplate,
			@RequestParam(name = "feedtitle", defaultValue = "Syndication feed of search") String feedTitle,
			@RequestParam(defaultValue = MediaType.APPLICATION_JSON_VALUE) String rdfFormat,
			@RequestParam(defaultValue = "en") String lang,
			@RequestParam(defaultValue = "50") int limit
	) {

		searchRateLimiter.acquirePermit(HttpUtil.getClientIpAddress(request, trustForwardedFor));

		if (limit > MAX_LIMIT) {
			limit = MAX_LIMIT;
		} else if (limit < 0) {
			// we allow 0 on purpose, this enables requests for the purpose of getting a result count only
			limit = 50;
		}

		List<Entry> foundEntries = searchService.findEntriesSparql(query);

		String responseBody;
		MediaType responseMediaType;
		if (StringUtils.isNotEmpty(syndication)) {
			responseBody = searchService.generateSyndication(request, foundEntries, syndication, lang, limit, urlTemplate, feedTitle);
			responseMediaType = Syndication.convertFeedTypeToMediaType(syndication);
		} else {
			// In Restlet's SPARQL search logic, the "offset" param is accepted from the user but not used
			responseBody = searchService.generateJson(0, limit, new QueryResultsDto(foundEntries), rdfFormat);
			responseMediaType = MediaType.APPLICATION_JSON;
		}

		return ResponseEntity.ok().contentType(responseMediaType).body(responseBody);
	}

	@Operation(summary = "Searches the repository and returns entries")
	@GetMapping(
			params = "type=solr"
	)
	public ResponseEntity<String> findEntriesSolr(
			HttpServletRequest request,
			@RequestParam @Size(min = 3, message = "'query' param length must be minimum 3") String query,
			@RequestParam(required = false) String syndication,    // feed type = rss_* or atom_*
			@RequestParam(name = "urltemplate", required = false) String urlTemplate,
			@RequestParam(name = "feedtitle", defaultValue = "Syndication feed of search") String feedTitle,
			@RequestParam(defaultValue = MediaType.APPLICATION_JSON_VALUE) String rdfFormat,
			@RequestParam(defaultValue = "en") String lang,
			@RequestParam(defaultValue = "0") int offset,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String filterQuery,
			FacetSettingsRequestParams facetRequest
	) {

		searchRateLimiter.acquirePermit(HttpUtil.getClientIpAddress(request, trustForwardedFor));

		solrSearchInputValidator.validateQuery(query);
		solrSearchInputValidator.validateSort(sort);
		solrSearchInputValidator.validateFacetSettings(facetRequest);

		if (StringUtils.isNotEmpty(syndication) && offset > 0) {
			throw new BadRequestException("Query parameter 'offset' not supported with syndication");
		}
		if (offset < 0) {
			offset = 0;
		}

		// Query parameter: limit
		if (limit > MAX_LIMIT) {
			limit = MAX_LIMIT;
		} else if (limit < 0) {
			// we allow 0 on purpose, this enables requests for the purpose of getting a result count only
			limit = 50;
		}

		// Query parameter: filterQuery
		List<String> filterQueries = new ArrayList<>();
		if (filterQuery != null) {
			// We URLDecode after the split because we want to be able to use comma
			// as separator (unencoded) for FQs and as content inside FQs (encoded)
			for (String fq : filterQuery.split(",")) {
				filterQueries.add(URLDecoder.decode(fq, UTF_8));
			}
		}
		solrSearchInputValidator.validateFilterQueries(filterQueries, filterQuery);

		SolrSearchIndex.FacetSettings facetSettings = facetRequest.toSolrFacetSettings(MAX_FACET_LIMIT, DEFAULT_FACET_LIMIT);

		QueryResultsDto queryResults = searchService.findEntriesSolr(query, sort, offset, limit, filterQueries, facetSettings);

		String responseBody;
		MediaType responseMediaType;
		if (StringUtils.isNotEmpty(syndication)) {
			responseBody = searchService.generateSyndication(request, queryResults.entries(), syndication, lang, limit, urlTemplate, feedTitle);
			responseMediaType = Syndication.convertFeedTypeToMediaType(syndication);
		} else {
			responseBody = searchService.generateJson(offset, limit, queryResults, rdfFormat);
			responseMediaType = MediaType.APPLICATION_JSON;
		}

		return ResponseEntity.ok().contentType(responseMediaType).body(responseBody);
	}

}
