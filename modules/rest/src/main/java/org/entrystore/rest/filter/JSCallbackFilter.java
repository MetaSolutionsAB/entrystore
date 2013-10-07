package org.entrystore.rest.filter;

import java.io.IOException;
import java.util.HashMap;

import org.entrystore.rest.util.Util;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
				response.getEntity() != null && MediaType.APPLICATION_JSON.equals(response.getEntity().getMediaType())) {
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
					response.setEntity(wrappedResponse.toString(), MediaType.APPLICATION_JSON);
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
		}
	}
	
}