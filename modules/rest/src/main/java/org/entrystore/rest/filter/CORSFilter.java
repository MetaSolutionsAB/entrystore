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

import org.entrystore.rest.util.Util;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;


/**
 * Filter to add response headers for handling simple CORS requests.
 * Advanced CORS requests are supported by the OPTIONS implementation in BaseResource.
 * 
 * @author Hannes Ebner
 */
public class CORSFilter extends Filter {
	
	static private Logger log = LoggerFactory.getLogger(CORSFilter.class);
	
	@Override
	protected void afterHandle(Request request, Response response) {
		if (request != null && response != null) {
			Form reqHeaders = (Form) request.getAttributes().get("org.restlet.http.headers");
			if (reqHeaders.contains("Origin")) {
				String origin = reqHeaders.getFirstValue("Origin", true);
				// TODO compare to list of allowed origins from configuration
				// if it does not match, do nothing
				// if it matches:
				Form respHeaders = (Form) response.getAttributes().get("org.restlet.http.headers");
				respHeaders.add("Access-Control-Allow-Origin", origin);
				respHeaders.add("Access-Control-Allow-Credentials", "true");
				// TODO check configuration whether any response headers should be added
				// if yes:
				respHeaders.add("Access-Control-Expose-Headers", "list-of-headers-from-configuration");
			}
		}
	}
	
}