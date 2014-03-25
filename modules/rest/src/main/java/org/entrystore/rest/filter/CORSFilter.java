/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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
import org.restlet.data.Form;
import org.restlet.data.Method;
import org.restlet.engine.header.Header;
import org.restlet.engine.header.HeaderUtils;
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
	
	static private Logger log = LoggerFactory.getLogger(CORSFilter.class);

	private CORSUtil cors;

	public CORSFilter(CORSUtil cors) {
		this.cors = cors;
	}

	@Override
	protected void afterHandle(Request request, Response response) {
		if (request != null && response != null) {
			// we only support GET, PUT, POST, DELETE and HEAD
			// OPTIONS for CORS preflight requests is handled in BaseResource
			Method m = request.getMethod();
			if (Method.GET.equals(m) || Method.PUT.equals(m) ||	Method.POST.equals(m) || Method.DELETE.equals(m) ||	Method.HEAD.equals(m)) {
				Series reqHeaders = (Series) request.getAttributes().get("org.restlet.http.headers");
				String origin = reqHeaders.getFirstValue("Origin", true);
				if (origin != null) {
					if (!cors.isValidOrigin(origin)) {
						log.info("Received CORS request with disallowed origin");
						return;
					}
					Series<Header> respHeaders = new Series<>(Header.class);
					respHeaders.add("Access-Control-Allow-Origin", origin);
					respHeaders.add("Access-Control-Allow-Credentials", "true");
					if (cors.getAllowedHeaders() != null) {
						respHeaders.add("Access-Control-Expose-Headers", cors.getAllowedHeaders());
					}
					if (cors.getMaxAge() > -1) {
						respHeaders.add("Access-Control-Max-Age", Integer.toString(cors.getMaxAge()));
					}
					HeaderUtils.copyExtensionHeaders(respHeaders, response);
				}
			}
		}
	}
	
}