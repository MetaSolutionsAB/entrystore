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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.rest.springboot.model.api.GetEntryNameResponse;
import org.entrystore.rest.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.SetEntryNameRequestBody;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.service.EntryService;
import org.entrystore.rest.springboot.util.EntryMediaTypeResolver;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.entrystore.rest.springboot.util.HttpUtil.determineMediaType;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EntryController {

	private static final String REDUCED_INFO_DESCRIPTION = "Callers who may read neither the entry's metadata nor " +
			"its resource get only the types, the URIs of the entry's parts, the dates and the home context.";

	private final EntryService entryService;
	private final ObjectMapper objectMapper;

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used. " + REDUCED_INFO_DESCRIPTION)
	@ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GetEntryResponse.class)))
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> getEntryInJsonFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType rdfFormat,
			@RequestParam(required = false) String includeAll,
			@ModelAttribute ListFilter listFilter,
			@Parameter(hidden = true) HttpServletResponse response
	) {
		String mediaType = rdfFormat != null ? GraphUtil.validateRdfMediaType(rdfFormat.toString()) : null;
		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		boolean mayReadNothing = entryService.mayReadNothing(entry);
		GetEntryResponse body = entryService.getEntryInJsonFormat(entry, mediaType, includeAll != null, listFilter,
				mayReadNothing);
		// Serialized here rather than by the message converter, so that the ETag can be computed from the bytes sent
		byte[] json = objectMapper.writeValueAsBytes(body);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.headers(headers -> {
					if (jsonEmbedsOtherData(entry, includeAll != null)) {
						HttpUtil.setContentRevalidationHeaders(headers, response, entry.getModifiedDate(), json, true);
					} else {
						setDateRevalidationHeaders(headers, response, entry, mayReadNothing);
					}
				})
				.body(json);
	}

	/**
	 * Sets the revalidation headers with the ETag from the entry's modification date, which differs for a caller who
	 * gets the reduced entry information, see {@link HttpUtil#setReducedRevalidationHeaders}.
	 */
	private static void setDateRevalidationHeaders(HttpHeaders headers, HttpServletResponse response, Entry entry,
												   boolean mayReadNothing) {
		if (mayReadNothing) {
			HttpUtil.setReducedRevalidationHeaders(headers, response, entry.getModifiedDate(), true);
		} else {
			HttpUtil.setRevalidationHeaders(headers, response, entry.getModifiedDate(), true);
		}
	}

	/**
	 * Whether the entry JSON carries data that can change while the entry's modification date does not: with
	 * {@code includeAll} the caller's rights, the relations and the members of a list, group or user, and for a
	 * context its quota fill level. Its ETag is then computed from the JSON instead of that date.
	 */
	private static boolean jsonEmbedsOtherData(Entry entry, boolean includeAll) {
		GraphType graphType = entry.getGraphType();
		return includeAll || graphType == GraphType.Context || graphType == GraphType.SystemContext;
	}

	@Operation(
			summary = "Returns the entry information.",
			description = "Returns an RDF graph unless application/json is requested in which case the JSON-structure " +
					"as specified in the response body is used. The 'format' parameter takes precedence over the " +
					"Accept header; without either, or for a wildcard Accept header, the graph is RDF/XML. " +
					REDUCED_INFO_DESCRIPTION)
	@GetMapping(path = "/{context-id}/entry/{entry-id}", produces = {"application/rdf+xml", "text/n3", "text/rdf+n3",
			"text/turtle", "application/trix", "application/n-triples", "application/trig", "application/ld+json",
			"application/rdf+json"})
	public ResponseEntity<String> getEntryInRdfFormat(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@Parameter(hidden = true) HttpServletRequest request,
			@Parameter(hidden = true) HttpServletResponse response
	) throws HttpMediaTypeNotAcceptableException {
		// Return ResponseEntity instead of String to control the response Content-Type. Spring MVC would otherwise
		// echo back the client's Accept type (text/rdf+n3) as the response Content-Type, but we respond with
		// the normalized form (text/n3) since text/rdf+n3 is a non-standard legacy N3 MIME type.
		String mediaType = GraphUtil.validateRdfMediaType(EntryMediaTypeResolver.resolve(request).toString());
		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		boolean mayReadNothing = entryService.mayReadNothing(entry);
		String body = entryService.getEntryInRdfFormat(entry, mediaType, mayReadNothing);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(mediaType))
				.headers(headers -> setDateRevalidationHeaders(headers, response, entry, mayReadNothing))
				.body(body);
	}

	@Operation(
			summary = "Returns the headers of the entry information.",
			description = "Answers with the headers a GET would send, including Last-Modified, without serializing " +
					"the entry. The ETag is omitted where the GET computes it from the JSON: with includeAll, and " +
					"for contexts.")
	@RequestMapping(path = "/{context-id}/entry/{entry-id}", method = RequestMethod.HEAD)
	public ResponseEntity<Void> headEntry(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@Parameter(hidden = true) HttpServletRequest request,
			@Parameter(hidden = true) HttpServletResponse response
	) throws HttpMediaTypeNotAcceptableException {
		// The GET is served by the JSON handler for any application/json, parameters included, else by the RDF one.
		MediaType negotiated = EntryMediaTypeResolver.resolve(request);
		boolean json = MediaType.APPLICATION_JSON.equalsTypeAndSubtype(negotiated);
		MediaType contentType = json
				? MediaType.APPLICATION_JSON
				: MediaType.parseMediaType(GraphUtil.validateRdfMediaType(negotiated.toString()));
		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		return ResponseEntity.ok()
				.contentType(contentType)
				.headers(headers -> {
					if (json && jsonEmbedsOtherData(entry, request.getParameter("includeAll") != null)) {
						HttpUtil.setRevalidationHeaders(headers, response, entry.getModifiedDate(), true);
						// the GET's ETag is computed from the JSON, which HEAD does not build
						headers.remove(HttpHeaders.ETAG);
					} else {
						setDateRevalidationHeaders(headers, response, entry, entryService.mayReadNothing(entry));
					}
				})
				.build();
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

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		Entry modifiedEntry = entryService.modifyEntry(entry, body, mediaType, applyACLtoChildren != null);

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

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		entryService.deleteEntry(entry, recursive != null);

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
