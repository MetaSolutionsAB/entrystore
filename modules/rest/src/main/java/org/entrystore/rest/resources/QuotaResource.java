/**
 * Copyright (c) 2007-2010
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

package org.entrystore.rest.resources;

import org.entrystore.repository.Quota;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This resource is used to manage Context quotas.
 * 
 * @author Hannes Ebner
 */
public class QuotaResource extends BaseResource {
	
	static Logger log = LoggerFactory.getLogger(QuotaResource.class);
	
	@Override
	public void doInit() {
		
	}

	@Get
	public Representation represent() {
		if (context != null) {
			if (context.getEntry().getRepositoryManager().hasQuotas()) {
				JSONObject result = new JSONObject();
				try {
					result.put("quota", context.getQuota());
					result.put("fillLevel", context.getQuotaFillLevel());
					result.put("hasDefaultQuota", context.hasDefaultQuota());
					return new JsonRepresentation(result);
				} catch (JSONException e) {
					log.error(e.getMessage());
				}	
			} else {
				getResponse().setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
				return new JsonRepresentation("{\"error\":\"Quotas are not supported by this installation\"}");		
			}
		}
		
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation("{\"error\":\"Cannot find that context or entry\"}"); 
	}

	@Put
	public void storeRepresentation(Representation r) {
		if (getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI()) || getPM().getAdminGroup().isMember(getPM().getUser(getPM().getAuthenticatedUserURI()))) {
			if (context != null) {
				try {
					JSONObject quotaObj = new JSONObject(this.getRequest().getEntity().getText());
					long quota = Quota.VALUE_UNKNOWN;
					if (quotaObj.has("quota")) {
						quota = quotaObj.getLong("quota");
					}
					if (context != null && quota != Quota.VALUE_UNKNOWN) {
						context.setQuota(quota);
					}
				} catch (Exception e) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				}
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			}
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
		}
	}
	
	@Delete
	public void removeRepresentations() {
		if (getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI()) || getPM().getAdminGroup().isMember(getPM().getUser(getPM().getAuthenticatedUserURI()))) {
			if (context != null) {
				context.removeQuota();
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			}
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
		}
	}
	
}