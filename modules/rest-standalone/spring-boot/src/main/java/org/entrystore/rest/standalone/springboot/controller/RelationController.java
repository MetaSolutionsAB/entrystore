package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.service.RelationService;
import org.entrystore.rest.standalone.springboot.util.GraphUtil;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RelationController {

	private static final String DEFAULT_MEDIA_TYPE = "application/rdf+xml";

	private final EntryService entryService;
	private final RelationService relationService;

	@Operation(summary = "Returns an RDF-graph with an entry's relations with other entries")
	@GetMapping(
			path = "/{context-id}/relations/{entry-id}",
			produces = {
					MediaType.APPLICATION_JSON_VALUE,
					MediaType.APPLICATION_OCTET_STREAM_VALUE,
					"application/rdf+xml", "text/n3", "text/rdf+n3", "text/turtle",
					"application/trix", "application/n-triples", "application/trig",
					"application/ld+json", "application/rdf+json"
			}
	)
	public ResponseEntity<String> getResource(
			@PathVariable("context-id") String contextId,
			@PathVariable("entry-id") String entryId,
			@RequestParam(required = false) String format,
			@RequestHeader(value = "Accept", defaultValue = DEFAULT_MEDIA_TYPE) String acceptHeader
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
		String relationsGraph = relationService.getEntryRelations(entry, mediaType);

		ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity
				.ok()
				.contentType(MediaType.parseMediaType(mediaType));

		if (entry.getModifiedDate() != null) {
			bodyBuilder
					.lastModified(entry.getModifiedDate().getTime())
					.eTag(HttpUtil.createStrongETag(Long.toString(entry.getModifiedDate().getTime())));
		}
		return bodyBuilder
				.body(relationsGraph);
	}
}
