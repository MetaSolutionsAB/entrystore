/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import org.eclipse.rdf4j.model.Model;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.converters.Graph2Entries;
import org.entrystore.rest.util.GraphUtil;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * This class supports the import of RDF into several entries into a specified
 * contexts. Data will be imported into existing entries if possible, otherwise
 * new entries will be created.
 * 
 * @author Matthias Palmér
 */
public class MergeResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(MergeResource.class);
		
	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}
			
			if (context == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}
			
			String graphString = null;
			try {
				graphString = getRequest().getEntity().getText();
			} catch (IOException e) {
				log.error(e.getMessage());
			}
			
			if (graphString != null) {
				MediaType mediaType = (format != null) ? format : getRequestEntity().getMediaType();
				Model deserializedGraph = GraphUtil.deserializeGraph(graphString, mediaType);
				
				if (deserializedGraph != null) {
					Graph2Entries g2e = new Graph2Entries(this.context);
					g2e.merge(deserializedGraph, this.parameters.get("resourceId"), null);
					getResponse().setStatus(Status.SUCCESS_OK);
					return;
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return;
				}
			}			
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
	}
}