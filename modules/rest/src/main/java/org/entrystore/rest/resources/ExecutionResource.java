/*
 * Copyright (c) 2007-2014
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

import org.entrystore.repository.Data;
import org.entrystore.repository.Entry;
import org.entrystore.repository.EntryType;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.ResourceType;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.transforms.Pipeline;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;


/**
 * This resource executes pipelines etc.
 * 
 * @author Hannes Ebner
 */
public class ExecutionResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ExecutionResource.class);

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
	}

	// TODO add support for GET to query currently running pipelines; this should
	// be supported together with asynchronous processing of pipeline executions
		
	@Post
	public void acceptRepresentation(Representation r) {
		if (context == null) {
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return;
		}

		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (!MediaType.APPLICATION_JSON.equals(preferredMediaType)) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		JSONObject request = null;
		try {
			request = new JSONObject(getRequest().getEntity().getText());
		} catch (Exception e) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNPROCESSABLE_ENTITY);
			log.error(e.getMessage());
			return;
		}

		String pipeline;
		String source;
		String destination;
		boolean async = false;

		try {
			pipeline = request.getString("pipeline"); // Pipeline Entry URI
			source = request.getString("source"); // Data source Entry URI
			destination = request.getString("destination"); // Destination Entry URI
			async = "async".equalsIgnoreCase(request.getString("async")); // sync is default
		} catch (JSONException e) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNPROCESSABLE_ENTITY);
			return;
		}

		// Parameters pipeline and source are required
		if (pipeline == null || source == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		String contextResUri = context.getEntry().getResourceURI().toString();

		// Pipeline and source have to be located in the same context
		if (!pipeline.startsWith(contextResUri) || !source.startsWith(contextResUri)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		try {
			// Pipeline execution requires admin-rights on context
			getPM().checkAuthenticatedUserAuthorized(context.getEntry(), AccessProperty.Administer);

			// Load pipeline and source entries from context
			Entry pipelineEntry = context.getByEntryURI(URI.create(pipeline));
			Entry sourceEntry = context.getByEntryURI(URI.create(source));
			if (pipelineEntry == null || sourceEntry == null) {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return;
			}

			// TODO add support for non-local resources

			String sourceMimeType = sourceEntry.getMimetype();
			Data data = (Data) sourceEntry.getResource();

			if (!ResourceType.Pipeline.equals(pipelineEntry.getResourceType()) ||
					!EntryType.Local.equals(sourceEntry.getEntryType()) ||
					!RepresentationType.InformationResource.equals(sourceEntry.getRepresentationType()) ||
					sourceMimeType == null ||
					data == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return;
			}

			new Pipeline(pipelineEntry).run(data.getData(), sourceMimeType);

			// TODO support execution status for async executions; perhaps the thread executioner can be
			// shared between listeners and pipelines? this would allow to set a reasonable maximum of
			// concurrent threads per EntryStore instance

			// TODO return list of created entries in response object

		} catch(AuthorizationException e) {
			log.error("unauthorizedPOST");
			unauthorizedPOST();
		}
	}

}