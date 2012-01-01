package se.kmr.scam.rest.resources;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.routing.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.rest.ScamApplication;

/**
 * @author Hannes Ebner
 */
public class ModificationLockOutFilter extends Filter {
	
	private Logger log = LoggerFactory.getLogger(ModificationLockOutFilter.class);
	
	@Override
	protected int beforeHandle(Request request, Response response) {
		if (request.getMethod().equals(Method.GET)) {
			return CONTINUE;
		} else {
			ScamApplication scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);
			boolean lockout = scamApp.getRM().hasModificationLockOut();
			if (lockout) {
				response.setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE,
					"The service is being maintained and does not accept modification requests right now, please check back later");
				return STOP;
			}
		}
		return CONTINUE;
	}
	
}