package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.standalone.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.standalone.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.service.SearchService;
import org.entrystore.rest.standalone.springboot.util.Syndication;
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
	private final Config esConfig;

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
			@RequestParam @Size(min = 3, message = "'query' param length must be minimum 3") String query,
			@RequestParam(required = false) String syndication,    // feed type = rss_* or atom_*
			@RequestParam(defaultValue = MediaType.APPLICATION_JSON_VALUE) String rdfFormat,
			@RequestParam(defaultValue = "en") String lang,
			@RequestParam(defaultValue = "50") int limit
	) {

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
			responseBody = searchService.generateSyndication(foundEntries, syndication, lang, limit);
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
			@RequestParam @Size(min = 3, message = "'query' param length must be minimum 3") String query,
			@RequestParam(required = false) String syndication,    // feed type = rss_* or atom_*
			@RequestParam(defaultValue = MediaType.APPLICATION_JSON_VALUE) String rdfFormat,
			@RequestParam(defaultValue = "en") String lang,
			@RequestParam(defaultValue = "0") int offset,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String filterQuery,
			FacetSettingsRequestParams facetRequest
	) {

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

		if (StringUtils.isNotEmpty(syndication) && StringUtils.isNotEmpty(sort)) {
			throw new BadRequestException("Query parameter 'sort' not supported with syndication");
		}

		SolrSearchIndex.FacetSettings facetSettings = facetRequest.toSolrFacetSettings(MAX_FACET_LIMIT, DEFAULT_FACET_LIMIT);

		QueryResultsDto queryResults = searchService.findEntriesSolr(query, sort, offset, limit, filterQueries, facetSettings);

		String responseBody;
		MediaType responseMediaType;
		if (StringUtils.isNotEmpty(syndication)) {
			responseBody = searchService.generateSyndication(queryResults.entries(), syndication, lang, limit);
			responseMediaType = Syndication.convertFeedTypeToMediaType(syndication);
		} else {
			responseBody = searchService.generateJson(offset, limit, queryResults, rdfFormat);
			responseMediaType = MediaType.APPLICATION_JSON;
		}

		return ResponseEntity.ok().contentType(responseMediaType).body(responseBody);
	}

}
