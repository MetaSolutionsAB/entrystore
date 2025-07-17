package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.QuotaException;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.model.dto.CompletionState;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.standalone.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.standalone.springboot.service.auth.LoginTokenCache;
import org.entrystore.rest.standalone.springboot.util.Email;
import org.entrystore.rest.standalone.springboot.util.FileUtil;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.NotAcceptableStatusException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	private static final String EMPTY_REPRESENTATION = "";

	private static Boolean rewriteMediaTypeJavaScript;

	private final RepositoryManagerImpl repositoryManager;
	private final ResourceJsonSerializer resourceSerializer;
	private final LoginTokenCache loginTokenCache;

	@PostConstruct
	public void init() {
		// Runs after class constructor
		rewriteMediaTypeJavaScript = repositoryManager.getConfiguration().getBoolean(Settings.HTTP_ALLOW_MEDIA_TYPE_JAVASCRIPT, false);
	}

	public String serializeResourceAsJson(Entry entry, String mediaType, ListFilter listFilter) {

		EntryType entryType = entry.getEntryType();
		GraphType graphType = entry.getGraphType();
		ResourceType resourceType = entry.getResourceType();

		// Resource missing
		if (graphType == null) {
			throw new EntityNotFoundException("No resource available for entry: " + entry.getResourceURI());
		}

		// Graph and List resource

		if (graphType == GraphType.Graph || graphType == GraphType.List) {
			boolean isList = (entry.getGraphType() == GraphType.List);
			Model graph;

			if (isList) {
				graph = ((org.entrystore.List) entry.getResource()).getGraph();
			} else {
				graph = ((RDFResource) entry.getResource()).getGraph();
			}

			if (graph != null) {
				String serializedGraph;
				if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType)) {
					if (isList) {
						return serializeJsonRepresentationResourceList(entry, listFilter);
					}
					serializedGraph = RDFJSON.graphToRdfJson(graph);
				} else {
					serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
				}

				if (serializedGraph != null) {
					return serializedGraph;
				} else {
					throw new NotAcceptableStatusException("Unknown requested format");
				}
			}
		}

		// Remote Document (GraphType == None) Resource

		if (entryType == EntryType.Link || entryType == EntryType.LinkReference || entryType == EntryType.Reference) {
			if (graphType == GraphType.None) {
				throw new RedirectSeeOtherException(entry.getResourceURI());
			}
		}

		/*
		 * Local Resource
		 */
		if (entryType == EntryType.Local) {

			// Named Local Resource
			if (resourceType == ResourceType.NamedResource) {
				throw new RedirectSeeOtherException(entry.getLocalMetadataURI());
			}

			try {
				Resource resource = entry.getResource();
				return switch (graphType) {
					case User -> resourceSerializer.serializeResourceUser(resource).toString();
					case Group -> resourceSerializer.serializeResourceGroup(resource, mediaType).toString();
					case Context -> resourceSerializer.serializeResourceContext(resource).toString();
					case SystemContext -> resourceSerializer.serializeResourceSystemContext(resource).toString();
					case Pipeline -> {
						if (resource instanceof RDFResource pipeline) {
							if (pipeline.getGraph() == null) {
								throw new EntityNotFoundException("The pipeline has not been set");
							}
							yield resourceSerializer.serializeResourcePipeline(pipeline, mediaType).toString();
						}
						yield EMPTY_REPRESENTATION;
					}
					case ResultList, PipelineResult -> EMPTY_REPRESENTATION;
					default -> EMPTY_REPRESENTATION;
				};
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException(e);
			}
		}
		return EMPTY_REPRESENTATION;
	}

	public File serializeResourceNoneAsFile(Entry entry) {

		if (entry.getResourceType() == ResourceType.InformationResource) {
			// Local data
			return ((Data) entry.getResource()).getDataFile();
		} else if (entry.getResourceType() == ResourceType.NamedResource) {
			// If there is no resource we redirect to the metadata
			throw new RedirectSeeOtherException(entry.getLocalMetadataURI());
		}
		// NOT USED YET
		//	if (ResourceType.Unknown.equals(entry.getResourceType())) {}
		return null;
	}

	public String serializeResourceString(Entry entry) {

		return resourceSerializer.serializeResourceString(entry.getResource());
	}

	public CompletionState setEntryResource(Entry entry, byte[] requestBody, String mediaType, String mimeType, boolean textArea, String filename) {
		GraphType gt = entry.getGraphType();
		/*
		 * List and Group
		 */
		if (GraphType.List.equals(gt) || GraphType.Group.equals(gt)) {

			if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType)) {
				try {
					JSONArray childrenJSONArray = new JSONArray(new String(requestBody, StandardCharsets.UTF_8));
					ArrayList<URI> newResource = new ArrayList<>();

					// Add new entries to the list.
					for (int i = 0; i < childrenJSONArray.length(); i++) {
						String childId = childrenJSONArray.get(i).toString();
						Entry childEntry = entry.getContext().get(childId);
						if (childEntry == null) {
							throw new BadRequestException("Cannot update list, since one of the children does not exist. ChildId: " + childId);
						} else {
							newResource.add(childEntry.getEntryURI());
						}
					}

					if (entry.getGraphType() == GraphType.List) {
						org.entrystore.List resourceList = (org.entrystore.List) entry.getResource();
						resourceList.setChildren(newResource);
					} else {
						Group resourceGroup = (Group) entry.getResource();
						resourceGroup.setChildren(newResource);
					}
					return CompletionState.UPDATED;
				} catch (JSONException e) {
					throw new BadRequestException("Cannot parse given body resource as JSONArray.");
				} catch (RepositoryException re) {
					throw new DataConflictException("An entry cannot be added multiple times. Exception: " + re.getMessage());
				}
			} else {
				Model graph = GraphUtil.deserializeGraph(new String(requestBody, StandardCharsets.UTF_8), mediaType);
				if (graph != null && GraphType.List.equals(entry.getGraphType())) {
					((org.entrystore.List) entry.getResource()).setGraph(graph);
				} else {
					throw new BadRequestException("Bad request, just in general...");
				}
				// TODO: add support for groups here
			}
			return CompletionState.UPDATED;
		}

		/*
		 * Data
		 */
		if (gt == GraphType.None) {

			if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(mediaType)) {
				throw new BadRequestException("Content negotiation failure, Multipart file content should be handled by other endpoint.");
			} else {
				try {
					((Data) entry.getResource()).setData(new ByteArrayInputStream(requestBody));
					entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
					if (mimeType == null) {
						mimeType = Objects.requireNonNullElse(mediaType, MediaType.APPLICATION_OCTET_STREAM_VALUE);
					}
					entry.setMimetype(mimeType);
					if (StringUtils.isNotEmpty(filename)) {
						entry.setFilename(FileUtil.sanitizeFilename(filename.trim()));
					}
				} catch (QuotaException qe) {
					throw new EntityTooLargeException(qe.getMessage());
				} catch (IOException ioe) {
					if (ioe.getCause() instanceof NullPointerException) {
						throw new BadRequestException(ioe.getCause().getMessage());
					} else {
						throw new InternalServerErrorException(ioe.getMessage());
					}
				}
			}
			// TODO: add textarea TEXT_HTML response inside ExceptionHandler, when textArea param is set
			/*
			if (error != null) {
				if (textArea) {
					// TODO
					//getResponse().setEntity("<textarea>{\"error\":\"" + error + "\"}</textarea>", MediaType.TEXT_HTML);
					return CompletionState.ERROR;
				} else {
					throw new InternalServerErrorException(error);
				}
			}*/

			return CompletionState.CREATED;
		}

		/* String  */
		if (gt == GraphType.String) {
			try {
				StringResource stringResource = (StringResource) entry.getResource();
				stringResource.setString(new String(requestBody, StandardCharsets.UTF_8));
			} catch (Exception e) {
				throw new BadRequestException("Problem with input. Error: " + e.getMessage());
			}

			return CompletionState.UPDATED;
		}

		/* Graph and Pipeline */
		if (gt == GraphType.Graph || gt == GraphType.Pipeline) {
			RDFResource graphResource = (RDFResource) entry.getResource();
			if (graphResource != null) {
				Model graph;
				try {
					graph = GraphUtil.deserializeGraph(new String(requestBody, StandardCharsets.UTF_8), mediaType);
				} catch (Exception e) {
					throw new BadRequestException("Unable to read request entity. Error: " + e.getMessage());
				}
				if (graph != null) {
					graphResource.setGraph(graph);
				} else {
					throw new BadRequestException("Unable to parse request entity.");
				}
			} else {
				throw new InternalServerErrorException("No RDF resource found for entry with ResourceType Graph");
			}

			return CompletionState.UPDATED;
		}

		/* User */
		if (GraphType.User.equals(gt)) {
			PrincipalManager pm = repositoryManager.getPrincipalManager();
			JSONObject entityJSON;
			try {
				entityJSON = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));

				User resourceUser = (User) entry.getResource();
				if (entityJSON.has("name")) {
					String oldName = resourceUser.getName();
					String newName = entityJSON.getString("name");
					if (resourceUser.setName(newName)) {
						// the username was successfully changed, so we need to update the UserInfo
						// objects in the LoginTokenCache to not invalidate logged in user sessions
						loginTokenCache.renameUser(oldName, newName);
					} else {
						throw new BadRequestException("Name is already in use: " + newName);
					}
				}
				if (entityJSON.has("password")) {
					boolean requireCurrentPassword = repositoryManager.getConfiguration().getBoolean(Settings.AUTH_PASSWORD_REQUIRE_CURRENT_PASSWORD, true);
					String newPassword = entityJSON.getString("password");

					if (requireCurrentPassword) {
						// we require the current password if:
						// (1) the user is a non-admin user, or
						// (2) the user is an admin user and wants to set her own password
						if (!pm.currentUserIsAdminOrAdminGroup() ||
								(pm.currentUserIsAdminOrAdminGroup() && pm.getAuthenticatedUserURI().equals(resourceUser.getURI()))) {
							if (!entityJSON.has("currentPassword")) {
								throw new BadRequestException("Current password is required");
							}
							String currentPassword = entityJSON.getString("currentPassword");
							String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(pm, resourceUser.getName());
							if (saltedHashedSecret == null || !Password.check(currentPassword, saltedHashedSecret)) {
								throw new ForbiddenException("No password set or incorrect current password provided");
							}
						}
					}

					if (resourceUser.setSecret(newPassword)) {
						// TODO: Fix this accordingly to ENTRYSTORE-914 result
						//loginTokenCache.removeTokensButOne(CookieVerifier.getAuthToken(getRequest()));
						Email.sendPasswordChangeConfirmation(repositoryManager.getConfiguration(), entry);
					} else {
						throw new BadRequestException("Password must conform to configured rules.");
					}
				}
				if (entityJSON.has("language")) {
					String prefLang = entityJSON.getString("language");
					if (prefLang.isEmpty()) {
						resourceUser.setLanguage(null);
					} else if (!resourceUser.setLanguage(prefLang)) {
						throw new BadRequestException("Preferred language could not be set.");
					}
				}
				if (entityJSON.has("homecontext")) {
					String homeContext = entityJSON.getString("homecontext");
					Entry entryHomeContext = repositoryManager.getContextManager().get(homeContext);
					if (entryHomeContext != null) {
						if (!(entryHomeContext.getResource() instanceof Context)
								|| !resourceUser.setHomeContext((Context) entryHomeContext.getResource())) {

							throw new BadRequestException("Given homecontext is not a context.");
						}
					}
				}
				if (entityJSON.has("disabled")) {
					if (entry.getResourceURI().equals(pm.getAuthenticatedUserURI())) {
						throw new BadRequestException("Users cannot set their own disabled status.");
					}
					boolean disabled = entityJSON.optBoolean("disabled", false);
					resourceUser.setDisabled(disabled);
					if (disabled) {
						String userName = pm.getPrincipalName(entry.getResourceURI());
						loginTokenCache.removeTokens(userName);
					}
				}
				if (entityJSON.has("customProperties")) {
					Map<String, String> customPropMap = new HashMap<>();
					JSONObject customPropJson = entityJSON.getJSONObject("customProperties");
					for (Iterator<String> cPIt = customPropJson.keys(); cPIt.hasNext(); ) {
						String key = cPIt.next();
						customPropMap.put(key, customPropJson.getString(key));
					}
					resourceUser.setCustomProperties(customPropMap);
				}

				return CompletionState.UPDATED;
			} catch (JSONException e) {
				throw new BadRequestException("Error in JSON syntax");
			}
		}

		return CompletionState.ERROR;
	}

	public CompletionState setEntryResourceMultipart(Entry entry, MultipartFile file, String mimeType) {

		if (entry.getGraphType() != GraphType.None) {
			throw new BadRequestException("Cannot set resource for entry with GraphType " + entry.getGraphType() + ". Only None GraphType can set a multipart file.");
		}

		try {
			long maxFileSize = repositoryManager.getMaximumFileSize();

			// we check if the file is not too big
			if (maxFileSize != -1 && file.getSize() > maxFileSize) {
				throw new BadRequestException("Received file size (of " + file.getSize() + "b) exceeds maximum allowed size of: " + maxFileSize + "b");
			}

			((Data) entry.getResource()).setData(file.getInputStream());
			entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
			String itemMimeType = file.getContentType();
			if (mimeType != null) {
				itemMimeType = mimeType;
			}
			entry.setMimetype(itemMimeType);
			// Documentation for MultipartFile.getName() says it's never null or empty
			String name = file.getName();
			entry.setFilename(FileUtil.sanitizeFilename(name.trim()));

			return CompletionState.CREATED;
		} catch (IOException ioe) {
			throw new InternalServerErrorException(ioe.getMessage());
		} catch (QuotaException qe) {
			throw new EntityTooLargeException(qe.getMessage());
		}
	}

	private String serializeJsonRepresentationResourceList(Entry entry,
														   ListFilter listFilter) {
		JSONArray array = new JSONArray();
		org.entrystore.List l = (org.entrystore.List) entry.getResource();
		List<URI> uris = l.getChildren();
		Set<String> IDs = new HashSet<>();
		for (URI u : uris) {
			String id = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/') + 1);
			IDs.add(id);
		}

		if (listFilter.sort() != null && (IDs.size() < 501)) {
			List<Entry> childrenEntries = new ArrayList<>();
			for (String id : IDs) {
				Entry childEntry = entry.getContext().get(id);
				if (childEntry != null) {
					childrenEntries.add(childEntry);
				} else {
					log.warn("Child entry {} in context {} does not exist, but is referenced by a list.", id, entry.getContext().getURI());
				}
			}

			Date before = new Date();
			boolean asc = !"desc".equalsIgnoreCase(listFilter.order());
			GraphType prioritizedResourceType = null;
			if (listFilter.prio() != null) {
				prioritizedResourceType = GraphType.valueOf(listFilter.prio());
			}
			String sortType = listFilter.sort();
			if ("title".equalsIgnoreCase(sortType)) {
				String lang = listFilter.lang();
				EntryUtil.sortAfterTitle(childrenEntries, lang, asc, prioritizedResourceType);
			} else if ("modified".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterModificationDate(childrenEntries, asc, prioritizedResourceType);
			} else if ("created".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterCreationDate(childrenEntries, asc, prioritizedResourceType);
			} else if ("size".equalsIgnoreCase(sortType)) {
				EntryUtil.sortAfterFileSize(childrenEntries, asc, prioritizedResourceType);
			}
			long sortDuration = new Date().getTime() - before.getTime();
			log.debug("List entry sorting took {} ms", sortDuration);

			for (Entry childEntry : childrenEntries) {
				URI childURI = childEntry.getEntryURI();
				String id = (childURI.toASCIIString()).substring((childURI.toASCIIString()).lastIndexOf('/') + 1);
				array.put(id);
			}
		} else {
			if (IDs.size() > 500) {
				log.warn("No sorting performed because of list size bigger than 500 children");
			}
			for (String id : IDs) {
				array.put(id);
			}
		}
		return array.toString();
	}

	public String determineMediaTypeForDownload(Entry entry) {
		String medTyp = entry.getMimetype();
		if (medTyp != null) {
			try {
				if (rewriteMediaTypeJavaScript) {
					if (medTyp.toLowerCase().contains("javascript")) {
						log.info("Rewriting media type {} to text/plain for {}", medTyp, entry.getResourceURI());
						medTyp = MediaType.TEXT_PLAIN_VALUE;
					}
				}
				return medTyp;
			} catch (IllegalArgumentException iae) {
				log.warn("Invalid media type for {}: {}", entry.getEntryURI(), iae.getMessage());
				return MediaType.ALL_VALUE;
			}
		} else {
			return MediaType.APPLICATION_OCTET_STREAM_VALUE;
		}

	}

}
