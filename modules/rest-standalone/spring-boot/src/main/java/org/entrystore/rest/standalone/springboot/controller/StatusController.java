package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.StatusExtendedIncludeEnum;
import org.entrystore.rest.standalone.springboot.model.api.StatusExtendedResponse;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.entrystore.rest.standalone.springboot.service.StatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class StatusController {

	private final StatusService statusService;

	@Operation(
		summary = "Returns basic repository status",
		description = "Returns only 'UP' or 'DOWN' string.")
	@GetMapping(path = "/management/status", produces = MediaType.TEXT_PLAIN_VALUE)
	public String getUpStatus() {
		return statusService.isUp() ? "UP" : "DOWN";
	}

	@Operation(
		summary = "Returns repository status",
		description = "Returns status data in requested format.")
	@GetMapping(path = "/management/status", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public StatusResponse getStatusJson() {
		return statusService.getStatus();
	}

	@Operation(
		summary = "Returns extended information. Requires Admin privileges",
		description = "Returns extended status information in requested format. Requires Admin privileges.",
		parameters = {
			@Parameter(name = "include", description = "Set this parameter value to include statistical information. " +
				"Values are case-insensitive and underscores can be omitted. To set multiple values: ?include=countStats&include=relationVerboseStats")
		}
	)
	@GetMapping(path = "/management/status/extended", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public StatusExtendedResponse getStatusExtended(
		@RequestParam(required = false, name = "include") List<StatusExtendedIncludeEnum> includeFields
	) {
		return statusService.getStatusExtended(includeFields);
	}
}
