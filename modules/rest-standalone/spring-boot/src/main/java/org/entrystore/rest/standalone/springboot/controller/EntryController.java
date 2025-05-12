package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
		return entryService.getEntryInJsonFormat(contextId, entryId, rdfFormat, includeAll != null, listFilter);
	}

}
