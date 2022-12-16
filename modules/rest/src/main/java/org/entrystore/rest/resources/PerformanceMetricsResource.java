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

package org.entrystore.rest.resources;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.cumulative.CumulativeTimer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.filter.PerfomanceMetricsFilter;
import org.json.JSONObject;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Provides methods to retrieve performance metrics.
 *
 * @author Hannes Ebner
 */
public class PerformanceMetricsResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(PerformanceMetricsResource.class);

	/**
	 * <pre>
	 * GET {baseURI}/metrics
	 * </pre>
	 *
	 * @return performance metrics
	 */
	@Get
	public Representation represent() {
		try {
			SimpleMeterRegistry registry = PerfomanceMetricsFilter.getSimpleMeterRegistry();
			PrincipalManager pm = getRM().getPrincipalManager();
			URI currentUser = pm.getAuthenticatedUserURI();

			//TODO: @hannes I only allow admins to get metrics. Should we allow regular users access?
			if (!pm.getAdminUser().getURI().equals(currentUser) &&
					!pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return new EmptyRepresentation();
			}

			JSONObject result = new JSONObject();
			for (Meter meter : registry.getMeters()) {
				// We only expose the request timers for now
				if (meter instanceof CumulativeTimer timer) {
					HistogramSnapshot histogramSnapshot = timer.takeSnapshot();
					JSONObject key = new JSONObject();
					key.put("requests", histogramSnapshot.count());
					key.put("mean", histogramSnapshot.mean(TimeUnit.MILLISECONDS));
					key.put("max", histogramSnapshot.max(TimeUnit.MILLISECONDS));
					//TODO: @hannes Percentiles does not work, and I do not know why. Should we release without fixing them?
					for (ValueAtPercentile valueAtPercentile : histogramSnapshot.percentileValues()) {
						key.put("percentile" + valueAtPercentile.percentile(), valueAtPercentile.value(TimeUnit.MILLISECONDS));
					}
					result.put(timer.getId().getName(), key);
				}
			}
			return new JsonRepresentation(result.toString(2));
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		} catch (Throwable e) {  //Includes JSONException
			log.error(e.getMessage());
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return new EmptyRepresentation();
		}
	}
}
