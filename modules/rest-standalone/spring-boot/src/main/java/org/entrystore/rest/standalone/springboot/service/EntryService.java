package org.entrystore.rest.standalone.springboot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.List;
import org.entrystore.Metadata;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.StringResource;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

import static org.entrystore.EntryType.Link;
import static org.entrystore.EntryType.LinkReference;
import static org.entrystore.EntryType.Local;
import static org.entrystore.EntryType.Reference;
import static org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer.IMMUTABLE_EMPTY_JSONOBJECT;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntryService {

	private static final int JSON_OBJECT_TO_STRING_INDENT_SIZE = 0;
	private static final Pattern ENTRY_ID_PATTERN = Pattern.compile("^[\\w\\-]+$");

	private final RepositoryManagerImpl repositoryManager;
	private final ResourceJsonSerializer resourceSerializer;

	private final ObjectMapper objectMapper;

	/**
	 * Checks whether the provided ID contains only allowed characters.
	 *
	 * @return True if supplied ID is valid.
	 */
	public static boolean isEntryIdValid(String id) {
		return ENTRY_ID_PATTERN.matcher(id).matches();
	}

	public GetEntryResponse getEntryInJsonFormat(String contextId, String entryId, String rdfFormat, boolean includeAll, ListFilter listFilter) {
		Context context = getContext(contextId);
		if (context == null) {
			// throw the same exception message for missing Context and missing Entry to avoid leaking information about context existence
			throw new EntityNotFoundException("No entry with id '" + entryId + "' found in context '" + contextId + "'");
		}

		Entry entry = context.get(entryId);
		if (entry == null) {
			throw new EntityNotFoundException("No entry with id '" + entryId + "' found in context '" + contextId + "'");
		}

		return convertEntryToResponseModel(entry, rdfFormat, includeAll, listFilter);
	}

	private Context getContext(String contextId) {
		ContextManager cm = repositoryManager.getContextManager();
		if (cm != null && contextId != null) {
			return cm.getContext(contextId);
		}
		return null;
	}

	private GetEntryResponse convertEntryToResponseModel(Entry entry, String rdfFormat, boolean includeAll, ListFilter listFilter) throws JSONException {

		ContextManager cm = repositoryManager.getContextManager();

		var responseBuilder = GetEntryResponse.builder();

		GraphType graphType = entry.getGraphType();
		EntryType entryType = entry.getEntryType();

		/*
		 * Entry id
		 */
		responseBuilder.entryId(entry.getId());

		/*
		 * Context or SystemContext
		 */
		if ((graphType == GraphType.Context || graphType == GraphType.SystemContext) && entryType == Local) {
			responseBuilder.name(cm.getName(entry.getResourceURI()));
			if (entry.getRepositoryManager().hasQuotas()) {
				JSONObject quotaObj = new JSONObject();
				Context c = cm.getContext(entry.getId());
				if (c != null) {
					quotaObj.put("quota", c.getQuota());
					quotaObj.put("fillLevel", c.getQuotaFillLevel());
					quotaObj.put("hasDefaultQuota", c.hasDefaultQuota());
				}
				responseBuilder.quota(quotaObj.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));
			}
		}

		/*
		 * Entry information
		 */
		Model entryGraph = entry.getGraph();
		JSONObject entryObj = GraphUtil.serializeGraphToJson(entryGraph, rdfFormat);
		responseBuilder.info(entryObj.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));

		/*
		 * If the parameter includeAll is set we must return more JDIl with
		 * example local metadata, cached external metadata and maybe a
		 * resource. If not set, return now.
		 */
		if (!includeAll) {
			return responseBuilder.build();
		}

		/*
		 * Cached External Metadata
		 */
		if (entryType == LinkReference || entryType == Reference) {
			try {
				Metadata cachedExternalMetadata = entry.getCachedExternalMetadata();
				Model cachedMetadataGraph = cachedExternalMetadata.getGraph();
				if (cachedMetadataGraph != null) {
					JSONObject cachedExternalMetadataJsonObject = GraphUtil.serializeGraphToJson(cachedMetadataGraph, rdfFormat);
					responseBuilder.cachedExternalMetadata(cachedExternalMetadataJsonObject.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));
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
					JSONObject localMetaDataJsonObject = GraphUtil.serializeGraphToJson(localMetadataGraph, rdfFormat);
					responseBuilder.metadata(localMetaDataJsonObject.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));
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
		java.util.List<Statement> relations = entry.getRelations();
		if (relations != null) {
			JSONObject relationsJsonObject = GraphUtil.serializeGraphToJson(new LinkedHashModel(relations), rdfFormat);
			responseBuilder.relations(relationsJsonObject.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));
		}

		/*
		 * Rights
		 */
		JSONArray rights = resourceSerializer.serializeRights(entry);
		responseBuilder.rights(rights.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE));

		/*
		 * Local resource
		 */
		if (entryType == Local) {
			Resource resource = entry.getResource();

			String resourceString = null;
			if (graphType == GraphType.String) {
				resourceString = resourceSerializer.serializeResourceString(resource);
			} else {
				JSONObject resourceObject = serializeResourceToJson(resource, graphType, rdfFormat, listFilter);
				if (resourceObject != null) {
					resourceString = resourceObject.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE);
				}
			}

			if (resourceString != null) {
				responseBuilder.resource(resourceString);
			}
		}
		return responseBuilder.build();
	}

	private JSONObject serializeResourceToJson(Resource resource, GraphType graphType, String rdfFormat, ListFilter listFilter) {
		if (graphType == null) {
			return IMMUTABLE_EMPTY_JSONOBJECT;
		}
		return switch (graphType) {
			case List ->
				resourceSerializer.serializeResourceList(resource, new ResourceJsonSerializer.ListParams(listFilter), rdfFormat);
			case User -> resourceSerializer.serializeResourceUser(resource);
			case Group -> resourceSerializer.serializeResourceGroup(resource, rdfFormat);
			case None -> resourceSerializer.serializeResourceNone(resource);
			case Graph -> resourceSerializer.serializeResourceGraph(resource, rdfFormat);
			case Pipeline -> resourceSerializer.serializeResourcePipeline(resource, rdfFormat);
			case String -> null;
			// TODO: other types, for example Context, SystemContext, PrincipalManager, etc
			case ResultList, PipelineResult, Context, SystemContext -> IMMUTABLE_EMPTY_JSONOBJECT;
		};
	}


	/**
	 * Creates a local entry
	 *
	 * @return the new created entry
	 */
	public Entry createLocalEntry(Context context, String entryId, GraphType graphType,
								  URI listUri, URI groupUri, CreateEntryRequestBody body) {

		Entry entry = context.createResource(entryId, graphType, null, listUri);
		try {
			if (setResource(context, entry, body, groupUri)) {
				setLocalMetadataGraph(entry, body);
				setEntryGraph(entry, body);
				if (listUri != null) {
					((ContextImpl) context).copyACL(listUri, entry);
				}
				return entry;
			} else {
				context.remove(entry.getEntryURI());
				return null;
			}
		} catch (JsonProcessingException e) {
			return null;
		}
	}

	/**
	 * Creates a link entry.
	 *
	 * @return the new created entry
	 */
	public Entry createLinkEntry(Context context, String entryId, GraphType graphType, URI resourceUri, URI listUri, CreateEntryRequestBody body) {

		Entry entry = context.createLink(entryId, resourceUri, listUri);

		if (entry != null) {
			setLocalMetadataGraph(entry, body);
			setEntryGraph(entry, body);
			if (graphType != null) {
				entry.setGraphType(graphType);
			}
			if (listUri != null) {
				((ContextImpl) context).copyACL(listUri, entry);
			}
		}
		return entry;
	}

	/**
	 * Creates a Reference entry.
	 *
	 * @return the new created entry
	 */
	public Entry createReferenceEntry(Context context, String entryId, GraphType graphType, URI resourceUri,
									  URI listUri, URI cachedExternalMetadataUri, CreateEntryRequestBody body) {

		if (resourceUri != null &&
			cachedExternalMetadataUri != null) {

			Entry entry = context.createReference(entryId, resourceUri, cachedExternalMetadataUri, listUri);

			if (entry != null) {
				setCachedMetadataGraph(entry, body);
				setEntryGraph(entry, body);
				if (graphType != null) {
					entry.setGraphType(graphType);
				}

				if (listUri != null) {
					((ContextImpl) context).copyACL(listUri, entry);
				}
			}

			return entry;
		}

		return null;
	}


	/**
	 * Creates a LinkReference entry.
	 *
	 * @return the new created entry object.
	 */
	public Entry createLinkReferenceEntry(Context context, String entryId, GraphType graphType, URI resourceUri,
										  URI listUri, URI cachedExternalMetadataUri, CreateEntryRequestBody body) {

		if (resourceUri != null) {

			Entry entry = context.createLinkReference(entryId, resourceUri, cachedExternalMetadataUri, listUri);

			if (entry != null) {
				setLocalMetadataGraph(entry, body);
				setCachedMetadataGraph(entry, body);
				setEntryGraph(entry, body);

				if (graphType != null) {
					entry.setGraphType(graphType);
				}

				if (listUri != null) {
					((ContextImpl) context).copyACL(listUri, entry);
				}
			}

			return entry;
		}

		return null;
	}

	/**
	 * Sets resource to an entry.
	 *
	 * @param context
	 * @param entry       Entry to set the resource on
	 * @param requestBody Input data with "resource" field to be read
	 * @param groupUri
	 * @return false if there is a resource provided, but it cannot be interpreted.
	 * @throws JsonProcessingException Exception if request resource is a malformed JSON
	 */
	private boolean setResource(Context context, Entry entry, CreateEntryRequestBody requestBody, URI groupUri) throws JsonProcessingException {

		ContextManager cm = repositoryManager.getContextManager();

		if (requestBody == null || StringUtils.isEmpty(requestBody.resource())) {
			return true;
		}

		String resource = requestBody.resource().replaceAll("_newId", entry.getId());

		switch (entry.getGraphType()) {

			case User:
				User user = (User) entry.getResource();

				Map<String, Object> resourceMap = objectMapper.readValue(resource, new TypeReference<>() {
				});

				if (resourceMap.containsKey("name")) {
					if (!user.setName(resourceMap.get("name").toString())) {
						return false;
					}
				} else {
					return false;
				}

				if (resourceMap.containsKey("homecontext")) {
					Entry homeContextEntry = cm.get(resourceMap.get("homecontext").toString());
					if (homeContextEntry != null) {
						user.setHomeContext((Context) homeContextEntry.getResource());
					}
				}

				if (groupUri != null) {
					Entry groupEntry = cm.getEntry(groupUri);
					Group group = (Group) groupEntry.getResource();
					group.addMember(user);
				}
				break;

			case Group:
				Group group = (Group) entry.getResource();

				Map<String, Object> groupResource = objectMapper.readValue(resource, new TypeReference<>() {
				});

				if (groupResource.containsKey("name")) {
					group.setName(groupResource.get("name").toString());
				}
				break;

			case List:
				List list = (List) entry.getResource();
				java.util.List<String> listResource = objectMapper.readValue(resource, new TypeReference<>() {
				});

				if (listResource != null) {
					listResource.forEach(childUri -> {
						Entry child = context.get(childUri);
						if (child != null) {
							list.addChild(child.getEntryURI());
						}
					});
				}
				break;

			case Context:
				Context cont = (Context) entry.getResource();

				Map<String, Object> contResource = objectMapper.readValue(resource, new TypeReference<>() {
				});

				if (contResource.containsKey("name")) {
					cm.setName(cont.getURI(), contResource.get("name").toString());
				}
				if (contResource.containsKey("quota")) {
					String quotaString = contResource.get("quota").toString();
					try {
						cont.setQuota(Long.parseLong(quotaString));
					} catch (Exception e) {
						log.warn("Unable to parse new quota value '{}'. Error: {}", quotaString, e.getMessage());
						throw new IllegalArgumentException("Unable to parse new quota value as Long: " + quotaString + ". Error: " + e.getMessage());
					}
				}
				break;

			case String:
				StringResource stringRes = (StringResource) entry.getResource();
				stringRes.setString(resource);
				break;

			case Graph:
			case Pipeline:
				RDFResource RDFRes = (RDFResource) entry.getResource();
				Model g = RDFJSON.rdfJsonToGraph(resource);
				RDFRes.setGraph(g);
				break;
			case PipelineResult:
			case None:
				break;
		}
		return true;
	}

	/**
	 * Extracts metadata from the request body and sets it as the entry's local metadata graph.
	 *
	 * @param entry The entry to set the metadata on
	 */
	private void setLocalMetadataGraph(Entry entry, CreateEntryRequestBody requestBody) {

		if (requestBody == null || StringUtils.isEmpty(requestBody.metadata())) {
			return;
		}
		if (EntryType.Reference.equals(entry.getEntryType())) {
			return;
		}

		try {
			JSONObject mdObj = new JSONObject(requestBody.metadata().replaceAll("_newId", entry.getId()));
			Model graph = RDFJSON.rdfJsonToGraph(mdObj);
			if (graph != null) {
				entry.getLocalMetadata().setGraph(graph);
			}
		} catch (JsonException e) {
			log.warn(e.getMessage());
		}
	}

	/**
	 * First caching of metadata.
	 *
	 * @param entry The entry to set the metadata on.
	 */
	private void setCachedMetadataGraph(Entry entry, CreateEntryRequestBody requestBody) {

		if (requestBody == null || StringUtils.isEmpty(requestBody.cachedExternalMetadata())) {
			return;
		}

		if (EntryType.Reference.equals(entry.getEntryType()) ||
			EntryType.LinkReference.equals(entry.getEntryType())) {
			try {
				JSONObject mdObj = new JSONObject(requestBody.cachedExternalMetadata().replaceAll("_newId", entry.getId()));
				Model graph = RDFJSON.rdfJsonToGraph(mdObj);
				if (graph != null) {
					entry.getCachedExternalMetadata().setGraph(graph);
				}
			} catch (JSONException e) {
				log.warn(e.getMessage());
			}
		}
	}


	/**
	 * Extracts entry info from the request body and sets it as the entry's local metadata graph.
	 * Since it assumes this is the creation step, the Entries URIs was not available
	 * on the client, hence the special "_newId" entryId has been used.
	 * Make sure this is replaced with the new entryId first.
	 *
	 * @param entry The entry to set the metadata on.
	 */
	private void setEntryGraph(Entry entry, CreateEntryRequestBody requestBody) {

		if (requestBody == null || StringUtils.isEmpty(requestBody.info())) {
			return;
		}

		try {
			JSONObject infoJsonObj = new JSONObject(requestBody.info().replaceAll("_newId", entry.getId()));
			Model graph = RDFJSON.rdfJsonToGraph(infoJsonObj);
			if (graph != null) {
				entry.setGraph(graph);
			}
		} catch (JSONException e) {
			log.warn(e.getMessage());
		}
	}

}
