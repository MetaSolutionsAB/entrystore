package org.entrystore.rest.standalone.springboot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrDocument;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
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
import org.entrystore.PrincipalManager;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.exception.EntryMissingException;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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

	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;
	private final ReservedNamesService reservedNamesService;
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
		Entry entry = getEntryByContextIdAndEntryId(contextId, entryId);
		return convertEntryToResponseModel(entry, rdfFormat, includeAll, listFilter);
	}

	public String getEntryInRdfFormat(String contextId, String entryId, String mediaType) {
		Entry entry = getEntryByContextIdAndEntryId(contextId, entryId);

		Model graph = entry.getGraph();
		String serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
		if (serializedGraph == null) {
			// TODO: not sure we should throw a 400 here (should be 500?), but this was the Restlet logic
			throw new BadRequestException("Bad request");
		}
		return serializedGraph;
	}

	public Entry getEntryByContextIdAndEntryId(String contextId, String entryId) {
		Context context = contextService.getContext(contextId);
		if (context == null) {
			// throw the same exception message for missing Context and missing Entry to avoid leaking information about context existence
			throw new EntityNotFoundException("No entry with id '" + entryId + "' found in context '" + contextId + "'");
		}

		Entry entry = context.get(entryId);
		if (entry == null) {
			throw new EntityNotFoundException("No entry with id '" + entryId + "' found in context '" + contextId + "'");
		}

		return entry;
	}

	/**
	 * Validates if the currently authenticated user has the specified access property to the given entry.
	 *
	 * @param entry          The entry object for which access needs to be checked.
	 * @param accessProperty The access property defining the type of access to be validated.
	 * @throws AuthorizationException If the user does not have the required access permissions.
	 */
	public void checkEntryUserAccess(Entry entry, PrincipalManager.AccessProperty accessProperty) throws AuthorizationException {
		principalManager.checkAuthenticatedUserAuthorized(entry, accessProperty);
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
		Model relations = entry.getRelations();
		if (relations != null) {
			JSONObject relationsJsonObject = GraphUtil.serializeGraphToJson(relations, rdfFormat);
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
			if (resource == null) {
				log.error("Resource is null for EntryId '{}', GraphType '{}', skipping resource serialization",
						entry.getId(), graphType);
			} else {
				try {
					String resourceString = serializeResourceToRawJsonString(resource, graphType, rdfFormat, listFilter);
					if (resourceString != null) {
						responseBuilder.resource(resourceString);
					}
				} catch (RuntimeException e) {
					// serialization path can throw IllegalArgumentException, RepositoryException (from context.getEntries() → RDF4J) or JSONException
					log.error("Failed to serialize resource for EntryId '{}', GraphType '{}', skipping resource serialization. Error: {}",
							entry.getId(), graphType, e.getMessage(), e);
				}
			}
		}
		return responseBuilder.build();
	}

	private String serializeResourceToRawJsonString(Resource resource, GraphType graphType, String rdfFormat, ListFilter listFilter) {
		if (graphType == GraphType.String) {
			return resourceSerializer.serializeResourceString(resource);
		}
		if (graphType == GraphType.Context || graphType == GraphType.SystemContext) {
			return resourceSerializer.serializeResourceContext(resource)
					.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE);
		}
		JSONObject jsonObject = serializeResourceToJson(resource, graphType, rdfFormat, listFilter);

		return jsonObject != null ?
				jsonObject.toString(JSON_OBJECT_TO_STRING_INDENT_SIZE)
				: null;
	}

	// TODO: move this method to ResourceSerializer class?
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
			case String, Context, SystemContext -> null;
			// TODO: other types, for example PrincipalManager, etc
			case ResultList, PipelineResult -> IMMUTABLE_EMPTY_JSONOBJECT;
		};
	}

	public Entry createEntry(String contextId, String entryId, EntryType entryType, GraphType graphType,
							 URI resourceUri, URI listUri, URI groupUri, URI cachedExternalMetadataUri,
							 String informationResource, URI templateUri, CreateEntryRequestBody body) {

		Context context = contextService.getContextOrThrow(contextId);

		if (entryId != null) {
			if (!EntryService.isEntryIdValid(entryId)) {
				throw new BadRequestException("Invalid entry ID of '" + entryId + "'");
			}
			Entry preExistingEntry = context.get(entryId);
			if (preExistingEntry != null) {
				throw new DataConflictException("Entry with provided ID already exists. EntryID: '" + entryId + "'");
			}
		}

		Entry entry = null; // A variable to store the new entry in.

		try {
			// Local
			if (entryType == null || entryType == EntryType.Local) {
				entry = createLocalEntry(context, entryId, graphType, listUri, groupUri, body);
			} else {
				// Link
				if (entryType == EntryType.Link && resourceUri != null) {
					entry = createLinkEntry(context, entryId, graphType, resourceUri, listUri, body);
				}
				// Reference
				else if (entryType == EntryType.Reference
						&& resourceUri != null
						&& cachedExternalMetadataUri != null) {

					entry = createReferenceEntry(context, entryId, graphType, resourceUri, listUri, cachedExternalMetadataUri, body);
				}
				// LinkReference
				else if (entryType == EntryType.LinkReference
						&& resourceUri != null
						&& cachedExternalMetadataUri != null) {

					entry = createLinkReferenceEntry(context, entryId, graphType, resourceUri, listUri, cachedExternalMetadataUri, body);
				}
			}
		} catch (IllegalArgumentException iae) {
			throw new BadRequestException(iae.getMessage());
		}

		if (entry != null) {
			ResourceType rt = mapToResourceType(informationResource);
			entry.setResourceType(rt);

			if (templateUri != null) {
				Entry templateEntry = context.getByEntryURI(templateUri);
				if (templateEntry != null && templateEntry.getLocalMetadata() != null) {
					Model templateMD = templateEntry.getLocalMetadata().getGraph();
					Model inheritedMD = new LinkedHashModel();
					if (templateMD != null) {
						ValueFactory vf = repositoryManager.getValueFactory();
						IRI oldResURI = vf.createIRI(templateEntry.getResourceURI().toString());
						IRI newResURI = vf.createIRI(entry.getResourceURI().toString());

						java.util.List<IRI> predicateBlackList = new ArrayList<>();
						predicateBlackList.add(vf.createIRI(NS.dc, "title"));
						predicateBlackList.add(vf.createIRI(NS.dcterms, "title"));
						predicateBlackList.add(vf.createIRI(NS.dc, "description"));
						predicateBlackList.add(vf.createIRI(NS.dcterms, "description"));
						java.util.List<Value> subjectBlackList = new ArrayList<>();

						for (Statement statement : templateMD) {
							if (predicateBlackList.contains(statement.getPredicate())) {
								subjectBlackList.add(statement.getObject());
								continue;
							}
							if (subjectBlackList.contains(statement.getSubject())) {
								continue;
							}
							if (statement.getSubject().equals(oldResURI)) {
								inheritedMD.add(newResURI, statement.getPredicate(), statement.getObject(), statement.getContext());
							} else {
								inheritedMD.add(statement);
							}
						}
					}
					if (!inheritedMD.isEmpty() && entry.getLocalMetadata() != null) {
						Model mergedGraph = new LinkedHashModel();
						mergedGraph.addAll(entry.getLocalMetadata().getGraph());
						mergedGraph.addAll(inheritedMD);
						entry.getLocalMetadata().setGraph(mergedGraph);
					}
				}
			}
		}

		if (entry == null) {
			throw new BadRequestException("Cannot create an entry with provided JSON");
		} else {
			return entry;
		}
	}

	private ResourceType mapToResourceType(String rt) {
		if (rt == null || !rt.equals("false")) {
			return ResourceType.InformationResource;
		} else {
			return ResourceType.NamedResource;
		}
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

	public Entry modifyEntry(String contextId, String entryId, String body, String mediaType, boolean applyACLtoChildren) throws AuthorizationException {
		Entry entry = getEntryByContextIdAndEntryId(contextId, entryId);
		return modifyEntry(entry, body, mediaType, applyACLtoChildren);
	}

	public Entry modifyEntry(Entry entry, String body, String mediaType, boolean applyACLtoChildren) throws AuthorizationException {

		Model deserializedGraph;
		if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType)) {
			try {
				JSONObject rdfJSON = new JSONObject(body);
				deserializedGraph = RDFJSON.rdfJsonToGraph(rdfJSON);
			} catch (JSONException e) {
				log.info(e.getMessage());
				throw new BadRequestException("Exception processing request body: " + e.getMessage());
			}
		} else {
			deserializedGraph = GraphUtil.deserializeGraph(body, mediaType);
		}

		if (deserializedGraph == null) {
			throw new BadRequestException("Unable to parse the request body in the requested format: " + mediaType);
		} else {
			entry.setGraph(deserializedGraph);
			if (applyACLtoChildren &&
					GraphType.List.equals(entry.getGraphType()) &&
					Local.equals(entry.getEntryType())) {
				((org.entrystore.List) entry.getResource()).applyACLtoChildren(true);
			}
			return entry;
		}
	}

	public void deleteEntry(String contextId, String entryId, boolean recursive) {

		Entry entry = getEntryByContextIdAndEntryId(contextId, entryId);

		try {
			if (GraphType.List.equals(entry.getGraphType()) && recursive) {
				org.entrystore.List l = (org.entrystore.List) entry.getResource();
				if (l != null) {
					l.removeTree();
				} else {
					log.warn("Resource of the following list is null: {}", entry.getEntryURI());
				}
			} else {
				entry.getContext().remove(entry.getEntryURI());
			}
		} catch (AuthorizationException e) {
			throw new UnauthorizedException("Not authorized");
		} catch (EntryMissingException e) {
			throw new EntityNotFoundException("Entry requested for deletion was not found. Error: " + e.getMessage());
		}
	}

	public String getEntryName(Entry entry) {
		String name = null;
		GraphType bt = entry.getGraphType();
		if (GraphType.Group.equals(bt)) {
			name = ((Group) entry.getResource()).getName();
		} else if (GraphType.User.equals(bt)) {
			name = ((User) entry.getResource()).getName();
		} else if (GraphType.Context.equals(bt)) {
			ContextManager cm = repositoryManager.getContextManager();
			Context c = cm.getContext(entry.getId());
			name = cm.getName(c.getURI());
		}
		return name;
	}

	public boolean setEntryName(Entry entry, String requestName) {

		String newName;
		if (StringUtils.isEmpty(requestName)) {
			newName = null;
		} else {
			newName = requestName.trim();
		}

		GraphType bt = entry.getGraphType();
		boolean success = false;

		if (GraphType.Group.equals(bt)) {
			success = ((Group) entry.getResource()).setName(newName);

		} else if (GraphType.User.equals(bt)) {
			// Users must always have a name
			if (newName == null) {
				throw new BadRequestException("User must have a name.");
			}
			success = ((User) entry.getResource()).setName(newName);

		} else if (GraphType.Context.equals(bt)) {
			if (reservedNamesService.isReservedName(StringUtils.trimToEmpty(newName).toLowerCase())) {
				throw new BadRequestException("Requested name to be set of '" + newName + "' is a reserved word.");
			} else {
				ContextManager cm = repositoryManager.getContextManager();
				Context c = cm.getContext(entry.getId());
				success = cm.setName(c.getURI(), newName);
			}
		}

		return success;
	}

	public Map<String, Object> getEntryIndex(Entry entry) {
		SolrDocument doc = ((SolrSearchIndex) repositoryManager.getIndex()).fetchDocument(entry.getEntryURI().toString());
		if (doc == null) {
			throw new EntityNotFoundException("Entry Index data not found.");
		}

		Map<String, Object> result = new HashMap<>();
		for (String field : doc.getFieldValuesMap().keySet()) {
			Collection<Object> values = doc.getFieldValues(field);
			if (values.size() > 1) {
				result.put(field, values);
			} else if (values.size() == 1) {
				result.put(field, values.iterator().next());
			}
		}
		return result;
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
