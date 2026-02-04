package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.AuthorizationException;
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
import org.entrystore.impl.ListImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.FileOperations;
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
import org.entrystore.rest.standalone.springboot.util.Email;
import org.entrystore.rest.standalone.springboot.util.FileUtil;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.NotAcceptableStatusException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	private static final String EMPTY_REPRESENTATION = "";

	private static Boolean rewriteMediaTypeJavaScript;

	private final RepositoryManagerImpl repositoryManager;
	private final ResourceJsonSerializer resourceSerializer;

	private final RestTemplate restTemplate;

	private final SessionRegistry sessionRegistry;

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

	public CompletionState setEntryResource(Entry entry, byte[] requestBody, String mediaType, String mimeType, boolean textArea, String filename, String currentSessionId) {
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
			} catch (AuthorizationException e) {
				throw e;
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
					String newName = entityJSON.getString("name");
					if (!resourceUser.setName(newName)) {
						throw new BadRequestException("Name is already in use: " + newName);
					}
				}
				if (entityJSON.has("password")) {
					boolean requireCurrentPassword = repositoryManager.getConfiguration().getBoolean(Settings.AUTH_PASSWORD_REQUIRE_CURRENT_PASSWORD, true);
					String newPassword = entityJSON.getString("password");

					if (requireCurrentPassword) {
						// we require the current password if:
						// (1) the user is a non-admin user, or
						// (2) the user is an admin user and wants to set his own password
						if (!pm.currentUserIsAdminOrAdminGroup() ||
								(pm.currentUserIsAdminOrAdminGroup() && pm.getAuthenticatedUserURI().equals(resourceUser.getURI()))) {
							if (!entityJSON.has("currentPassword")) {
								throw new ForbiddenException("Current password is required");
							}
							String currentPassword = entityJSON.getString("currentPassword");
							String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(pm, resourceUser.getName());
							if (saltedHashedSecret == null || !Password.check(currentPassword, saltedHashedSecret)) {
								throw new ForbiddenException("No password set or incorrect current password provided");
							}
						}
					}

					if (resourceUser.setSecret(newPassword)) {
						// we need to expire sessions of the user, whose password is being changed

						// if it is an admin/admingroup member, who is changing the password of another user, we expire all sessions of that user
						// if it is an admin/admingroup member changing his own password, or user changing his own password,
						// we expire all sessions of that admin/user except the session, through which it is being changed (currentSessionId)

						// the test only asks if the authenticatedUser is the same as the user, whose password is to be changed
						// because no user can change password of another user, only admin
						boolean expireAllSessions = !pm.getAuthenticatedUserURI().equals(resourceUser.getURI());
						List<Object> allPrincipals = sessionRegistry.getAllPrincipals();

						// go through all principals
						// if the principal matches the principal, whose password is being changed, expire his sessions
						for (Object principal : allPrincipals) {
							if (principal instanceof UserDetails user && user.getUsername().equals(resourceUser.getEntry().getResourceURI().toString())) {
								for (SessionInformation session : sessionRegistry.getAllSessions(user, false)) {
									// do not expire the current session, in case an admin or user is changing his own password
									if (expireAllSessions || !session.getSessionId().equals(currentSessionId)) {
										session.expireNow();
									}
								}
								break;
							}
						}

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

	public Entry importEntryResource(Entry entry, byte[] requestBody, boolean isImport) {

		GraphType graphType = entry.getGraphType();

		if (graphType == GraphType.List
				&& isImport) {

			// Below code does not mutate anything on the entry, only reads a zip file into memory
			importFromZIP(requestBody);
			return null;
		} else {
			throw new BadRequestException("Bad request: supports only Entry graphType of List (given: " + graphType + ") and import with 'application/zip' format and 'import' parameter");
		}
	}

	public Entry modifyListEntryResource(Entry entry, String moveEntry, String fromList, boolean removeAll) {

		GraphType graphType = entry.getGraphType();

		if (graphType == GraphType.List
				&& moveEntry != null
				&& fromList != null) {

			// POST 3/resource/45?moveEntry=2/entry/34&fromList=2/resource/67
			ListImpl dest = (ListImpl) entry.getResource();

			String baseURI = repositoryManager.getRepositoryURL().toString();
			if (!baseURI.endsWith("/")) {
				baseURI += "/";
			}

			// Entry URI of the Entry to be moved
			URI movableEntry = moveEntry.startsWith("http://") ? URI.create(moveEntry) : URI.create(baseURI + moveEntry);
			// Resource URI of the source List
			URI movableEntrySource = fromList.startsWith("http://") ? URI.create(fromList) : URI.create(baseURI + fromList);

			Entry movedEntry;
			try {
				movedEntry = dest.moveEntryHere(movableEntry, movableEntrySource, removeAll);
			} catch (QuotaException qe) {
				throw new EntityTooLargeException(qe.getMessage());
			}

			return movedEntry;
		} else {
			throw new BadRequestException("Bad request: supports only Entry graphType of List (given: " + graphType + ") and moving Entry with 'moveEntry' and 'fromList' parameters.");
		}
	}

	public void deleteResource(Entry entry, String proxy, boolean isRecursive) {
		EntryType entryType = entry.getEntryType();

		if ((entryType == EntryType.Link || entryType == EntryType.Reference || entryType == EntryType.LinkReference)
				&& "true".equalsIgnoreCase(proxy)) {

			deleteRemoteResource(entry.getResourceURI().toString(), 0);
		} else {
			deleteLocalResource(entry, isRecursive);
		}
	}

	private void deleteRemoteResource(String url, int redirectCount) {

		if (redirectCount > 10) {
			log.warn("More than 10 redirect loops detected, aborting");
			return;
		}

		/*
		 * RestTemplate does not automatically follow redirects for DELETE requests.
		 * Instead, it treats a 3xx status code as a client-side error and throws an HttpClientErrorException
		 */
		try {
			ResponseEntity<String> response = restTemplate.exchange(
					url,
					HttpMethod.DELETE,
					null,
					String.class
			);
			// no exception = successfully deleted

		} catch (HttpClientErrorException e) {

			if (e.getStatusCode().is3xxRedirection()) {
				if (e.getResponseHeaders() != null && e.getResponseHeaders().getLocation() != null) {
					String redirectUrl = e.getResponseHeaders().getLocation().toString();
					log.info("DELETE Request redirected to {}", redirectUrl);
					deleteRemoteResource(redirectUrl, ++redirectCount);
				} else {
					throw new InternalServerErrorException("Redirect response received without a Location header.", e);
				}
			} else {
				// Other errors (4xx, 5xx)
				throw new InternalServerErrorException("Delete request received an error response. Message: " + e.getResponseBodyAsString(), e);
			}
		}
	}

	/**
	 * Deletes the resource if the entry has any.
	 */
	private void deleteLocalResource(Entry entry, boolean isRecursive) {
		/*
		 * List
		 */
		if (entry.getGraphType() == GraphType.List) {
			ListImpl l = (ListImpl) entry.getResource();
			if (isRecursive) {
				l.removeTree();
			} else {
				l.setChildren(new Vector<>());
			}
		}

		/*
		 * None
		 */
		if (entry.getGraphType() == GraphType.None) {
			if (entry.getResourceType() == ResourceType.InformationResource) {
				Data data = (Data) entry.getResource();
				if (!data.delete()) {
					log.error("Unable to delete resource of entry {}", entry.getEntryURI());
					// Not sure why 400, should be 500?
					throw new BadRequestException("Unable to delete resource of entry " + entry.getEntryURI());
				}
			}
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

	private void importFromZIP(byte[] requestBody) {
		File tmpFile = null;
		try {
			tmpFile = writeStreamToTmpFile(new ByteArrayInputStream(requestBody));
			if (tmpFile != null && tmpFile.exists()) {
				ZipFile zipFile = new ZipFile(tmpFile);
				Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
				while (zipEntries.hasMoreElements()) {
					ZipEntry entry = zipEntries.nextElement();
					String nameLC = entry.getName();
					if (!entry.isDirectory() && (nameLC.endsWith(".xml") || nameLC.endsWith(".rdf"))) {
						InputStream fileIS = zipFile.getInputStream(entry);
						if (fileIS == null) {
							log.error("Unable to get InputStream of ZipEntry: {}", nameLC);
							continue;
						}
						String fileString;
						try {
							StringWriter writer = new StringWriter();
							IOUtils.copy(fileIS, writer, StandardCharsets.UTF_8);
							fileString = writer.toString();
							if (fileString == null) {
								log.error("[IMPORT] Problem with reading ZipEntry into String");
								continue;
							}
						} finally {
							if (fileIS != null) {
								fileIS.close();
							}
						}
						if (nameLC.endsWith(".rdf")) {
							importRDFResource(fileString);
						}
					}
				}
			} else {
				throw new InternalServerErrorException("Unable to create temporary file for ZIP import");
			}
		} catch (IOException ioe) {
			throw new InternalServerErrorException(ioe.getMessage());
		} finally {
			if (tmpFile != null) {
				tmpFile.delete();
			}
		}
	}

	private File writeStreamToTmpFile(InputStream is) throws IOException {
		File tmpFile = File.createTempFile("entrystore_res_import", ".zip");
		log.info("[IMPORT] Created temporary file: {}", tmpFile);
		OutputStream fos = Files.newOutputStream(tmpFile.toPath());
		FileOperations.copyFile(is, fos);
		return tmpFile;
	}

	private void importRDFResource(String rdfString) {
		// TODO
	}

}
