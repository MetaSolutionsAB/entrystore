package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.service.ContextService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ContextController {

	private final ContextService contextService;

	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Returns an array of IDs of a context's entries")
	@GetMapping(path = "/{context-id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public List<String> getContextEntries(
		@PathVariable("context-id") String contextId,
		@RequestParam(required = false, name = "entryname") String entryName,
		@RequestParam(required = false, name = "deleted") String deletedEntries
	) {
		return contextService.getContextEntries(contextId, deletedEntries != null, entryName);
	}

	@Operation(summary = "Creates a new entry inside the given context")
	@PostMapping(path = "/{context-id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public ResponseEntity<CreateEntryResponse> createEntry(
		@PathVariable("context-id") String contextId,
		@RequestParam(required = false, name = "id") String entryId,
		@RequestParam(required = false, name = "entrytype") EntryType entryType,
		@RequestParam(required = false, name = "graphtype", defaultValue = "none") GraphType graphType,
		@RequestParam(required = false, name = "resource") URI resourceUri,
		@RequestParam(required = false, name = "list") URI listUri,
		@RequestParam(required = false) URI groupUri,
		@RequestParam(required = false, name = "cached-external-metadata") URI cachedExternalMetadataUri,
		@RequestParam(required = false, name = "informationresource") String informationResource,
		@RequestParam(required = false, name = "template") URI templateUri,
		@RequestBody(required = false) CreateEntryRequestBody body) {

		if (graphType == GraphType.PipelineResult) {
			throw new BadRequestException("Pipeline results may only be created by Pipelines");
		}

		Entry entry = contextService.createEntry(contextId, entryId, entryType, graphType, resourceUri, listUri,
			groupUri, cachedExternalMetadataUri, informationResource, templateUri, body);


		CreateEntryResponse responseBody = new CreateEntryResponse(entry.getId());

		return ResponseEntity
			.created(entry.getEntryURI())
			.lastModified(entry.getModifiedDate().getTime())
			.eTag(HttpUtil.createStrongETag(Long.toString(entry.getModifiedDate().getTime())))
			.body(responseBody);

	}

}
