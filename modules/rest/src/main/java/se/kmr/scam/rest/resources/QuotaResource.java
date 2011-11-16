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

package se.kmr.scam.rest.resources;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.Quota;

/**
 * This resource is used to manage Context quotas.
 * 
 * @author Hannes Ebner
 */
public class QuotaResource extends BaseResource {
	
	/** Logger. */
	Logger log = LoggerFactory.getLogger(QuotaResource.class);
	
	/** The contexts ID. */
	String contextId = null;
	
	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;
	
	Entry entry = null;
	
	public QuotaResource(Context context, Request request, Response response) {
		super(context, request, response);

		this.contextId = (String) getRequest().getAttributes().get("context-id");

		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		getVariants().add(new Variant(MediaType.ALL));
		
		if (getCM() != null) {
			try {	
				this.context = getCM().getContext(contextId);  
			} catch (NullPointerException e) {
				// not a context
				this.context = null; 
			}
		}
	}

	/**
	 * GET
	 */
	public Representation represent(Variant variant) {
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

	/**
	 * PUT
	 */
	public void storeRepresentation(Representation representation) {
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
	
	/**
	 * DELETE
	 */
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

	@Override
	public boolean allowPut() {
		return true;
	}
	
	@Override
	public boolean allowDelete() {
		return true;
	}
	
}