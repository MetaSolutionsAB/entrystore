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

import org.entrystore.rest.util.CORSUtil;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.routing.Filter;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Filter to add response headers for handling simple CORS requests.
 * Advanced CORS requests are supported by the OPTIONS implementation in BaseResource.
 * 
 * @author Hannes Ebner
 */
public class CORSFilter extends Filter {
	
	static private final Logger log = LoggerFactory.getLogger(CORSFilter.class);

	private final CORSUtil cors;

	public CORSFilter(CORSUtil cors) {
		this.cors = cors;
	}

	@Override
	protected void afterHandle(Request request, Response response) {
		if (request != null && response != null) {
			addCorsHeader(request, response);
		}
	}

	public void addCorsHeader(Request request, Response response) {
		// we only support GET, PUT, POST, DELETE and HEAD
		// OPTIONS for CORS preflight requests is handled in BaseResource
		Method m = request.getMethod();
		if (Method.GET.equals(m) || Method.PUT.equals(m) ||	Method.POST.equals(m) || Method.DELETE.equals(m) ||	Method.HEAD.equals(m)) {
			Series reqHeaders = (Series) request.getAttributes().get("org.restlet.http.headers");
			String origin = reqHeaders.getFirstValue("Origin", true);
			if (origin != null) {
				if (!cors.isValidOrigin(origin) && !cors.isValidOriginWithCredentials(origin)) {
					// logging this only on debug-level because some browsers send an
					// Origin-header even on same-origin PUT/POST/DELETE (Chrome e.g.)
					log.debug("Received CORS request with disallowed origin");
					return;
				}

				response.setAccessControlAllowOrigin(origin);
				response.setAccessControlAllowCredentials(cors.isValidOriginWithCredentials(origin));
				if (cors.getMaxAge() > -1) {
					response.setAccessControlMaxAge(cors.getMaxAge());
				}
				if (cors.getAllowedHeaders() != null) {
					response.setAccessControlAllowHeaders(cors.getAllowedHeaders());
					response.setAccessControlExposeHeaders(cors.getAllowedHeaders());
				}
			}
		}
	}

}