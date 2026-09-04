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
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.entrystore.Entry;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.ModifyListResourceResponse;
import org.entrystore.rest.springboot.model.api.ResourceQuery;
import org.entrystore.rest.springboot.model.dto.CompletionState;
import org.entrystore.rest.springboot.model.dto.ResourceRepresentation;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.service.EntryService;
import org.entrystore.rest.springboot.service.ResourceService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.MultipartUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

import static org.entrystore.rest.springboot.util.HttpUtil.normalizeMediaType;

@RestController
@RequiredArgsConstructor
public class ResourceController {

	private final EntryService entryService;
	private final ResourceService resourceService;

	@Value("${entrystore.http.allow-content-disposition-inline:true}")
	private boolean allowContentDispositionInline;

	@Operation(
			summary = "Returns a resource.",
			description = "Depending on the entry’s character the resource may be binary, JSON or RDF. See Knowledge Base for details.")
	@GetMapping(path = "/{context-id}/resource/{entry-id}")
	public ResponseEntity<Object> getResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) MediaType rdfFormat,
			@RequestParam(required = false) String syndication,
			@RequestParam(required = false, defaultValue = "50") int feedSize,
			@RequestParam(name = "lang", required = false, defaultValue = "en") String language,
			@RequestParam(required = false) String download,
			@ModelAttribute ListFilter listFilter,
			@RequestHeader(value = "Accept", required = false, defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader
	) {
		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		ResourceQuery query = new ResourceQuery(rdfFormat, acceptHeader, syndication, language, feedSize, listFilter);

		return switch (resourceService.getResourceRepresentation(entry, query)) {
			case ResourceRepresentation.Empty _ -> ResponseEntity
					.noContent()
					.build();
			case ResourceRepresentation.FileDownload file -> ResponseEntity
					.ok()
					.headers(buildFileDownloadResponseHeaders(file, download != null))
					.contentLength(file.file().length())
					.body(new FileSystemResource(file.file()));
			case ResourceRepresentation.TextBody text -> ResponseEntity
					.ok()
					.contentType(text.mediaType())
					.body(text.body());
		};
	}

	@Operation(
			summary = "Sets a resource.",
			description = "Resource should be sent in the request body. Depending on the entry’s character the resource may be binary, " +
					"JSON or RDF. See Knowledge Base for details.")
	@PutMapping(
			path = "/{context-id}/resource/{entry-id}",
			consumes = "!multipart/form-data")
	public ResponseEntity<Void> setResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String mimeType,
			@RequestParam(required = false) String textarea,
			@RequestHeader(value = "Content-Type", required = false) String contentType,
			@RequestHeader(value = HttpHeaders.CONTENT_DISPOSITION, required = false) String contentDisposition,
			@RequestBody(required = false) byte[] body,
			HttpSession session
	) {

		String mediaType = normalizeMediaType(contentType);

		String filename = null;
		if (contentDisposition != null) {
			ContentDisposition disposition = ContentDisposition.parse(contentDisposition);
			filename = disposition.getFilename();
		}

		if (body == null) {
			body = new byte[0];
		}

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		CompletionState result = resourceService.setEntryResource(entry, body, mediaType, mimeType, filename,
				session.getId());

		return buildSetResourceResponse(entry, result);
	}

	@Operation(
			summary = "Sets a resource.",
			description = "Resource should be sent as a file part of a multipart/form-data request. It may carry any " +
					"part name: a part named \"file\" is used when it carries a filename, otherwise the first part " +
					"carrying a filename, otherwise the first part carrying content. Depending on the entry’s character " +
					"the resource may be binary, JSON or RDF. See Knowledge Base for details.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = @Content(
					mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
					schema = @Schema(type = "object"),
					schemaProperties = {
							@SchemaProperty(
									name = "file",
									schema = @Schema(type = "string", format = "binary")),
							@SchemaProperty(
									name = "mimeType",
									schema = @Schema(type = "string"))}))
	@PutMapping(
			path = "/{context-id}/resource/{entry-id}",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<Void> setResourceMultipart(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String mimeType,
			@Parameter(hidden = true) MultipartRequest request
	) {

		MultipartFile file = MultipartUtil.firstFilePart(request)
				.orElseThrow(() -> new BadRequestException("Multipart request contains no file part"));

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		CompletionState result = resourceService.setEntryResourceMultipart(entry, file, mimeType);

		return buildSetResourceResponse(entry, result);
	}

	@Operation(
			summary = "Modifies a list resource.",
			// The description from current swagger state seems outdated/invalid
			description = "Among other functionality, fallback if PUT and DELETE cannot be used by the client.")
	@PostMapping(path = "/{context-id}/resource/{entry-id}")
	public ModifyListResourceResponse modifyListResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam String moveEntry,
			@RequestParam String fromList,
			@RequestParam(required = false) String removeAll
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		Entry movedEntry = resourceService.modifyListEntryResource(entry, moveEntry, fromList, removeAll != null);

		return new ModifyListResourceResponse(movedEntry.getEntryURI().toString());
	}

	@Operation(
			summary = "Imports a ZIP file resource (not yet implemented).")
	@PostMapping(
			path = "/{context-id}/resource/{entry-id}",
			consumes = "application/zip")
	public void importListResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestBody byte[] body
	) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		resourceService.importEntryResource(entry, body);
	}

	@Operation(
			summary = "Deletes a resource.")
	@DeleteMapping("/{context-id}/resource/{entry-id}")
	public ResponseEntity<Void> deleteResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String proxy,
			@RequestParam(required = false) String recursive) {

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);
		resourceService.deleteResource(entry, proxy, recursive != null);

		return ResponseEntity.noContent().build();
	}

	private HttpHeaders buildFileDownloadResponseHeaders(ResourceRepresentation.FileDownload file, boolean isDownload) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(file.mediaType());

		ContentDisposition.Builder disposition = !allowContentDispositionInline || isDownload
				? ContentDisposition.attachment()
				: ContentDisposition.inline();
		headers.setContentDisposition(disposition.filename(file.filename()).build());

		if (file.sha256Digest() != null) {
			headers.set("Digest", "sha-256=" + file.sha256Digest());
		}
		return headers;
	}

	private static ResponseEntity<Void> buildSetResourceResponse(Entry entry, CompletionState result) {
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
				.status(mapCompletionStateToHttpStatus(result));

		if (result == CompletionState.CREATED) {
			responseBuilder
					.location(entry.getResourceURI());
		}
		if (result != CompletionState.ERROR) {
			responseBuilder
					.lastModified(entry.getModifiedDate().getTime());
		}

		return responseBuilder.build();
	}

	private static HttpStatus mapCompletionStateToHttpStatus(CompletionState state) {
		return switch (state) {
			case CREATED -> HttpStatus.CREATED;
			case UPDATED -> HttpStatus.NO_CONTENT;
			case OK -> HttpStatus.OK;
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}
}
