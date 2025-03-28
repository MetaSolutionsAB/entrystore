package org.entrystore.rest.standalone.springboot.controller;

import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.StatusExtendedResponse;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.entrystore.rest.standalone.springboot.service.StatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StatusController {

	private final StatusService statusService;

	@GetMapping(path = "/management/status", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public StatusResponse getStatus() {
		return statusService.getStatus();
	}

	@GetMapping(path = "/management/status/extended", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public StatusExtendedResponse getStatusExtended() {
		return statusService.getStatusExtended();
	}
}
