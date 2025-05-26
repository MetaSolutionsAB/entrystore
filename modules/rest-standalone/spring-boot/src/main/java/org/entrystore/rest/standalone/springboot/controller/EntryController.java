package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
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

	private final EntryService entryService;

	@Operation(
		summary = "Returns the entry information.",
		description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
			"as specified in the response body is used.")
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetEntryResponse getEntry(
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

		ResponseEntity.HeadersBuilder<?> responseBuilder = ResponseEntity.noContent();
		if (modifiedEntry.getModifiedDate() == null) {
			log.warn("Last-Modified header could not be set because the entry does not have a modification date: {}", modifiedEntry.getEntryURI());
		} else {
			responseBuilder
				.lastModified(modifiedEntry.getModifiedDate().getTime())
				.eTag(HttpUtil.createStrongETag(Long.toString(modifiedEntry.getModifiedDate().getTime())));
		}

		return responseBuilder.build();
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
}
