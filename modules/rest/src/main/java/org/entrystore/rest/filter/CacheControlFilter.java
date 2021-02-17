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

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Filter to add Cache-Control header to resources that are requested with an authentication cookie.
 * 
 * @author Hannes Ebner
 */
public class CacheControlFilter extends Filter {

	static private Logger log = LoggerFactory.getLogger(CacheControlFilter.class);

	@Override
	protected void afterHandle(Request request, Response response) {
		if (request != null &&
				response != null &&
				request.getCookies().getFirst("auth_token") != null) {
			response.getCacheDirectives().add(CacheDirective.privateInfo());
		}
	}

}