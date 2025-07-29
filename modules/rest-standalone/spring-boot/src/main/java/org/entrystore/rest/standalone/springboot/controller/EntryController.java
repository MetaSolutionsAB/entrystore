package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryNameResponse;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.model.api.SetEntryNameRequestBody;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EntryController {

	private static final String DEFAULT_MEDIA_TYPE = "application/rdf+xml";

	private final EntryService entryService;

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used.")
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetEntryResponse getEntryInJsonFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String rdfFormat,
			@RequestParam(required = false) String includeAll,
			@ModelAttribute ListFilter listFilter
	) {
		// for rdfFormat param, data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (rdfFormat != null) {
			rdfFormat = rdfFormat.trim().replace(' ', '+');
		}
		return entryService.getEntryInJsonFormat(contextId, entryId, rdfFormat, includeAll != null, listFilter);
	}

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used.")
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = {"application/rdf+xml", "text/n3", "text/turtle",
			"application/trix", "application/n-triples", "application/trig", "application/ld+json", "application/rdf+json"})
	public String getEntryInRdfFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestHeader(value = "Accept", required = false, defaultValue = DEFAULT_MEDIA_TYPE) String acceptHeader
	) {

		if (StringUtils.isEmpty(acceptHeader) || MediaType.ALL_VALUE.equals(acceptHeader)) {
			acceptHeader = DEFAULT_MEDIA_TYPE;
		}
		return entryService.getEntryInRdfFormat(contextId, entryId, acceptHeader);
	}

	@Operation(
			summary = "Sets the entry information.",
			description = "Overrides entry data with data in the request body.")
	@PutMapping(path = "/{context-id}/entry/{entry-id}")
	public ResponseEntity<Void> modifyEntry(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String format,
			@RequestParam(required = false) String applyACLtoChildren,
			@RequestHeader("Content-Type") String contentType,
			@RequestBody String body
	) {

		String mediaType;
		// for 'format' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (format != null) {
			mediaType = format.trim().replace(' ', '+');
		} else {
			mediaType = contentType;
		}

		Entry modifiedEntry = entryService.modifyEntry(contextId, entryId, body, mediaType, applyACLtoChildren != null);

		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.noContent(),
						modifiedEntry.getModifiedDate())
				.build();
	}

	@Operation(
			summary = "Deletes the entry.",
			description = "Deletes given entry. If parameter 'recursive' is set then also deletes all its children.")
	@DeleteMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> deleteEntry(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String recursive
	) {

		entryService.deleteEntry(contextId, entryId, recursive != null);

		return ResponseEntity
				.noContent()
				.build();
	}

	@Operation(summary = "Returns the entry's name (alias).")
	@GetMapping(path = "/{context-id}/entry/{entry-id}/name", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetEntryNameResponse getEntryName(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		String name = entryService.getEntryName(entry);

		if (name == null) {
			throw new EntityNotFoundException("Entry with id '" + entry.getId() + "' has no name set");
		}

		return new GetEntryNameResponse(name);
	}

	@Operation(summary = "Sets the entry's name (alias).")
	@PutMapping(path = "/{context-id}/entry/{entry-id}/name", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> setEntryName(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestBody SetEntryNameRequestBody body
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		boolean success = entryService.setEntryName(entry, body.name());

		if (!success) {
			throw new DataConflictException("Unable to set new name for Entry with id '" + entry.getId() + "'");
		}

		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.noContent(),
						entry.getModifiedDate())
				.build();
	}
}
