package org.entrystore.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.restlet.data.MediaType.APPLICATION_JSON;
import static org.restlet.data.MediaType.TEXT_PLAIN;
import static org.restlet.data.Method.GET;
import static org.restlet.data.Method.PUT;
import static org.restlet.data.Status.CLIENT_ERROR_FORBIDDEN;
import static org.restlet.data.Status.SUCCESS_OK;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;

class PerfomanceMetricsFilterTest {

	@Test
	void testDifferentResourceTypes() {
		PerformanceMetricsFilter filter = new PerformanceMetricsFilter(true);
		callFilter(filter, GET, "http://uri:0/search?type=solr&query=title:Bamse", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/search/?type=solr&query=title:Bamse", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/search", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/_contexts/resource/1", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/_contexts/does-not-exist/1", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/does-not-exist", APPLICATION_JSON, SUCCESS_OK);

		CompositeMeterRegistry registry = Metrics.globalRegistry;
		assertThat(registry.getMeters()).hasSize(2)
				.extracting(meter -> meter.getId().getName())
				.containsExactlyInAnyOrder("GET-search-json-200", "GET-resource-json-200");
	}

	@Test
	void testDifferentMethodsAndResourceTypesAndMediaTypesAndStatuses() {
		PerformanceMetricsFilter filter = new PerformanceMetricsFilter(true);
		callFilter(filter, GET, "http://uri:0/search?type=solr&query=title:Bamse", APPLICATION_JSON, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/search/?type=solr&query=title:Bamse", TEXT_PLAIN, SUCCESS_OK);
		callFilter(filter, GET, "http://uri:0/search", APPLICATION_JSON, CLIENT_ERROR_FORBIDDEN);
		callFilter(filter, PUT, "http://uri:0/_contexts/resource/1", APPLICATION_JSON, SUCCESS_OK);

		CompositeMeterRegistry registry = Metrics.globalRegistry;
		assertThat(registry.getMeters()).hasSize(4)
				.extracting(meter -> meter.getId().getName())
				.containsExactlyInAnyOrder(
						"GET-search-json-200",
						"GET-search-plain-200",
						"GET-search-json-403",
						"PUT-resource-json-200");
	}

	private void callFilter(PerformanceMetricsFilter filter, Method method, String uri, MediaType mediaType, Status status) {
		Request request = new Request(method, uri);
		Response response = new Response(request);
		response.setEntity(null, mediaType);
		response.setStatus(status);
		filter.doHandle(request, response);
	}
}
