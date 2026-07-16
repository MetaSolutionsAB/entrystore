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
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.Entry;
import org.entrystore.rest.springboot.model.api.MetadataType;
import org.entrystore.rest.springboot.model.dto.MetadataResult;
import org.entrystore.rest.springboot.service.EntryService;
import org.entrystore.rest.springboot.service.MetadataService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.entrystore.rest.springboot.util.HttpUtil.determineMediaType;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MetadataController {

	private static final RDFFormat RDFJSON_WITH_APPLICATION_JSON
			= new RDFFormat("RDF/JSON", List.of("application/json"), StandardCharsets.UTF_8, List.of("json"),
			SimpleValueFactory.getInstance().createIRI("http://www.w3.org/ns/formats/RDF_JSON"), false, true, false);

	private final MetadataService metadataService;
	private final EntryService entryService;

	@Operation(
			summary = "Returns an entry's metadata graph.",
			description = "desc")
	@GetMapping(path = "/{context-id}/{type:metadata|cached-external-metadata|merged-metadata}/{entry-id}")
	public ResponseEntity<String> getMetadata(
			@PathVariable("context-id") String contextId,
			@PathVariable("type") MetadataType metadataType,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType format,
			@RequestParam(required = false) String graphQuery,
			@RequestParam(required = false, defaultValue = "10") Integer depth,
			@RequestParam(required = false) String recursive,
			@RequestParam(required = false) String scope,
			@RequestParam(name = "rev", required = false) String revision,
			@RequestParam(required = false) String download,
			@RequestHeader(value = "Accept", required = false, defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader,
			WebRequest webRequest
	) {
		String mediaType = GraphUtil.resolveRdfMediaType(format, acceptHeader);

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);

		// ENTRYSTORE-1087: everything that changes the response body for the same metadata state
		// must be part of the ETag, otherwise If-None-Match would confirm a representation the
		// client does not hold.
		String representationKey = String.join("|", metadataType.name(), mediaType,
				String.valueOf(graphQuery), String.valueOf(depth), String.valueOf(recursive),
				String.valueOf(scope), String.valueOf(revision));
		// JSONP responses are wrapped by a filter (callback in the body, not in this key) and are
		// contractually non-cacheable; recursive representations aggregate other entries whose
		// modification dates are not monotonic for this ETag; revisions are addressed explicitly.
		// These shapes carry no conditional headers at all: Spring's HttpEntityMethodProcessor
		// auto-answers 304 for any ResponseEntity with an ETag, so skipping checkNotModified
		// alone would not prevent a stale 304.
		boolean conditionalApplicable = recursive == null && revision == null
				&& webRequest.getParameter("callback") == null;

		if (conditionalApplicable && metadataType == MetadataType.LOCAL_METADATA) {
			// Fast path for the polling-heavy local-metadata GET: authorize with exactly the
			// property the load path checks, then answer the revalidation before loading and
			// serializing the graph.
			metadataService.checkReadMetadataAuthorization(entry);
			Date modificationDate = getModificationDate(entry, metadataType);
			if (modificationDate != null && webRequest.checkNotModified(
					HttpUtil.createRepresentationETag(modificationDate, representationKey),
					modificationDate.getTime())) {
				return null;
			}
		}

		MetadataResult metadataResult = metadataService.getMetadata(entry, metadataType, mediaType, graphQuery, depth, recursive, scope, revision);

		Date modificationDate = metadataResult.lastModified() != null
				? metadataResult.lastModified()
				: getModificationDate(entry, metadataType);

		if (conditionalApplicable && metadataType != MetadataType.LOCAL_METADATA
				&& modificationDate != null && webRequest.checkNotModified(
						HttpUtil.createRepresentationETag(modificationDate, representationKey),
						modificationDate.getTime())) {
			// Post-load revalidation for the non-local types: their authorization involves the
			// referenced entry (LocalMetadataWrapper), so the load must happen — only the
			// serialization transfer is saved.
			return null;
		}

		HttpHeaders headers = buildResponseHeaders(entry, mediaType, download != null);
		if (conditionalApplicable) {
			HttpUtil.setLastModifiedAndETag(headers, modificationDate, representationKey);
		}
		headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT);
		return new ResponseEntity<>(metadataResult.serializedGraph(), headers, HttpStatus.OK);
	}

	@Operation(
			summary = "Sets an entry's metadata graph.",
			description = "desc")
	@PutMapping(path = "/{context-id}/{type:metadata|cached-external-metadata|merged-metadata}/{entry-id}")
	public ResponseEntity<Void> setMetadata(
			@PathVariable("context-id") String contextId,
			@PathVariable("type") MetadataType metadataType,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType format,
			@RequestParam(name = "rev", required = false) String revision,
			@RequestHeader("Content-Type") String contentType,
			@RequestBody String body
	) {

		String mediaType = GraphUtil.validateRdfMediaType(
				determineMediaType(format, contentType), HttpStatus.UNSUPPORTED_MEDIA_TYPE);

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		Model deserializedGraph = GraphUtil.deserializeGraph(body, mediaType);
		metadataService.setEntryMetadata(entry, metadataType, deserializedGraph, revision);

		Date modificationDate = getModificationDate(entry, metadataType);
		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.noContent(),
						modificationDate)
				.build();
	}

	@Operation(
			summary = "Deletes an entry's metadata graph.",
			description = "desc")
	@DeleteMapping(path = "/{context-id}/{type:metadata|cached-external-metadata|merged-metadata}/{entry-id}")
	public ResponseEntity<Void> deleteMetadata(
			@PathVariable("context-id") String contextId,
			@PathVariable("type") MetadataType metadataType,
			@PathVariable("entry-id") String entryId,
			@RequestParam(name = "rev", required = false) String revision) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		metadataService.setEntryMetadata(entry, metadataType, new LinkedHashModel(), revision);

		return ResponseEntity.noContent().build();
	}

	private static HttpHeaders buildResponseHeaders(Entry entry, String mediaType, boolean isDownload) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(mediaType));

		// set file name
		String fileName = entry.getFilename();
		if (fileName == null) {
			fileName = entry.getId();
		}
		fileName += "." + getFileExtensionForMediaType(mediaType);

		ContentDisposition contentDisposition;
		if (isDownload) {
			contentDisposition = ContentDisposition.attachment().filename(fileName).build();
		} else {
			contentDisposition = ContentDisposition.inline().filename(fileName).build();
		}
		headers.setContentDisposition(contentDisposition);

		return headers;
	}

	private static String getFileExtensionForMediaType(String mt) {
		Optional<RDFFormat> rdfFormat = RDFFormat.matchMIMEType(mt, Arrays.asList(
				RDFFormat.RDFXML,
				RDFFormat.NTRIPLES,
				RDFFormat.TURTLE,
				RDFFormat.N3,
				RDFFormat.TRIX,
				RDFFormat.TRIG,
				RDFFormat.BINARY,
				RDFFormat.NQUADS,
				RDFFormat.JSONLD,
				RDFFormat.RDFJSON,
				RDFFormat.RDFA,
				RDFJSON_WITH_APPLICATION_JSON)
		);
		if (rdfFormat.isPresent() && rdfFormat.get().getDefaultFileExtension() != null) {
			return rdfFormat.get().getDefaultFileExtension();
		}
		return "rdf";
	}

	private static Date getModificationDate(Entry entry, MetadataType metadataType) {
		return switch (metadataType) {
			case LOCAL_METADATA -> entry.getModifiedDate();
			case CACHED_EXTERNAL_METADATA -> entry.getExternalMetadataCacheDate();
			case MERGED_METADATA -> latest(entry.getExternalMetadataCacheDate(), entry.getModifiedDate());
		};
	}

	private static Date latest(Date... dates) {
		return Arrays.stream(dates)
				.filter(Objects::nonNull)
				.max(Date::compareTo)
				.orElse(null);
	}
}
