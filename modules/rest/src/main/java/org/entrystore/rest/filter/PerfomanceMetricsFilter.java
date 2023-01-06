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

import static org.entrystore.repository.config.Settings.METRICS;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.rest.micrometer.EntryscapeSimpleMeterRegistry;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter for gathering performance metrics
 */
public class PerfomanceMetricsFilter extends Filter {

	final static private List<String> allowedTypes = List.of(
			"resource",
			"entry",
			"metadata",
			"search"
	);
	static private final Logger log = LoggerFactory.getLogger(PerfomanceMetricsFilter.class);
	final private boolean disableCallToSuperDoHandle;
	final private boolean enableMetrics;

	/**
	 * Only use this constructor for JUnit tests, as it will disable all services of the Web Rest API!
	 */
	protected PerfomanceMetricsFilter(boolean testMode) {
		this.disableCallToSuperDoHandle = testMode;
		EntryscapeSimpleMeterRegistry registry = new EntryscapeSimpleMeterRegistry();
		Metrics.addRegistry(registry);

		ConfigurationManager configurationManager;
		try {
			URI uri = ConfigurationManager.getConfigurationURI();
			configurationManager = new ConfigurationManager(uri);
		} catch (IOException e) {
			configurationManager = null;
		}
		this.enableMetrics = configurationManager.getConfiguration().getBoolean(METRICS, false);
	}

	public PerfomanceMetricsFilter() {
		this(false);
	}

	@Override
	protected int doHandle(Request request, Response response) {

		if (!enableMetrics) {
			super.doHandle(request, response);
			return CONTINUE;
		}

		CompositeMeterRegistry registry = Metrics.globalRegistry;
		Timer.Sample sample = Timer.start(registry);

		int returnStatus = this.disableCallToSuperDoHandle ? CONTINUE : super.doHandle(request, response);

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
			Timer.builder(metricName).register(registry);
		}
		sample.stop(registry.timer(metricName));

		return returnStatus;
	}

	private String extractType(String requestPath) {

		if (requestPath.startsWith("/")) {
			requestPath = requestPath.substring(1);
		}

		String[] split = requestPath.split("/");
		String resourceType = (split.length == 1) ? split[0] : split[1];
		if (log.isDebugEnabled()) {
			log.debug("URI split into [{}] parts, choosing [{}] as type - {}",
					split.length, resourceType, Arrays.toString(split));
		}

		return allowedTypes.contains(resourceType) ? resourceType : null;
	}
}
