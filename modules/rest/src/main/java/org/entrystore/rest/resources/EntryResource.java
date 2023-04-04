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

import static org.entrystore.EntryType.Link;
import static org.entrystore.EntryType.LinkReference;
import static org.entrystore.EntryType.Local;
import static org.entrystore.EntryType.Reference;
import static org.entrystore.rest.serializer.ResourceJsonSerializer.IMMUTABLE_EMPTY_JSONOBJECT;
import static org.restlet.data.MediaType.APPLICATION_JSON;
import static org.restlet.data.MediaType.APPLICATION_RDF_XML;
import static org.restlet.data.MediaType.TEXT_RDF_N3;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Metadata;
import org.entrystore.Resource;
import org.entrystore.exception.EntryMissingException;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.rest.auth.UserTempLockoutCache;
import org.entrystore.rest.serializer.ResourceJsonSerializer;
import org.entrystore.rest.serializer.ResourceJsonSerializer.ListParams;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Handles entries.
 *
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class EntryResource extends BaseResource {

	private final Logger log = LoggerFactory.getLogger(EntryResource.class);
	private final List<MediaType> supportedMediaTypes = List.of(
			APPLICATION_RDF_XML,
			APPLICATION_JSON,
			TEXT_RDF_N3,
			new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()),
			new MediaType(RDFFormat.TRIX.getDefaultMIMEType()),
			new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()),
			new MediaType(RDFFormat.TRIG.getDefaultMIMEType()),
			new MediaType(RDFFormat.JSONLD.getDefaultMIMEType())
	);

	private UserTempLockoutCache userTempLockoutCache;
	private ResourceJsonSerializer resourceSerializer;

	@Override
	public void doInit() {
		this.userTempLockoutCache = getUserTempLockoutCache();
		this.resourceSerializer = new ResourceJsonSerializer(getPM(), getCM(), userTempLockoutCache);
	}

	@Override
	public Representation head() {
		return head(null);
	}

	@Override
	public Representation head(Variant v) {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new EmptyRepresentation();
			}
			return createEmptyRepresentationWithLastModified(entry.getModifiedDate());
		} catch (AuthorizationException e) {
			return unauthorizedHEAD();
		}
	}

	/**
	 * GET
	 *
	 * From the REST API:
	 *
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/entry/{entry-id}
	 * </pre>
	 *
	 * @return The Representation as JSON
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			// the check for resource safety is necessary to avoid an implicit
			// getMetadata() in the case of a PUT on (not yet) existant metadata
			// - this is e.g. the case if conditional requests are issued
			Optional<MediaType> preferredMediaType =
					Optional.of(getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes));

			Representation result = switch (getRequest().getMethod().getName()) {
				case "GET" -> getEntry((format != null) ? format : preferredMediaType.orElse(APPLICATION_RDF_XML));
				default -> new EmptyRepresentation();
			};

			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				result.setModificationDate(lastMod);
			}

			return result;
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		try {
			if (getRequest().getEntity().isEmpty()) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
			modifyEntry((format != null) ? format : getRequestEntity().getMediaType());
			getResponse().setEntity(createEmptyRepresentationWithLastModified(entry.getModifiedDate()));
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry != null && context != null) {
				if (GraphType.List.equals(entry.getGraphType()) && parameters.containsKey("recursive")) {
					org.entrystore.List l = (org.entrystore.List) entry.getResource();
					if (l != null) {
						l.removeTree();
					} else {
						log.warn("Resource of the following list is null: " + entry.getEntryURI());
					}
				} else {
					context.remove(entry.getEntryURI());
				}
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			}
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		} catch (EntryMissingException e) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		}
	}

	private Representation getEntry(MediaType mediaType) {
		String serializedGraph = null;
		/* if (MediaType.TEXT_HTML.equals(mediaType)) {
			return getEntryInHTML();
		} else */
		if (APPLICATION_JSON.equals(mediaType)) {
			return getEntryInJSON();
		} else {
			Model graph = entry.getGraph();
			serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
			if (serializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				return new StringRepresentation(serializedGraph, mediaType);
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new EmptyRepresentation();
	}

	/* Temporarily disabled code, see ENTRYSTORE-435 for details
	private Representation getEntryInHTML() {
		try {
			if (parameters != null) {
				parameters.put("includeAll", "true");
			}
			JSONObject jobj = this.getEntryAsJSONObject();
			String storejs = config.getString(Settings.STOREJS_JS, "/storejs/storejs.js");
			String storecss = config.getString(Settings.STOREJS_CSS, "/storejs/storejs.css");
			if (jobj != null) {
				return new StringRepresentation(
						"<html><head><meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\">"+
						"<script type=\"text/javascript\">dojoConfig = {deps: [\"store/boot\"]};</script>"+
						"<script type=\"text/javascript\" src=\""+storejs+"\"></script>"+
						"<link type=\"text/css\" href=\""+storecss+"\" rel=\"stylesheet\"></link></head><body><textarea>" +
						jobj.toString(2) +
						"</textarea></body></html>", MediaType.TEXT_HTML);
			}
		} catch (JSONException e) {
			log.error(e.getMessage());
		}
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
	}
	*/

	/**
	 * Gets the entry JSON
	 *
	 * @return JSON representation
	 */
	private Representation getEntryInJSON() {
		try {
			JSONObject jobj = getEntryAsJSONObject();
			return new JsonRepresentation(jobj.toString(2));
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
		} catch (IllegalArgumentException e) {
			log.error(e.getMessage(), e);
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return new JsonRepresentation("{\"error\":\"Internal Server Error\"}");
		}
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
	}


	private JSONObject getEntryAsJSONObject() throws JSONException {
		JSONObject mainJsonObject = new JSONObject();

		GraphType graphType = entry.getGraphType();
		EntryType entryType = entry.getEntryType();

		/*
		 * Entry id
		 */
		mainJsonObject.put("entryId", entry.getId());

		/*
		 * Context or SystemContext
		 */
		if ((graphType == GraphType.Context || graphType == GraphType.SystemContext) && entryType == Local) {
			mainJsonObject.put("name", getCM().getName(entry.getResourceURI()));
			if (entry.getRepositoryManager().hasQuotas()) {
				JSONObject quotaObj = new JSONObject();
				Context c = getCM().getContext(this.entryId);
				if (c != null) {
					quotaObj.put("quota", c.getQuota());
					quotaObj.put("fillLevel", c.getQuotaFillLevel());
				}
				quotaObj.put("hasDefaultQuota", c.hasDefaultQuota());
				mainJsonObject.put("quota", quotaObj);
			}
		}

		/*
		 * Entry information
		 */
		Model entryGraph = entry.getGraph();
		JSONObject entryObj = new JSONObject(RDFJSON.graphToRdfJson(entryGraph));
		mainJsonObject.accumulate("info", entryObj);

		/*
		 * If the parameter includeAll is set we must return more JDIl with
		 * example local metadata, cached external metadata and maybe a
		 * resource. If not set, return now.
		 */
		if (parameters == null || !parameters.containsKey("includeAll")) {
			return mainJsonObject;
		}

		/*
		 * Cached External Metadata
		 */
		if (entryType == LinkReference || entryType == Reference) {
			try {
				Metadata cachedExternalMetadata = entry.getCachedExternalMetadata();
				Model cachedMetadataGraph = cachedExternalMetadata.getGraph();
				if (cachedMetadataGraph != null) {
					JSONObject cachedExternalMetadataJsonObject = new JSONObject(RDFJSON.graphToRdfJson(cachedMetadataGraph));
					mainJsonObject.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, cachedExternalMetadataJsonObject);
				}
			} catch (AuthorizationException ae) {
				//mainJsonObject.accumulate("noAccessToMetadata", true);
				//TODO: Replaced by using "rights" in json, do something else in this catch-clause
			}
		}

		/*
		 * Local Metadata
		 */
		if (entryType == Local || entryType == Link || entryType == LinkReference) {
			try {
				Metadata localMetadata = entry.getLocalMetadata();
				Model localMetadataGraph = localMetadata.getGraph();
				if (localMetadataGraph != null) {
					JSONObject localMetaDataJsonObject = new JSONObject(RDFJSON.graphToRdfJson(localMetadataGraph));
					mainJsonObject.accumulate(RepositoryProperties.MD_PATH, localMetaDataJsonObject);
				}
			} catch (AuthorizationException ae) {
				/*if (!mainJsonObject.has("noAccessToMetadata")) {
					mainJsonObject.accumulate("noAccessToMetadata", true);
				}*/
				//TODO: Replaced by using "rights" in json, do something else in this catch-clause
			}
		}

		/*
		 *	Relations
		 */
		List<Statement> relations = entry.getRelations();
		if (relations != null) {
			JSONObject relationsJsonObject = new JSONObject(RDFJSON.graphToRdfJson(new LinkedHashModel(relations)));
			mainJsonObject.accumulate(RepositoryProperties.RELATION, relationsJsonObject);
		}

		/*
		 * Rights
		 */
		JSONArray rights = resourceSerializer.serializeRights(entry);
		mainJsonObject.put("rights", rights);

		/*
		 * Local resource
		 */
		if (entryType == Local) {
			Resource resource = entry.getResource();
			// As serializeResourceString must return a String, not a JSONObject, we must define this as Object.
			Object resourceObject = (graphType == null) ? IMMUTABLE_EMPTY_JSONOBJECT : switch (graphType) {
				case List -> resourceSerializer.serializeResourceList(resource, new ListParams(parameters));
				case User -> resourceSerializer.serializeResourceUser(resource);
				case Group -> resourceSerializer.serializeResourceGroup(resource);
				case None -> resourceSerializer.serializeResourceNone(resource);
				case String -> resourceSerializer.serializeResourceString(resource);
				case Graph -> resourceSerializer.serializeResourceGraph(resource);
				case Pipeline -> resourceSerializer.serializeResourcePipeline(resource);
				//TODO other types, for example Context, SystemContext, PrincipalManager, etc
				case ResultList, PipelineResult, Context, SystemContext -> IMMUTABLE_EMPTY_JSONOBJECT;
			};
			mainJsonObject.accumulate("resource", resourceObject);
		}
		return mainJsonObject;
	}

	private void modifyEntry(MediaType mediaType) throws AuthorizationException {

		String graphString = null;
		try {
			graphString = getRequest().getEntity().getText();
		} catch (IOException e) {
			log.info(e.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		Model deserializedGraph = null;
		if (APPLICATION_JSON.equals(mediaType)) {
			try {
				JSONObject rdfJSON = new JSONObject(graphString);
				deserializedGraph = RDFJSON.rdfJsonToGraph(rdfJSON);
			} catch (JSONException e) {
				log.info(e.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else {
			deserializedGraph = GraphUtil.deserializeGraph(graphString, mediaType);
		}

		if (deserializedGraph == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		} else {
			entry.setGraph(deserializedGraph);
			if (parameters.containsKey("applyACLtoChildren") &&
					GraphType.List.equals(entry.getGraphType()) &&
					Local.equals(entry.getEntryType())) {
				((org.entrystore.List) entry.getResource()).applyACLtoChildren(true);
			}
			getResponse().setStatus(Status.SUCCESS_OK);
		}
	}
}
