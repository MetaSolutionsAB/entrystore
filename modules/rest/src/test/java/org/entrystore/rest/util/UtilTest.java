package org.entrystore.rest.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UtilTest {

	@Test
	void parseRequest() {

		HashMap<String, String> params = Util.parseRequest("includeAll=&ignore&limit=50&sort=title&prio=List");

		Map<String, String> expected = Map.of(
				"includeAll", "",
				"ignore", "",
				"limit", "50",
				"sort", "title",
				"prio", "List"
		);

		assertThat(params)
				.hasSize(5)
				.isEqualTo(expected);
	}

	@Test
	void parseRequestWithFormat() {

		Map<String, String> expected = Map.of(
				"format", "application/rdf+xml",
				"includeAll", "");

		HashMap<String, String> params = Util.parseRequest("format=application/rdf+xml&includeAll");

		assertThat(params)
				.hasSize(2)
				.isEqualTo(expected);
	}

	@Test
	void parseRequestWithRdfFormat() {

		Map<String, String> expected = Map.of(
				"rdfFormat", "application/ld+json",
				"includeAll", "");

		HashMap<String, String> params = Util.parseRequest("rdfFormat=application/ld+json&includeAll");

		assertThat(params)
				.hasSize(2)
				.isEqualTo(expected);
	}

	@Test
	void parseRequestWithSolrQuery() {

		Map<String, String> expected = Map.of(
				"type", "solr",
				"query", "rdfType:http\\://www.w3.org/2004/02/skos/core#ConceptScheme AND context:https\\://dev.entryscape.com/store/9");

		HashMap<String, String> params = Util.parseRequest("type=solr&query=rdfType:http%5C%3A%2F%2Fwww.w3.org%2F2004%2F02%2Fskos%2Fcore%23ConceptScheme+AND+context:https%5C%3A%2F%2Fdev.entryscape.com%2Fstore%2F9");

		assertThat(params)
				.hasSize(2)
				.isEqualTo(expected);
	}

	@Test
	void parseRequestWithUrl() {
		Map<String, String> expected = Map.of("url", "https://some.host.tld/store/3/metadata/2?recursive=dcat&format=application/rdf+xml");

		HashMap<String, String> params = Util.parseRequest("url=https%3A%2F%2Fsome.host.tld%2Fstore%2F3%2Fmetadata%2F2%3Frecursive%3Ddcat%26format%3Dapplication%2Frdf%2Bxml");

		assertThat(params)
			.hasSize(1)
			.isEqualTo(expected);
	}

}
