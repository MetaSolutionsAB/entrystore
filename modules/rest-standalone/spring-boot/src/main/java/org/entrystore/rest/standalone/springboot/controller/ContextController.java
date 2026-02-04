package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.CreateEntryResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.service.ContextService;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ContextController {

	private final ContextService contextService;
	private final EntryService entryService;

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
			@RequestParam(required = false) URI groupURI,
			@RequestParam(required = false, name = "cached-external-metadata") URI cachedExternalMetadataUri,
			@RequestParam(required = false, name = "informationresource") String informationResource,
			@RequestParam(required = false, name = "template") URI templateUri,
			@RequestBody(required = false) CreateEntryRequestBody body) {

		if (isGraphTypeForbidden(graphType)) {
			throw new BadRequestException("Pipeline results may only be created by Pipelines");
		}

		Entry entry = entryService.createEntry(contextId, entryId, entryType, graphType, resourceUri, listUri,
				groupURI, cachedExternalMetadataUri, informationResource, templateUri, body);


		CreateEntryResponse responseBody = new CreateEntryResponse(entry.getId());

		return ResponseEntity
				.created(entry.getEntryURI())
				.lastModified(entry.getModifiedDate().getTime())
				.eTag(HttpUtil.createStrongETag(Long.toString(entry.getModifiedDate().getTime())))
				.body(responseBody);

	}

	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Exports a context")
	@GetMapping(
			path = "/{context-id}/export"
			// we should add the 'produces' MediaType to the endpoint to clearly inform users what type of data is returned
			// however, then the behaviour differs from Restlet, maybe tweaking Spring-boot content-negotiation strategy will help
			//produces = "application/zip"
	)
	public ResponseEntity<InputStreamResource> createContextExport(
			@PathVariable("context-id") String contextId,
			@RequestParam(required = false) String metadataOnly,
			@RequestParam(required = false) String rdfFormat
	) throws FileNotFoundException {

		// for 'rdfFormat' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (rdfFormat != null) {
			rdfFormat = rdfFormat.trim().replace(' ', '+');
		}

		Context context = contextService.getContextOrThrow(contextId);
		File zipFile = contextService.exportContextToAZipFile(context, metadataOnly != null, rdfFormat);

		InputStreamResource fileStream = new InputStreamResource(new FileInputStream(zipFile));

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"context_" + contextId + "_export.zip\"")
				.contentType(MediaType.valueOf("application/zip"))
				.contentLength(zipFile.length())
				.body(fileStream);
	}

	@Operation(summary = "Import of a single context")
	@PostMapping(
			path = "/{context-id}/import",
			produces = MediaType.TEXT_HTML_VALUE)
	public String importContextFromZipFile(
			@PathVariable("context-id") String contextId,
			@RequestBody byte[] zipFile) {

		Context context = contextService.getContextOrThrow(contextId);
		contextService.importContextDataFromFile(context, new ByteArrayInputStream(zipFile));

		return "<textarea></textarea>";
	}

	@Operation(summary = "Import of a single context")
	@PostMapping(
			path = "/{context-id}/import",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.TEXT_HTML_VALUE)
	public String importContextFromMultipartForm(
			@PathVariable("context-id") String contextId,
			@RequestPart(value = "file") MultipartFile file) throws IOException {

		Context context = contextService.getContextOrThrow(contextId);
		try (InputStream inputStream = file.getInputStream()) {
			contextService.importContextDataFromFile(context, inputStream);
		}

		return "<textarea></textarea>";
	}

	/**
	 * Returns false if the Graph Type provided in the parameters
	 * cannot be used for manually created entries
	 *
	 * @return True if Graph Type is forbidden/blacklisted.
	 */
	private boolean isGraphTypeForbidden(GraphType graphType) {
		// Pipeline results may only be created by Pipelines
		return GraphType.PipelineResult == graphType;
	}

}
