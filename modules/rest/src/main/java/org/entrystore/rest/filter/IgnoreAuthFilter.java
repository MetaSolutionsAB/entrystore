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

import org.entrystore.rest.util.Util;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;


/**
 * @author Hannes Ebner
 */
public class IgnoreAuthFilter extends Filter {
	
	static private Logger log = LoggerFactory.getLogger(IgnoreAuthFilter.class);
	
	@Override
	protected int beforeHandle(Request request, Response response) {
		HashMap<String,String> parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
		if (parameters.containsKey("ignoreAuth")) {
			log.debug("Removing auth_token cookie from request due to ignoreAuth request parameter");
			request.getCookies().removeAll("auth_token");
		}
		return CONTINUE;
	}
	
}