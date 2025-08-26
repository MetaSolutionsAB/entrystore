package org.entrystore.rest.standalone.springboot.model.dto;

import org.apache.solr.client.solrj.response.FacetField;
import org.entrystore.Entry;

import java.util.Collections;
import java.util.List;

public record QueryResultsDto(
		List<Entry> entries,
		long resultsCount,
		List<FacetField> responseFacetFields) {

	public QueryResultsDto(List<Entry> entries) {
		this(entries, entries.size(), Collections.emptyList());
	}
}
