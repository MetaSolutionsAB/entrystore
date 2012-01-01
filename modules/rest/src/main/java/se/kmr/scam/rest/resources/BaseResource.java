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


import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.harvester.Harvester;
import se.kmr.scam.jdil.JDILErrorMessages;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.RepositoryManager;
import se.kmr.scam.repository.backup.BackupScheduler;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.rest.ScamApplication;
import se.kmr.scam.rest.util.Util;
/**
 *<p> Base resource class that supports common behaviours or attributes shared by
 * all resources.</p>
 * 
 * @author Eric Johansson
 * @author Hannes Ebner 
 */
public abstract class BaseResource extends ServerResource {
	
	protected HashMap<String,String> parameters;
	
	MediaType format;
	
	protected String contextId;
	
	protected String entryId;
	
	protected se.kmr.scam.repository.Context context;
	
	protected se.kmr.scam.repository.Entry entry;
	
	private static Logger log = LoggerFactory.getLogger(BaseResource.class);
	
	@Override
	public void init(Context con, Request req, Response res) {
		parameters = Util.parseRequest(getRequest().getResourceRef().getRemainingPart());
		
		contextId = (String) getRequest().getAttributes().get("context-id");
		if (getCM() != null) {
			context = getCM().getContext(contextId);
		}
		
		entryId = (String) getRequest().getAttributes().get("entry-id");
		if (context != null) {
			entry = context.get(entryId);
		}
		
		if (parameters.containsKey("format")) {
			String format = parameters.get("format");
			if (format != null) {
				this.format = new MediaType(format);
			}
		}
		
		super.init(con, req, res);
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public ContextManager getCM() {
		return ((ScamApplication) getContext().getAttributes().get(ScamApplication.KEY)).getCM();
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public PrincipalManager getPM() {
		Map<String, Object> map = getContext().getAttributes();
		return ((ScamApplication) map.get(ScamApplication.KEY)).getPM();
	}

	/**
	 * Gets the current {@link RepositoryManager}.
	 * @return the current {@link RepositoryManager}.
	 */
	public RepositoryManagerImpl getRM() {
		return ((ScamApplication) getContext().getAttributes().get(ScamApplication.KEY)).getRM();
	}

	public ArrayList<Harvester> getHarvesters() {
		return ((ScamApplication) getContext().getAttributes().get(ScamApplication.KEY)).getHarvesters();
	}
	
	public BackupScheduler getBackupScheduler() {
		return ((ScamApplication) getContext().getAttributes().get(ScamApplication.KEY)).getBackupScheduler();
	}

	public void unauthorizedGETContext() {
		log.info("client tried to GET a resource without being authorized for it's context");
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedGETContext));
	}

	public Representation unauthorizedGET() {
		log.info("client tried to GET a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		return new JsonRepresentation(JDILErrorMessages.unauthorizedGET);
	}

	public void unauthorizedDELETE() {
		log.info("client tried to DELETE a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedDELETE)); 
	}

	public void unauthorizedPOST() {
		log.info("client tried to POST a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedPOST)); 
	}

	public void unauthorizedPUT() {
		log.info("client tried to PUT a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedPUT)); 
	}
	
}