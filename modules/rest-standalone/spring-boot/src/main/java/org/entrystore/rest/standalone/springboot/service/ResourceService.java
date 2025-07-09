package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.RDFJSON;
import org.entrystore.rest.standalone.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.NotAcceptableStatusException;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	private static final String EMPTY_REPRESENTATION = "";

	private static Boolean rewriteMediaTypeJavaScript;

	private final RepositoryManagerImpl repositoryManager;
	private final ResourceJsonSerializer resourceSerializer;

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
