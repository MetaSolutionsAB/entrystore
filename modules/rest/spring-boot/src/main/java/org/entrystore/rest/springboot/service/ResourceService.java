/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.service;

import lombok.RequiredArgsConstructor;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.RepositoryException;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.ResourceQuery;
import org.entrystore.rest.springboot.model.dto.CompletionState;
import org.entrystore.rest.springboot.model.dto.RenderedFeed;
import org.entrystore.rest.springboot.model.dto.ResourceRepresentation;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Entry point for the {@code /{context-id}/resource/{entry-id}} operations. Dispatches on the entry's graph type
 * to {@link ListResourceService}, {@link FileResourceService}, {@link UserService} and {@link ProxyService}, and
 * keeps the String and Graph/Pipeline handling that is small enough not to need a home of its own.
 */
@Service
@RequiredArgsConstructor
public class ResourceService {

	private static final String EMPTY_REPRESENTATION = "";

	private final ResourceJsonSerializer resourceSerializer;
	private final PrincipalManager principalManager;
	private final SyndicationService syndicationService;
	private final ListResourceService listResourceService;
	private final FileResourceService fileResourceService;
	private final UserService userService;
	private final ProxyService proxyService;

	/**
	 * Chooses the representation a GET on the resource URI answers with. A syndication request is served before
	 * the entry's own type is considered. May throw {@code RedirectSeeOtherException} for named resources.
	 */
	public ResourceRepresentation getResourceRepresentation(Entry entry, ResourceQuery query) {
		if (query.syndication() != null) {
			RenderedFeed feed = syndicationService.renderFeed(entry, query.syndication(), query.language(),
					query.feedSize());
			return new ResourceRepresentation.TextBody(feed.xml(), feed.mediaType());
		}

		EntryType entryType = entry.getEntryType();
		GraphType graphType = entry.getGraphType();

		if (entryType == EntryType.Local && graphType == GraphType.None) {
			File file = fileResourceService.dataFileForDownload(entry);
			if (file == null) {
				return new ResourceRepresentation.Empty();
			}
			String filename = Objects.requireNonNullElse(entry.getFilename(), entry.getId());
			return new ResourceRepresentation.FileDownload(file, fileResourceService.mediaTypeForDownload(entry),
					filename, resourceSerializer.readDigest(entry));
		}

		if (entryType == EntryType.Local && graphType == GraphType.String) {
			// A String resource without a rdf:value has no text; the answer is an empty body, not an error.
			String text = Objects.requireNonNullElse(
					resourceSerializer.serializeResourceString(entry.getResource()), "");
			return new ResourceRepresentation.TextBody(text, MediaType.TEXT_PLAIN);
		}

		if (graphType == GraphType.Graph || graphType == GraphType.List) {
			String rdfMediaType = GraphUtil.resolveRdfMediaType(query.rdfFormat(), query.acceptHeader());
			return new ResourceRepresentation.TextBody(serializeResourceAsJson(entry, rdfMediaType, query.listFilter()),
					MediaType.parseMediaType(rdfMediaType));
		}

		String mediaType = query.rdfFormat() != null ? query.rdfFormat().toString() : query.acceptHeader();
		return new ResourceRepresentation.TextBody(serializeResourceAsJson(entry, mediaType, query.listFilter()),
				MediaType.APPLICATION_JSON);
	}

	String serializeResourceAsJson(Entry entry, String mediaType, ListFilter listFilter) {

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
				if (isList && MediaType.APPLICATION_JSON_VALUE.equals(mediaType)) {
					return listResourceService.serializeChildrenIds(entry, listFilter);
				}
				// serializeGraph routes application/json and application/rdf+json through RDFJSON itself
				return GraphUtil.serializeGraph(graph, mediaType);
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

	/**
	 * Routes a PUT body to the service owning the entry's graph type and bumps the entry's modification date.
	 * A binary upload is reported as {@link CompletionState#CREATED}, every other update as
	 * {@link CompletionState#UPDATED}; an unsupported graph type answers {@link CompletionState#ERROR} and leaves
	 * the entry untouched.
	 */
	public CompletionState setEntryResource(Entry entry, byte[] requestBody, String mediaType, String mimeType,
											String filename, String currentSessionId) {
		CompletionState state = switch (entry.getGraphType()) {
			case List, Group -> {
				if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType)) {
					listResourceService.setChildrenFromJson(entry, requestBody);
				} else {
					listResourceService.setGraph(entry, requestBody, mediaType);
				}
				yield CompletionState.UPDATED;
			}
			case None -> {
				fileResourceService.setData(entry, requestBody, mediaType, mimeType, filename);
				yield CompletionState.CREATED;
			}
			case String -> {
				setString(entry, requestBody);
				yield CompletionState.UPDATED;
			}
			case Graph, Pipeline -> {
				setGraph(entry, requestBody, mediaType);
				yield CompletionState.UPDATED;
			}
			case User -> {
				userService.updateSettings(entry, requestBody, currentSessionId);
				yield CompletionState.UPDATED;
			}
			case null, default -> CompletionState.ERROR;
		};
		if (state != CompletionState.ERROR) {
			entry.updateModificationDate();
		}
		return state;
	}

	private static void setString(Entry entry, byte[] requestBody) {
		try {
			StringResource stringResource = (StringResource) entry.getResource();
			stringResource.setString(new String(requestBody, StandardCharsets.UTF_8));
		} catch (RepositoryException e) {
			throw new InternalServerErrorException("Failed to store string resource for entry " + entry.getEntryURI(), e);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("Invalid string resource payload for entry " + entry.getEntryURI(), e);
		} catch (ClassCastException e) {
			throw new InternalServerErrorException("Resource of entry " + entry.getEntryURI() + " is not a StringResource", e);
		}
	}

	private static void setGraph(Entry entry, byte[] requestBody, String mediaType) {
		if (!(entry.getResource() instanceof RDFResource graphResource)) {
			throw new InternalServerErrorException("No RDF resource found for entry with ResourceType Graph");
		}
		Model graph = GraphUtil.deserializeGraph(new String(requestBody, StandardCharsets.UTF_8), mediaType);
		graphResource.setGraph(graph);
	}

	/**
	 * Stores an uploaded file via {@link FileResourceService#setDataMultipart} and bumps the entry's modification
	 * date.
	 */
	public CompletionState setEntryResourceMultipart(Entry entry, MultipartFile file, String mimeType) {
		fileResourceService.setDataMultipart(entry, file, mimeType);
		entry.updateModificationDate();
		return CompletionState.CREATED;
	}

	/**
	 * See {@link ListResourceService#importFromZip}.
	 */
	public void importEntryResource(Entry entry, byte[] requestBody) {
		listResourceService.importFromZip(entry, requestBody);
	}

	/**
	 * See {@link ListResourceService#moveEntry}.
	 */
	public Entry modifyListEntryResource(Entry entry, String moveEntry, String fromList, boolean removeAll) {
		return listResourceService.moveEntry(entry, moveEntry, fromList, removeAll);
	}

	/**
	 * Checks write access, then deletes the remote document of a link-type entry when {@code proxy} is
	 * {@code "true"} and otherwise routes to the service owning the entry's graph type.
	 */
	public void deleteResource(Entry entry, String proxy, boolean isRecursive) {
		principalManager.checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.WriteResource);

		EntryType entryType = entry.getEntryType();

		if ((entryType == EntryType.Link || entryType == EntryType.Reference || entryType == EntryType.LinkReference)
				&& "true".equalsIgnoreCase(proxy)) {
			proxyService.deleteUrl(entry.getResourceURI().toString());
			return;
		}
		switch (entry.getGraphType()) {
			case List -> listResourceService.deleteChildren(entry, isRecursive);
			case None -> fileResourceService.deleteData(entry);
			case null, default -> {
				// Nothing to delete for the remaining graph types.
			}
		}
	}
}
