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

import java.util.HashMap;

import org.openrdf.repository.RepositoryException;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.restlet.resource.StringRepresentation; 


import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.rest.util.Util;
/**
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 * @see BaseResource
 */
public class ContextBackupResource extends BaseResource {
	/** Logger. */
	Logger log = LoggerFactory.getLogger(ContextBackupResource.class);
	se.kmr.scam.repository.Context context; 
	String backupId = null; 
	/** Parameters from the URL. Example: ?scam=umu&shame=kth */
	HashMap<String,String> parameters = null; 
	public ContextBackupResource(Context context, Request request, Response response) {
		super(context, request, response);

		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		MediaType.register("APPLICATION_TRIG", "application/x-trig"); 
		getVariants().add(new Variant(MediaType.valueOf("APPLICATION_TRIG")));

		String contextId =(String)getRequest().getAttributes().get("context-id"); 
		backupId =(String)getRequest().getAttributes().get("backup-id"); 
		String remainingPart = request.getResourceRef().getRemainingPart(); 

		parameters = Util.parseRequest(remainingPart); 

		if(getCM() != null) {
			try {
				this.context = getCM().getContext(contextId);  
			} catch (NullPointerException e) {
				// not a context
				this.context = null; 
			}
		}

	}
	//GET
	@Override
	public Representation represent(Variant variant) throws ResourceException {
		try {
			if(context != null && backupId != null) {

				if(parameters.containsKey("method") && parameters.get("method").equals("restore")) {
					try {
						getCM().restoreBackup(context.getURI(), backupId);
					} catch (RepositoryException e) {
						log.error(e.getMessage()); 
						return new JsonRepresentation("{\"error\":\""+e.getMessage()+"\"}");
					}
					return new JsonRepresentation("{\"value\":\"The context is restored.\"}"); 
				} else {
					String backup = getCM().getBackup(context.getURI(), backupId);
					if(backup != null) {

						StringRepresentation rep = new StringRepresentation(backup); 
						MediaType.register("APPLICATION_TRIG", "application/x-trig"); 
						rep.setMediaType(MediaType.valueOf("APPLICATION_TRIG")); 

						return rep; 
					}
				}
			}
			return new JsonRepresentation("{\"error\":\"No backup with that id is found\"}");
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	@Override
	public boolean allowPost() {
		return true;
	}

	//POST
	@Override
	public void acceptRepresentation(Representation representation) throws ResourceException {
		try {
			if(context != null && backupId != null) {

			}

		} catch(AuthorizationException e) {
			unauthorizedPOST();
		} 

	}

	@Override
	public boolean allowDelete() {
		return true;
	}

	//	DELETE
	@Override
	public void removeRepresentations() throws ResourceException {
		try {
			if(context != null && backupId != null) {
				if(getCM().deleteBackup(context.getURI(), backupId)) {
					// TODO: success
					return; 
				} 
			}
			log.error("Can not remove the backup"); 
			// TODO Error
		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}


	//PUT
	@Override
	public void storeRepresentation(Representation entity) throws ResourceException {
		try {

		}
		catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}
}
