package org.entrystore.rest.standalone.springboot.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.springboot.model.api.EntryResponse;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/entries")
@RequiredArgsConstructor
public class EntryController {

	private final EntryService entryService;

	@GetMapping(path = "/{id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public EntryResponse getEntry(@PathVariable("id") int entryId) {
		return entryService.getEntry(entryId);
	}

	@PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public EntryResponse createEntry(@Valid @RequestBody EntryCreateRequest entryRequest) {
		return entryService.createEntry(entryRequest);
	}
}
