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

package org.entrystore.rest.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter for gathering performance metrics
 */
public class PerfomanceMetricsFilter extends Filter {

	static private Logger log = LoggerFactory.getLogger(PerfomanceMetricsFilter.class);

	final static private SimpleMeterRegistry registry = new SimpleMeterRegistry();

	final private boolean disableCallToSuperDoHandle;

	/**
	 * Only use for JUnit tests if you wnt to test the functionality of the filter.
	 *
	 * Will disable all services of the Web Rest API!
	 *
	 * @param disableCallToSuperDoHandle
	 */
	protected PerfomanceMetricsFilter(boolean disableCallToSuperDoHandle) {
		this.disableCallToSuperDoHandle = disableCallToSuperDoHandle;
		Metrics.addRegistry(PerfomanceMetricsFilter.registry);
	}

	public PerfomanceMetricsFilter() {
		this(false);
	}

	@Override
	protected int doHandle(Request request, Response response) {

		Timer.Sample sample = Timer.start(registry);

		int returnStatus = this.disableCallToSuperDoHandle ? CONTINUE :  super.doHandle(request, response);

		String type = extractType(request.getResourceRef().getPath());
		if (type == null) {
			return returnStatus;
		}
		String method = request.getMethod().getName();
		int statusCode = response.getStatus().getCode();
		String mediaSubType;
		if (response.getEntity() != null) {
			mediaSubType = response.getEntity().getMediaType().getSubType();
		} else {
			mediaSubType = "none";
		}
		String metricName = String.format("%s-%s-%s-%d", method, type, mediaSubType, statusCode);

		Search search = registry.find(metricName);
		if (search == null) {
			Timer
					.builder(metricName)
//					.percentilePrecision(0)
					.publishPercentileHistogram()
					.publishPercentiles(0.90d, 0.99d)
					.register(registry);
		}
		sample.stop(registry.timer(metricName));

		return returnStatus;
	}

	final static private List allowedTypes = List.of(
			"resource",
			"entry",
			"metadata",
			"export",
			"import",
			"merge",
			"groups",
			"search"
	);

	private String extractType(String requestPath) {

		if (requestPath.startsWith("/")) {
			requestPath = requestPath.substring(1);
		}

		String[] split = requestPath.split("/");
		String resourceType = (split.length == 1) ? split[0] : split[1];
		if (log.isDebugEnabled()) {
			log.debug("URI split into [{}] parts, choosing [{}] as type - {}", split.length, Arrays.toString(split));
		}

		if (allowedTypes.contains(resourceType)) {
			return resourceType;
		} else {
			return null;
		}
	}

	public static SimpleMeterRegistry getSimpleMeterRegistry() {
		return PerfomanceMetricsFilter.registry;
	}
}
