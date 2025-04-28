package org.entrystore.rest.standalone.springboot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.List;
import org.entrystore.User;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.StringResource;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntryService {

	private static final Pattern ENTRY_ID_PATTERN = Pattern.compile("^[\\w\\-]+$");

	private final ObjectMapper objectMapper;
	private final RepositoryManagerImpl repositoryManager;

	/**
	 * Checks whether the provided ID only contains allowed characters.
	 *
	 * @return True if supplied ID is valid.
	 */
	public static boolean isEntryIdValid(String id) {
		return ENTRY_ID_PATTERN.matcher(id).matches();
	}

	/**
	 * Creates a local entry
	 *
	 * @return the new created entry
	 */
	public Entry createLocalEntry(Context context, String entryId, GraphType graphType,
								  URI listUri, URI groupUri, String bodyResource) {

		Entry entry = context.createResource(entryId, graphType, null, listUri);
		try {
			if (setResource(context, entry, bodyResource, groupUri)) {
				setLocalMetadataGraph(entry, bodyResource);
				setEntryGraph(entry, bodyResource);
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
	public Entry createLinkEntry(Context context, String entryId, GraphType graphType, URI resourceUri, URI listUri, String bodyResource) {

		Entry entry = context.createLink(entryId, resourceUri, listUri);

		if (entry != null) {
			setLocalMetadataGraph(entry, bodyResource);
			setEntryGraph(entry, bodyResource);
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
									  URI listUri, URI cachedExternalMetadataUri, String bodyResource) {

		if (resourceUri != null &&
			cachedExternalMetadataUri != null) {

			Entry entry = context.createReference(entryId, resourceUri, cachedExternalMetadataUri, listUri);

			if (entry != null) {
				setCachedMetadataGraph(entry, bodyResource);
				setEntryGraph(entry, bodyResource);
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
										  URI listUri, URI cachedExternalMetadataUri, String bodyResource) {

		if (resourceUri != null) {

			Entry entry = context.createLinkReference(entryId, resourceUri, cachedExternalMetadataUri, listUri);

			if (entry != null) {
				setLocalMetadataGraph(entry, bodyResource);
				setCachedMetadataGraph(entry, bodyResource);
				setEntryGraph(entry, bodyResource);

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
	 * @param entry a reference to a entry
	 * @return false if there is a resource provided but it cannot be interpreted.
	 * @throws JsonProcessingException Exception if payload is malformed
	 */
	private boolean setResource(Context context, Entry entry, String requestResource, URI groupUri) throws JsonProcessingException {

		ContextManager cm = repositoryManager.getContextManager();

		if (StringUtils.isEmpty(requestResource)) {
			return true;
		}

		String resource = requestResource.replaceAll("_newId", entry.getId());

		// If there is no resource there is nothing to do yet
		if (StringUtils.isEmpty(resource)) {
			return true;
		}

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
	 * Extracts metadata from the request and sets it as the entrys local metadata graph.
	 *
	 * @param entry The entry to set the metadata on.
	 */
	private void setLocalMetadataGraph(Entry entry, String requestResource) {

		if (StringUtils.isEmpty(requestResource)) {
			return;
		}
		if (EntryType.Reference.equals(entry.getEntryType())) {
			return;
		}

		try {
			JSONObject mdObj = new JSONObject(requestResource.replaceAll("_newId", entry.getId()));
			if (mdObj.has("metadata")) {
				JSONObject obj = (JSONObject) mdObj.get("metadata");
				Model graph = RDFJSON.rdfJsonToGraph(obj);
				if (graph != null) {
					entry.getLocalMetadata().setGraph(graph);
				}
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
	private void setCachedMetadataGraph(Entry entry, String requestResource) {

		if (StringUtils.isEmpty(requestResource)) {
			return;
		}

		if (EntryType.Reference.equals(entry.getEntryType()) ||
			EntryType.LinkReference.equals(entry.getEntryType())) {
			try {
				JSONObject mdObj = new JSONObject(requestResource.replaceAll("_newId", entry.getId()));
				if (mdObj.has("cached-external-metadata")) {
					JSONObject obj = (JSONObject) mdObj.get("cached-external-metadata");
					Model graph = RDFJSON.rdfJsonToGraph(obj);
					if (graph != null) {
						entry.getCachedExternalMetadata().setGraph(graph);
					}
				}
			} catch (JSONException e) {
				log.warn(e.getMessage());
			}
		}
	}


	/**
	 * Extracts entryinfo from the request and sets it as the entrys local metadata graph.
	 * Since it assumes this is the creation step, the Entries URIs was not available
	 * on the client, hence the special "_newId" entryId has been used.
	 * Make sure this is replaced with the new entryId first.
	 *
	 * @param entry The entry to set the metadata on.
	 */
	private void setEntryGraph(Entry entry, String requestResource) {

		if (StringUtils.isEmpty(requestResource)) {
			return;
		}

		try {
			JSONObject mdObj = new JSONObject(requestResource.replaceAll("_newId", entry.getId()));
			if (mdObj.has("info")) {
				JSONObject obj = (JSONObject) mdObj.get("info");
				Model graph = RDFJSON.rdfJsonToGraph(obj);
				if (graph != null) {
					entry.setGraph(graph);
				}
			}
		} catch (JSONException e) {
			log.warn(e.getMessage());
		}
	}

}
