package org.entrystore.rest.standalone.springboot.controller;

import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.entrystore.rest.standalone.springboot.service.StatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StatusController {

	private final StatusService statusService;

	@GetMapping(path = "/management/status", produces = MediaType.APPLICATION_JSON_VALUE)
	public StatusResponse getStatusAsJson() {
		return statusService.getStatus();
	}
}
