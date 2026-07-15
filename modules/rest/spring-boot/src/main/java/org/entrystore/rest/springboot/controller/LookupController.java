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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.springboot.model.api.LookupScope;
import org.entrystore.rest.springboot.service.LookupService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Slf4j
@RestController
@RequiredArgsConstructor
public class LookupController {

	private final LookupService lookupService;

	@Operation(summary = "Performs a global lookup by resource URI and returns metadata")
	@GetMapping(
			path = "/lookup",
			produces = {
					"application/rdf+xml", MediaType.APPLICATION_JSON_VALUE,
					"text/n3", "text/turtle", "application/trix",
					"application/n-triples", "application/trig",
					"application/ld+json", "application/rdf+json"
			}
	)
	public ResponseEntity<String> globalLookup(
			@RequestParam URI uri,
			@RequestParam(required = false, defaultValue = "all") LookupScope scope,
			@RequestParam(required = false) MediaType format,
			@RequestHeader(value = "Accept", defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader
	) {
		String mediaType = GraphUtil.resolveRdfMediaType(format, acceptHeader);
		Entry entry = lookupService.lookupGlobal(uri);
		return buildResponse(entry, scope, mediaType);
	}

	@Operation(summary = "Performs a context-scoped lookup by resource URI and returns metadata")
	@GetMapping(
			path = "/{context-id}/lookup",
			produces = {
					"application/rdf+xml", MediaType.APPLICATION_JSON_VALUE,
					"text/n3", "text/turtle", "application/trix",
					"application/n-triples", "application/trig",
					"application/ld+json", "application/rdf+json"
			}
	)
	public ResponseEntity<String> contextLookup(
			@PathVariable("context-id") String contextId,
			@RequestParam URI uri,
			@RequestParam(required = false, defaultValue = "all") LookupScope scope,
			@RequestParam(required = false) MediaType format,
			@RequestHeader(value = "Accept", defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader
	) {
		String mediaType = GraphUtil.resolveRdfMediaType(format, acceptHeader);
		Entry entry = lookupService.lookupInContext(contextId, uri);
		return buildResponse(entry, scope, mediaType);
	}

	private ResponseEntity<String> buildResponse(Entry entry, LookupScope scope, String mediaType) {
		String serializedMetadata = lookupService.getMetadataByScope(entry, scope, mediaType);

		ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity
				.ok()
				.contentType(MediaType.parseMediaType(mediaType));

		HttpUtil.updateResponseWithModificationDateAndETag(bodyBuilder, entry.getModifiedDate());

		return bodyBuilder.body(serializedMetadata);
	}
}
