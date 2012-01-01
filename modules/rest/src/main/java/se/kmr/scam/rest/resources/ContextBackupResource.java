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

import org.openrdf.repository.RepositoryException;
import org.restlet.data.MediaType;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;

/**
 * 
 * @author Eric Johansson 
 */
public class ContextBackupResource extends BaseResource {
	
	static Logger log = LoggerFactory.getLogger(ContextBackupResource.class);
	
	String backupId = null; 
	
	@Override
	public void doInit() {
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		MediaType.register("APPLICATION_TRIG", "application/x-trig"); 
		getVariants().add(new Variant(MediaType.valueOf("APPLICATION_TRIG")));
		backupId =(String) getRequest().getAttributes().get("backup-id");
	}
	
	@Get
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

	@Post
	public void acceptRepresentation(Representation representation) throws ResourceException {
		try {
			if(context != null && backupId != null) {

			}

		} catch(AuthorizationException e) {
			unauthorizedPOST();
		} 

	}

	@Delete
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

}
