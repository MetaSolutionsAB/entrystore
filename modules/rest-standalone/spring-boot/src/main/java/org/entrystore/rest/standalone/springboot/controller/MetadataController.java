package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.model.api.MetadataType;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.service.MetadataService;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.entrystore.rest.standalone.springboot.util.HttpUtil.determineMediaType;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MetadataController {

	private static final String DEFAULT_MEDIA_TYPE = "application/rdf+xml";

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
			@RequestParam(required = false) String format,
			@RequestParam(required = false) String graphQuery,
			@RequestParam(required = false, defaultValue = "10") Integer depth,
			@RequestParam(required = false) String recursive,
			@RequestParam(required = false) String scope,
			@RequestParam(name = "rev", required = false) String revision,
			@RequestParam(required = false) String download,
			@RequestHeader(value = "Accept", required = false, defaultValue = "application/rdf+xml") String acceptHeader
	) {
		String mediaType;
		// for 'format' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (format != null) {
			mediaType = GraphUtil.validateRdfMediaType(format.trim().replace(' ', '+'));
		} else {
			mediaType = GraphUtil.resolveAcceptedMediaType(acceptHeader, DEFAULT_MEDIA_TYPE);
		}

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);

		String responseBody = metadataService.getMetadata(entry, metadataType, mediaType, graphQuery, depth, recursive, scope, revision);

		HttpHeaders headers = buildResponseHeaders(entry, mediaType, download != null);
		return new ResponseEntity<>(responseBody, headers, HttpStatus.OK);
	}

	@Operation(
			summary = "Sets an entry's metadata graph.",
			description = "desc")
	@PutMapping(path = "/{context-id}/{type:metadata|cached-external-metadata|merged-metadata}/{entry-id}")
	public ResponseEntity<Void> setMetadata(
			@PathVariable("context-id") String contextId,
			@PathVariable("type") MetadataType metadataType,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String format,
			@RequestParam(name = "rev", required = false) String revision,
			@RequestHeader("Content-Type") String contentType,
			@RequestBody String body
	) {

		String mediaType = GraphUtil.validateRdfMediaType(determineMediaType(format, contentType));

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
		// TODO: set last modified based on travResult.getLatestModified()
		// headers.setLastModified(1);
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
