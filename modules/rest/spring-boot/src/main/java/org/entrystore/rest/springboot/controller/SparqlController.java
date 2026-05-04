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
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.service.SparqlService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.SparqlMediaType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SparqlController {

	private static final int MAX_POST_REQUEST_SIZE = 32 * 1024;

	private final SparqlService sparqlService;

	@Operation(summary = "Executes a SPARQL tuple query against the public repository")
	@GetMapping("/sparql")
	public ResponseEntity<byte[]> getSparql(
			@RequestParam("query") String query,
			@RequestParam(value = "format", required = false) String format,
			@RequestHeader(value = "Accept", required = false) String acceptHeader) {

		return runQuery(query, format, acceptHeader, null);
	}

	@Operation(summary = "Executes a SPARQL tuple query restricted to a single context's named graph")
	@GetMapping("/{context-id}/sparql")
	public ResponseEntity<byte[]> getContextSparql(
			@PathVariable("context-id") String contextId,
			@RequestParam("query") String query,
			@RequestParam(value = "format", required = false) String format,
			@RequestHeader(value = "Accept", required = false) String acceptHeader) {

		return runQuery(query, format, acceptHeader, contextId);
	}

	@Operation(summary = "Executes a SPARQL tuple query (form-encoded POST) against the public repository")
	@PostMapping(path = "/sparql", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<byte[]> postSparql(
			HttpServletRequest request,
			@RequestParam("query") String query,
			@RequestParam(value = "output", required = false) String output) {

		return runFormQuery(request, query, output, null);
	}

	@Operation(summary = "Executes a SPARQL tuple query (form-encoded POST) restricted to a single context's named graph")
	@PostMapping(path = "/{context-id}/sparql", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<byte[]> postContextSparql(
			HttpServletRequest request,
			@PathVariable("context-id") String contextId,
			@RequestParam("query") String query,
			@RequestParam(value = "output", required = false) String output) {

		return runFormQuery(request, query, output, contextId);
	}

	private ResponseEntity<byte[]> runQuery(String query, String format, String acceptHeader, String contextId) {
		return buildResponse(SparqlMediaType.resolve(format, acceptHeader), query, contextId);
	}

	private ResponseEntity<byte[]> runFormQuery(HttpServletRequest request, String query, String output, String contextId) {
		// @RequestParam binds from BOTH the form body AND the URL query string; without this guard a client
		// could ship a megabyte query in the URL while keeping the body under the 32 KB cap, defeating it.
		if (StringUtils.isNotEmpty(request.getQueryString())) {
			throw new BadRequestException("POST /sparql parameters must be supplied in the form body, not the URL query string");
		}
		HttpUtil.checkRequestSize(request, MAX_POST_REQUEST_SIZE);
		return buildResponse(SparqlMediaType.fromOutputForm(output), query, contextId);
	}

	private ResponseEntity<byte[]> buildResponse(String mediaType, String query, String contextId) {
		byte[] body = sparqlService.runQuery(mediaType, query, contextId);
		return ResponseEntity.ok().contentType(SparqlMediaType.toMediaType(mediaType)).body(body);
	}
}
