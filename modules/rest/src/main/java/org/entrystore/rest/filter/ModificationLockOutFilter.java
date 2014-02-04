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