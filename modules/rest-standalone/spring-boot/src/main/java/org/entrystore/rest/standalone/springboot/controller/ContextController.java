package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.service.ContextService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContextController {

	private final ContextService contextService;

	@Operation(summary = "Returns an array of IDs of a context's entries")
	@GetMapping(path = "/{context-id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public List<String> getContextEntries(@PathVariable("context-id") String contextId) {
		return contextService.getContextEntries(contextId);
	}

}
