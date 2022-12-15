package org.entrystore.rest.filter;

import static org.restlet.data.MediaType.APPLICATION_JSON;
import static org.restlet.data.Status.SUCCESS_OK;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;

class PerfomanceMetricsFilterTest {

	@Test
	void doHandle() {
		PerfomanceMetricsFilter filter = new PerfomanceMetricsFilter(true);
		Request request = new Request(Method.GET, "http://url:8181/search");
		Response response = new Response(request);
		response.setEntity("", APPLICATION_JSON);
		response.setStatus(SUCCESS_OK);
		System.out.println(response);
		filter.doHandle(request, response);

		SimpleMeterRegistry registry = PerfomanceMetricsFilter.getSimpleMeterRegistry();
		List<Meter> meters = registry.getMeters();
		System.out.println(meters);
	}
}
