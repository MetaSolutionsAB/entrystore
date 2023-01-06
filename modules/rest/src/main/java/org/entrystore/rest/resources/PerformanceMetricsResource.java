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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.net.URI;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
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
			MeterRegistry registry = Metrics.globalRegistry;
			PrincipalManager pm = getRM().getPrincipalManager();
			URI currentUser = pm.getAuthenticatedUserURI();

			if (!pm.getAdminUser().getURI().equals(currentUser) &&
					!pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return new EmptyRepresentation();
			}

			JSONObject result = new JSONObject();
			for (Meter meter : registry.getMeters()) {
				// We only expose the request timers for now
				if (meter instanceof Timer timer) {
					String timerName = timer.getId().getName();
					HistogramSnapshot histogramSnapshot = timer.takeSnapshot();
					JSONObject timerData = new JSONObject();
					timerData.put("requests", histogramSnapshot.count());
					timerData.put("mean", Math.round(histogramSnapshot.mean(MILLISECONDS)));
					timerData.put("max", Math.round(histogramSnapshot.max(MILLISECONDS)));
					for (ValueAtPercentile valueAtPercentile : histogramSnapshot.percentileValues()) {
						timerData.put("percentile-" + valueAtPercentile.percentile(),
								Math.round(valueAtPercentile.value(MILLISECONDS)));
					}
					result.put(timerName, timerData);
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
