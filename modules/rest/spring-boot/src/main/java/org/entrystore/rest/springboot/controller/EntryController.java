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
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.rest.springboot.model.api.GetEntryNameResponse;
import org.entrystore.rest.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.SetEntryNameRequestBody;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.service.EntryService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.entrystore.rest.springboot.util.HttpUtil.determineMediaType;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EntryController {

	private final EntryService entryService;

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used.")
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetEntryResponse getEntryInJsonFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType rdfFormat,
			@RequestParam(required = false) String includeAll,
			@ModelAttribute ListFilter listFilter
	) {
		String mediaType = rdfFormat != null ? GraphUtil.validateRdfMediaType(rdfFormat.toString()) : null;
		return entryService.getEntryInJsonFormat(contextId, entryId, mediaType, includeAll != null, listFilter);
	}

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used.")
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = {"application/rdf+xml", "text/n3", "text/rdf+n3",
			"text/turtle", "application/trix", "application/n-triples", "application/trig", "application/ld+json",
			"application/rdf+json"})
	public ResponseEntity<String> getEntryInRdfFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestHeader(value = "Accept", required = false, defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader
	) {
		// Return ResponseEntity instead of String to control the response Content-Type. Spring MVC would otherwise
		// echo back the client's Accept type (text/rdf+n3) as the response Content-Type, but we respond with
		// the normalized form (text/n3) since text/rdf+n3 is a non-standard legacy N3 MIME type.
		String mediaType = GraphUtil.resolveAcceptedMediaType(acceptHeader, GraphUtil.DEFAULT_RDF_MEDIA_TYPE);
		String body = entryService.getEntryInRdfFormat(contextId, entryId, mediaType);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(mediaType))
				.body(body);
	}

	@Operation(
			summary = "Sets the entry information.",
			description = "Overrides entry data with data in the request body.")
	@PutMapping(path = "/{context-id}/entry/{entry-id}")
	public ResponseEntity<Void> modifyEntry(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType format,
			@RequestParam(required = false) String applyACLtoChildren,
			@RequestHeader("Content-Type") String contentType,
			@RequestBody String body
	) {

		String mediaType = GraphUtil.validateRdfMediaType(
				determineMediaType(format, contentType), HttpStatus.UNSUPPORTED_MEDIA_TYPE);

		Entry modifiedEntry = entryService.modifyEntry(contextId, entryId, body, mediaType, applyACLtoChildren != null);

		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.noContent(),
						modifiedEntry.getModifiedDate())
				.build();
	}

	@Operation(
			summary = "Deletes the entry.",
			description = "Deletes given entry. If parameter 'recursive' is set then also deletes all its children.")
	@DeleteMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> deleteEntry(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String recursive
	) {

		entryService.deleteEntry(contextId, entryId, recursive != null);

		return ResponseEntity
				.noContent()
				.build();
	}

	@Operation(summary = "Returns the entry's name (alias).")
	@GetMapping(path = "/{context-id}/entry/{entry-id}/name", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetEntryNameResponse getEntryName(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		String name = entryService.getEntryName(entry);

		if (name == null) {
			throw new EntityNotFoundException("Entry with id '" + entry.getId() + "' has no name set");
		}

		return new GetEntryNameResponse(name);
	}

	@Operation(summary = "Sets the entry's name (alias).")
	@PutMapping(path = "/{context-id}/entry/{entry-id}/name", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> setEntryName(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestBody SetEntryNameRequestBody body
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		boolean success = entryService.setEntryName(entry, body.name());

		if (!success) {
			throw new DataConflictException("Unable to set new name for Entry with id '" + entry.getId() + "'");
		}

		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.noContent(),
						entry.getModifiedDate())
				.build();
	}

	@Operation(summary = "Returns entry's index")
	@GetMapping(path = "/{context-id}/entry/{entry-id}/index", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> getEntryIndex(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		entryService.checkEntryUserAccess(entry, AccessProperty.Administer);

		return entryService.getEntryIndex(entry);
	}
}
