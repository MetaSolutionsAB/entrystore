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

import org.entrystore.AuthorizationException;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.TransformException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


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
		String source = null;
//		String destination;
//		boolean async = false;

		try {
			pipeline = request.getString("pipeline"); // Pipeline Entry URI
			if (request.has("source")) {
				source = request.getString("source"); // Data source Entry URI
			}
//			destination = request.getString("destination"); // Destination Entry URI
//			async = "async".equalsIgnoreCase(request.getString("async")); // sync is default
		} catch (JSONException e) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNPROCESSABLE_ENTITY);
			return;
		}

		// Parameter pipeline is required
		if (pipeline == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		String contextResUri = context.getEntry().getResourceURI().toString();

		// Pipeline have to be located in the same context
		if (!pipeline.startsWith(contextResUri)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		// Source (if provided= have to be located in the same context
		if ( source != null && !source.startsWith(contextResUri)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		try {
			// Pipeline execution requires write-rights on context's resource
			getPM().checkAuthenticatedUserAuthorized(context.getEntry(), AccessProperty.WriteResource);

			// Load pipeline entry from context
			Entry pipelineEntry = context.getByEntryURI(URI.create(pipeline));
			if (pipelineEntry == null) {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return;
			}
			// Load source entry from context
			Entry sourceEntry = null;
			URI listURI = null;
			String sourceMimeType = null;
			Data data = null;
			if (source != null) {
				sourceEntry = context.getByEntryURI(URI.create(source));
				if (sourceEntry == null) {
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					return;
				}
				Set<URI> lists = sourceEntry.getReferringListsInSameContext();
				if (lists.size() == 1) {
					listURI = lists.iterator().next();
				}
				sourceMimeType = sourceEntry.getMimetype();
				data = (Data) sourceEntry.getResource();
				if (!EntryType.Local.equals(sourceEntry.getEntryType()) ||
						!ResourceType.InformationResource.equals(sourceEntry.getResourceType()) ||
						sourceMimeType == null ||
						data == null) {
					getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
					return;
				}
			}

			// TODO add support for non-local resources

			if (!GraphType.Pipeline.equals(pipelineEntry.getGraphType())) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return;
			}

			Set<Entry> processedEntries = null;
			try {
				processedEntries = new Pipeline(pipelineEntry).run(sourceEntry, listURI);
			} catch (IllegalStateException iae) {
				log.error(iae.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			} catch (TransformException te) {
				log.error(te.getMessage());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return;
			}

			if (processedEntries != null && processedEntries.size() > 0) {
				JSONObject result = new JSONObject();
				JSONArray processedEntriesArr = new JSONArray();
				for (Entry e : processedEntries) {
					processedEntriesArr.put(e.getEntryURI().toString());
				}
				try {
					result.put("result", processedEntriesArr);
				} catch (JSONException e) {
					log.error(e.getMessage());
				}

				getResponse().setEntity(new JsonRepresentation(result));
				getResponse().setStatus(Status.SUCCESS_CREATED);
			}

			// TODO support execution status for async executions; perhaps the thread executioner can be
			// shared between listeners and pipelines? this would allow to set a reasonable maximum of
			// concurrent threads per EntryStore instance

		} catch(AuthorizationException e) {
			log.debug("Unauthorized POST");
			unauthorizedPOST();
		}
	}

}