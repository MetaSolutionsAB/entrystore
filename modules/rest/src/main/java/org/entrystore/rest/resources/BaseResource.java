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


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.entrystore.harvester.Harvester;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.Util;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	
	protected org.entrystore.repository.Context context;
	
	protected org.entrystore.repository.Entry entry;
	
	private static Logger log = LoggerFactory.getLogger(BaseResource.class);
	
	@Override
	public void init(Context c, Request request, Response response) {
		parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
		super.init(c, request, response);		
		
		contextId = (String) request.getAttributes().get("context-id");
		if (getCM() != null) {
			context = getCM().getContext(contextId);
		}
		
		entryId = (String) request.getAttributes().get("entry-id");
		if (context != null) {
			entry = context.get(entryId);
		}
		
		if (parameters.containsKey("format")) {
			String format = parameters.get("format");
			if (format != null) {
				this.format = new MediaType(format);
			}
		}
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public ContextManager getCM() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getCM();
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public PrincipalManager getPM() {
		Map<String, Object> map = getContext().getAttributes();
		return ((EntryStoreApplication) map.get(EntryStoreApplication.KEY)).getPM();
	}

	/**
	 * Gets the current {@link RepositoryManager}.
	 * @return the current {@link RepositoryManager}.
	 */
	public RepositoryManagerImpl getRM() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getRM();
	}

	public ArrayList<Harvester> getHarvesters() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getHarvesters();
	}
	
	public BackupScheduler getBackupScheduler() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getBackupScheduler();
	}

	public Representation unauthorizedGET() {
		log.info("Unauthorized GET");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		
		List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (MediaType.APPLICATION_JSON.equals(preferredMediaType)) {
			return new JsonRepresentation(JSONErrorMessages.unauthorizedGET);
		} else {
			return new EmptyRepresentation();
		}
	}

	public void unauthorizedDELETE() {
		log.info("Unauthorized DELETE");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedDELETE));
		}
	}

	public void unauthorizedPOST() {
		log.info("Unauthorized POST");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedPOST));
		}
	}

	public void unauthorizedPUT() {
		log.info("Unauthorized PUT");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedPUT));
		}
	}
	
}