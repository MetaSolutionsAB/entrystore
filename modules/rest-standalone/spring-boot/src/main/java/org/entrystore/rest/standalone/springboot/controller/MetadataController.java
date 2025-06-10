package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.model.api.MetadataType;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.service.MetadataService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
	@GetMapping(path = "/{context-id}/{type:metadata|cached-external-metadata|merged-metadata}/{entry-id}",
		produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getMetadata(
		@PathVariable("context-id") String contextId,
		@PathVariable("type") MetadataType metadataType,
		@PathVariable("entry-id") String entryId,
		@RequestParam(required = false) String format,
		@RequestParam(required = false) String graphQuery,
		@RequestParam(required = false, defaultValue = "10") Integer depth,
		@RequestParam(required = false) String recursive,
		@RequestParam(required = false) String scope,
		@RequestParam(required = false) String rev,
		@RequestParam(required = false) String download,
		@RequestHeader(value = "Accept", required = false, defaultValue = "application/rdf+xml") String acceptHeader
	) {
		String mediaType;
		// for 'format' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (format != null) {
			mediaType = format.trim().replace(' ', '+');
		} else {
			mediaType = acceptHeader;
		}

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);

		String responseBody = metadataService.getMetadata(entry, metadataType, mediaType, graphQuery, depth, recursive, scope, rev);

		HttpHeaders headers = buildResponseHeaders(entry, mediaType, download != null);
		return new ResponseEntity<>(responseBody, headers, HttpStatus.OK);
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

}
