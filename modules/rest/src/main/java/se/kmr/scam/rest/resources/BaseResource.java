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
import java.util.Map;

import org.restlet.Context;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Resource;
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
/**
 *<p> Base resource class that supports common behaviours or attributes shared by
 * all resources.</p>
 * 
 * <p>A resource is anything that's important enough to be referenced as a thing in 
 * itself. If your might "want to create a hypertext link to it, make or refute
 * assertions about it, retrieve  or cache a representation of it, include all 
 * or part of it by reference into another representation, annote it, or perform 
 * other operations on it", then you should make it a resource. </p>
 * 
 * <p>Usually, a resource is something that can be stored on a computer and 
 * represented as a stream of bits: a document, row in a database, or results of 
 * running algorithm.</p>
 * 
 * @author Eric Johansson
 * @author Hannes Ebner 
 */
public abstract class BaseResource extends Resource {
	
	/** Logger. */
	private Logger logger = LoggerFactory.getLogger(BaseResource.class);

	/**
	 * Contructor 
	 */
	public BaseResource(Context context, Request request, Response response) {
		super(context, request, response);
		
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
		Map map = getContext().getAttributes();
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
		logger.info("client tried to GET a resource without being authorized for it's context");
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedGETContext));
	}

	public Representation unauthorizedGET() {
		logger.info("client tried to GET a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		return new JsonRepresentation(JDILErrorMessages.unauthorizedGET);
	}

	public void unauthorizedDELETE() {
		logger.info("client tried to DELETE a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedDELETE)); 
	}

	public void unauthorizedPOST() {
		logger.info("client tried to POST a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedPOST)); 
	}

	public void unauthorizedPUT() {
		logger.info("client tried to PUT a resource without being authorized for it");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.unauthorizedPUT)); 
	}
	
}