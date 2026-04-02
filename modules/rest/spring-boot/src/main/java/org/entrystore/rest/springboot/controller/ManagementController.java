/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.model.api.SetLoggingConfigRequestBody;
import org.entrystore.rest.springboot.model.api.SolrReindexRequestBody;
import org.entrystore.rest.springboot.model.api.StatusExtendedIncludeEnum;
import org.entrystore.rest.springboot.model.api.StatusExtendedResponse;
import org.entrystore.rest.springboot.model.api.StatusResponse;
import org.entrystore.rest.springboot.service.LoggingService;
import org.entrystore.rest.springboot.service.SolrManagementService;
import org.entrystore.rest.springboot.service.StatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {

	private final LoggingService loggingService;
	private final SolrManagementService solrManagementService;
	private final StatusService statusService;

	@Operation(
			summary = "Provides a way to temporarily override parts of the logging configuration"
	)
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PutMapping(
			path = "/logging",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	public void setLoggingConfig(@RequestBody SetLoggingConfigRequestBody body) {
		loggingService.updateLoggingConfig(body);
	}


	@Operation(
			summary = "Triggers Solr reindex operations",
			description = "Accepts a JSON body with 'command' and optional 'context' fields. " +
					"Full reindex (no context) requires admin privileges. " +
					"Per-context reindex requires Administer access on the context."
	)
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping(
			path = "/solr",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	public void postSolrCommand(@Valid @RequestBody SolrReindexRequestBody body) {
		solrManagementService.reindex(body.context());
	}


	@Operation(
			summary = "Returns basic repository status",
			description = "Returns only 'UP' or 'DOWN' string.")
	@GetMapping(path = "/status", produces = MediaType.TEXT_PLAIN_VALUE)
	public String getUpStatus() {
		return statusService.isUp() ? "UP" : "DOWN";
	}


	@Operation(
			summary = "Returns repository status",
			description = "Returns status data in requested format.")
	@GetMapping(path = "/status", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
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
	@GetMapping(path = "/status/extended", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public StatusExtendedResponse getStatusExtended(
			@RequestParam(required = false, name = "include") List<StatusExtendedIncludeEnum> includeFields
	) {
		return statusService.getStatusExtended(includeFields);
	}
}
