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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.service.ValidatorService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ValidatorController {

	private static final int MAX_REQUEST_SIZE = 10_485_760; // 10 MB

	private final ValidatorService validatorService;

	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	@Operation(
			summary = "Validates an RDF graph payload: parses it, checks IRI validity, and verifies triplestore round-trip.",
			description = "IRI validity is checked for all subject/predicate/object/context positions and for literal values that begin with http:// or https://."
	)
	@PostMapping(path = "/validator")
	public void validate(
			@RequestParam(required = false) MediaType format,
			@RequestHeader(value = "Content-Type", required = false) String contentType,
			@RequestBody String body,
			HttpServletRequest request
	) {
		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);

		String mediaType = GraphUtil.validateRdfMediaType(
				HttpUtil.determineMediaType(format, contentType),
				HttpStatus.BAD_REQUEST
		);

		validatorService.validate(body, mediaType);
	}

}
