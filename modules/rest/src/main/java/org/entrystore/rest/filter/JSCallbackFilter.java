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
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;


/**
 * JavaScript Callback Wrapper to enable JSONP.
 * 
 * @author Hannes Ebner
 */
public class JSCallbackFilter extends Filter {
	
	static private Logger log = LoggerFactory.getLogger(JSCallbackFilter.class);
	
	@Override
	protected void afterHandle(Request request, Response response) {
		if (request != null && response != null && Method.GET.equals(request.getMethod()) &&
				response.getEntity() != null && isJSON(response.getEntity().getMediaType())) {
			HashMap<String, String> parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
			if (parameters.containsKey("callback")) {
				String callback = parameters.get("callback");
				if (callback == null) {
					callback = "callback";
				}
				try {
					StringBuilder wrappedResponse = new StringBuilder();
					wrappedResponse.append(callback).append("(");
					wrappedResponse.append(response.getEntity().getText());
					wrappedResponse.append(")");
					response.setEntity(wrappedResponse.toString(), MediaType.APPLICATION_JAVASCRIPT);
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
		}
	}

	private boolean isJSON(MediaType mediaType) {
		String mime = mediaType.toString();
		if ("application/json".equals(mime) ||
				"application/ld+json".equals(mime) ||
				"application/rdf+json".equals(mime)) {
			return true;
		}
		return false;
	}
	
}