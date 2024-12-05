package org.entrystore.rest.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
	void parseRequest2() {

		Map<String, String> expected = Map.of(
				"rdfFormat", "application/ld+json",
				"includeAll", "");

		HashMap<String, String> params = Util.parseRequest("rdfFormat=application/ld+json&includeAll");

		assertThat(params)
				.hasSize(2)
				.isEqualTo(expected);
	}
}
