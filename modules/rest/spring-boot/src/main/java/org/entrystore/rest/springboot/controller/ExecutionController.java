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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ExecutePipelineRequestBody;
import org.entrystore.rest.springboot.model.api.ExecutePipelineResponse;
import org.entrystore.rest.springboot.service.ExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ExecutionController {

	private final ExecutionService executionService;

	@Operation(summary = "Executes a pipeline on a source entry within the given context")
	@PostMapping(
			path = "/{context-id}/execute",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ExecutePipelineResponse> execute(
			@PathVariable("context-id") String contextId,
			@Valid @RequestBody ExecutePipelineRequestBody body) {

		ExecutePipelineResponse response = executionService.execute(contextId, body.pipelineUri(), body.sourceUri());
		HttpStatus status = response.result().isEmpty() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(response);
	}
}
