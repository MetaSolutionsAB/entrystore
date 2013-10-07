package org.entrystore.rest.filter;

import org.entrystore.rest.EntryStoreApplication;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Hannes Ebner
 */
public class ModificationLockOutFilter extends Filter {
	
	static private Logger log = LoggerFactory.getLogger(ModificationLockOutFilter.class);
	
	@Override
	protected int beforeHandle(Request request, Response response) {
		if (request.getMethod().equals(Method.GET)) {
			return CONTINUE;
		} else {
			EntryStoreApplication scamApp = (EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY);
			boolean lockout = scamApp.getRM().hasModificationLockOut();
			if (lockout) {
				String maintMsg = "The service is being maintained and does not accept modification requests right now, please check back later";
				response.setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE, maintMsg);
				log.warn(maintMsg);
				return STOP;
			}
		}
		return CONTINUE;
	}
	
}