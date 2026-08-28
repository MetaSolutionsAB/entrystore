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

import com.rometools.rome.feed.synd.SyndFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.impl.DataImpl;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.ModifyListResourceResponse;
import org.entrystore.rest.springboot.model.dto.CompletionState;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.service.EntryService;
import org.entrystore.rest.springboot.service.ResourceService;
import org.entrystore.rest.springboot.service.SyndicationService;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.MultipartUtil;
import org.entrystore.rest.springboot.util.Syndication;
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

import java.io.File;

import static org.entrystore.rest.springboot.util.HttpUtil.normalizeMediaType;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceController {

	private final EntryService entryService;
	private final ResourceService resourceService;
	private final SyndicationService syndicationService;

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
			@RequestParam(required = false, defaultValue = "50") Integer feedSize,
			@RequestParam(name = "lang", required = false, defaultValue = "en") String language,
			@RequestParam(required = false) String download,
			@ModelAttribute ListFilter listFilter,
			@RequestHeader(value = "Accept", required = false, defaultValue = GraphUtil.DEFAULT_RDF_MEDIA_TYPE) String acceptHeader
	) {
		String mediaType = rdfFormat != null ? rdfFormat.toString() : acceptHeader;

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);

		String responseBody;
		MediaType responseMediaType;

		if (syndication != null) {
			SyndFeed feed = syndicationService.getSyndicationFeedSolr(entry, syndication, language, feedSize);
			responseBody = Syndication.convertSyndFeedToXml(feed);
			MediaType feedMediaType = Syndication.convertFeedTypeToMediaType(feed.getFeedType());
			responseMediaType = feedMediaType != null ? feedMediaType : MediaType.parseMediaType(mediaType);

		} else if (entry.getEntryType() == EntryType.Local && entry.getGraphType() == GraphType.None) {

			File file = resourceService.serializeResourceNoneAsFile(entry);
			if (file == null) {
				return ResponseEntity
						.noContent()
						.build();
			}
			String responseMediaTypeStr = resourceService.determineMediaTypeForDownload(entry);
			HttpHeaders httpHeaders = buildFileDownloadResponseHeaders(entry, responseMediaTypeStr, download != null);

			return ResponseEntity
					.ok()
					.headers(httpHeaders)
					.contentLength(file.length())
					.body(new FileSystemResource(file));

		} else if (entry.getEntryType() == EntryType.Local && entry.getGraphType() == GraphType.String) {

			responseBody = resourceService.serializeResourceString(entry);
			responseMediaType = MediaType.TEXT_PLAIN;

		} else {
			GraphType graphType = entry.getGraphType();
			if (graphType == GraphType.Graph || graphType == GraphType.List) {
				mediaType = GraphUtil.resolveRdfMediaType(rdfFormat, acceptHeader);
				responseMediaType = MediaType.parseMediaType(mediaType);
			} else {
				responseMediaType = MediaType.APPLICATION_JSON;
			}
			responseBody = resourceService.serializeResourceAsJson(entry, mediaType, listFilter);
		}

		return ResponseEntity
				.ok()
				.contentType(responseMediaType)
				.body(responseBody);
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
		CompletionState result = resourceService.setEntryResource(entry, body, mediaType, mimeType, textarea != null, filename, session.getId());

		if (result != CompletionState.ERROR) {
			entry.updateModificationDate();
		}

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
					schemaProperties = @SchemaProperty(
							name = "file",
							schema = @Schema(type = "string", format = "binary"))))
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

		if (result != CompletionState.ERROR) {
			entry.updateModificationDate();
		}

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

	private HttpHeaders buildFileDownloadResponseHeaders(Entry entry, String mediaType, boolean isDownload) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(mediaType));

		// set file name
		String fileName = entry.getFilename();
		if (fileName == null) {
			fileName = entry.getId();
		}

		ContentDisposition contentDisposition;
		if (!allowContentDispositionInline || isDownload) {
			contentDisposition = ContentDisposition.attachment().filename(fileName).build();
		} else {
			contentDisposition = ContentDisposition.inline().filename(fileName).build();
		}
		headers.setContentDisposition(contentDisposition);

		DataImpl data = new DataImpl(entry);
		String digest = data.readDigest();
		if (digest != null) {
			headers.set("Digest", "sha-256=" + digest);
		} else {
			log.debug("Digest does not exist for entry [{}]", entry.getResourceURI());
		}
		return headers;
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
