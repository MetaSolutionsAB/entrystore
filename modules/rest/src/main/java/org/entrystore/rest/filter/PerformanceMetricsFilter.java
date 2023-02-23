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
import io.micrometer.core.instrument.search.Search;
import java.util.Arrays;
import java.util.List;
import org.entrystore.config.Config;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.micrometer.EntryStoreSimpleMeterRegistry;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter for gathering performance metrics
 */
public class PerformanceMetricsFilter extends Filter {

	final static private List<String> allowedTypes = List.of(
			"resource",
			"entry",
			"metadata",
			"search"
	);

	static private final Logger log = LoggerFactory.getLogger(PerformanceMetricsFilter.class);

	/**
	 * Only use this constructor for JUnit tests, as it will disable all services of the Web Rest API!
	 */
	public PerformanceMetricsFilter() {
		EntryStoreSimpleMeterRegistry registry = new EntryStoreSimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@Override
	protected int doHandle(Request request, Response response) {

		EntryStoreApplication app = getEntryStoreApplication();
		Config config = app.getRM().getConfiguration();
		boolean configEnableMetrics = config.getBoolean(METRICS, false);

		if (!configEnableMetrics) {
			return super.doHandle(request, response);
//			return CONTINUE;
		}

		Timer.Sample sample = startTimer();
		int returnStatus = super.doHandle(request, response);
		stopTimerAndRegisterMetrics(request, response, sample);
		return returnStatus;
	}

	protected Timer.Sample startTimer() {
		Timer.Sample sample = Timer.start(Metrics.globalRegistry);
		return sample;
	}

	protected void stopTimerAndRegisterMetrics(Request request, Response response, Timer.Sample sample) {
		String type = extractType(request.getResourceRef().getPath());
		if (type == null) {
			return;
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

		Search search = Metrics.globalRegistry.find(metricName);
		if (search == null) {
			Timer.builder(metricName).register(Metrics.globalRegistry);
		}
		sample.stop(Metrics.globalRegistry.timer(metricName));
	}

	private EntryStoreApplication getEntryStoreApplication() {
		EntryStoreApplication app = (EntryStoreApplication)getApplication();
		return app;
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
