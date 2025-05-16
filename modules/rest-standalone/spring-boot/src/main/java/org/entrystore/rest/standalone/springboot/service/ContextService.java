package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.EntryNamesContext;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextService {

	private final RepositoryManagerImpl repositoryManager;
	private final EntryService entryService;
	private final ReservedNamesService reservedNames;


	public List<String> getContextEntries(String contextId, boolean deletedEntries, String entryName) {

		Context context = getContext(contextId);
		if (context == null) {
			throw new BadRequestException("No context with id '" + contextId + "' found");
		}

		if (deletedEntries) {

			return context.getDeletedEntries().keySet()
				.stream()
				.map(URI::toString)
				.map(uri -> uri.substring(uri.lastIndexOf("/") + 1))
				.collect(Collectors.toList());

		} else if (context instanceof EntryNamesContext && entryName != null) {

			Entry matchedEntry = ((EntryNamesContext) context).getEntryByName(entryName);
			if (matchedEntry != null) {
				return List.of(matchedEntry.getId());
			} else {
				throw new EntityNotFoundException("Entity with name '" + entryName + "' not found in context '" + contextId + "'");
			}
		}

		return context.getEntries()
			.stream()
			.map(uri -> {
				Entry entry = context.getByEntryURI(uri);
				if (entry == null) {
					log.warn("No entry found for this referenced URI: {}", uri);
					return null;
				}
				return entry.getId();
			})
			.filter(Objects::nonNull)
			.toList();
	}

	public Context getContext(String contextId) {

		ContextManager cm = repositoryManager.getContextManager();

		if (cm != null && contextId != null) {
			// TODO: Why do we verify contextId against reservedNames on each context fetch? Should be done on Context creation only
			if (reservedNames.contains(contextId.toLowerCase())) {
				log.error("Context ID is a reserved term and must not be used: \"{}\". This error is likely to be caused by an error in the REST routing.", contextId);
			} else {
				return cm.getContext(contextId);
			}
		}
		return null;
	}

	public Entry createEntry(String contextId, String entryId, EntryType entryType, GraphType graphType,
							 URI resourceUri, URI listUri, URI groupUri, URI cachedExternalMetadataUri,
							 String informationResource, URI templateUri, String bodyResource) {

		Context context = getContext(contextId);
		if (context == null) {
			throw new BadRequestException("No context with id '" + contextId + "' found");
		}

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
				entry = entryService.createLocalEntry(context, entryId, graphType, listUri, groupUri, bodyResource);
			} else {
				// Link
				if (entryType == EntryType.Link && resourceUri != null) {
					entry = entryService.createLinkEntry(context, entryId, graphType, resourceUri, listUri, bodyResource);
				}
				// Reference
				else if (entryType == EntryType.Reference
					&& resourceUri != null
					&& cachedExternalMetadataUri != null) {

					entry = entryService.createReferenceEntry(context, entryId, graphType, resourceUri, listUri, cachedExternalMetadataUri, bodyResource);
				}
				// LinkReference
				else if (entryType == EntryType.LinkReference
					&& resourceUri != null
					&& cachedExternalMetadataUri != null) {

					entry = entryService.createLinkReferenceEntry(context, entryId, graphType, resourceUri, listUri, cachedExternalMetadataUri, bodyResource);
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

}
