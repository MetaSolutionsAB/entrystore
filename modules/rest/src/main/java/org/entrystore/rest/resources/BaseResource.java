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


import com.google.common.collect.Sets;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.harvester.Harvester;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.util.CORSUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.Util;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.ServerInfo;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Options;
import org.restlet.resource.ServerResource;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *<p> Base resource class that supports common behaviours or attributes shared by
 * all resources.</p>
 * 
 * @author Eric Johansson
 * @author Hannes Ebner 
 */
public abstract class BaseResource extends ServerResource {
	
	protected HashMap<String, String> parameters;
	
	MediaType format;
	
	protected String contextId;
	
	protected String entryId;
	
	protected org.entrystore.Context context;
	
	protected Entry entry;
	
	private static Logger log = LoggerFactory.getLogger(BaseResource.class);

	private static ServerInfo serverInfo;
	
	@Override
	public void init(Context c, Request request, Response response) {
		parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
		super.init(c, request, response);

		// we set a custom Server header in the HTTP response
		setServerInfo(this.getServerInfo());
		
		contextId = (String) request.getAttributes().get("context-id");
		if (getCM() != null && contextId != null) {
			if (getReservedNames().contains(contextId.toLowerCase())) {
				log.error("Context ID is a reserved term and must not be used: \"" + contextId + "\". This error is likely to be caused by an error in the REST routing.");
			} else {
				context = getCM().getContext(contextId);
				if (context == null) {
					log.info("There is no context " + contextId);
				}
			}
		}
		
		entryId = (String) request.getAttributes().get("entry-id");
		if (context != null && entryId != null) {
			entry = context.get(entryId);
			if (entry == null) {
				log.info("There is no entry " + entryId + " in context " + contextId);
			}
		}
		
		if (parameters.containsKey("format")) {
			String format = parameters.get("format");
			if (format != null) {
				// workaround for URL-decoded pluses (space) in MIME-type names, e.g. ld+json
				format = format.replaceAll(" ", "+");
				this.format = new MediaType(format);
			}
		}

		Util.handleIfUnmodifiedSince(entry, getRequest());
	}

	@Override
	public ServerInfo getServerInfo() {
		if (serverInfo == null) {
			ServerInfo si = super.getServerInfo();
			si.setAgent("EntryStore/" + EntryStoreApplication.getVersion());
			serverInfo = si;
		}
		return serverInfo;
	}

	/**
	 * Sends a response with CORS headers according to the configuration.
	 */
	@Options
	public Representation preflightCORS() {
		if ("off".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.CORS, "off"))) {
			log.info("Received CORS preflight request but CORS support is disabled");
			setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
			return null;
		}
		getResponse().setEntity(new EmptyRepresentation());
		Series reqHeaders = (Series) getRequest().getAttributes().get("org.restlet.http.headers");
		String origin = reqHeaders.getFirstValue("Origin", true);
		if (origin != null) {
			CORSUtil cors = CORSUtil.getInstance(getRM().getConfiguration());
			if (!cors.isValidOrigin(origin)) {
				log.info("Received CORS preflight request with disallowed origin");
				//setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
				return new EmptyRepresentation();
			}

			getResponse().setAccessControlAllowOrigin(origin);
			getResponse().setAccessControlAllowMethods(Sets.newHashSet(Method.HEAD, Method.GET, Method.PUT, Method.POST, Method.DELETE, Method.OPTIONS));
			getResponse().setAccessControlAllowCredentials(true);
			if (cors.getAllowedHeaders() != null) {
				getResponse().setAccessControlAllowHeaders(cors.getAllowedHeaders());
				getResponse().setAccessControlExposeHeaders(cors.getAllowedHeaders());
			}
			if (cors.getMaxAge() > -1) {
				getResponse().setAccessControlMaxAge(cors.getMaxAge());
			}
		}
		return getResponse().getEntity();
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

	public Set<String> getReservedNames() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getReservedNames();
	}

	public Representation unauthorizedHEAD() {
		log.info("Unauthorized HEAD");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		return new EmptyRepresentation();
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


	protected Representation createEmptyRepresentationWithLastModified(Date modificationDate) {
		Representation result = new EmptyRepresentation();
		if (modificationDate != null) {
			result.setModificationDate(modificationDate);
		} else {
			log.warn("Last-Modified header could not be set because the entry does not have a modification date: " + entry.getEntryURI());
		}
		return result;
	}

}