package org.entrystore.rest.standalone.springboot.controller;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.service.ResourceService;
import org.entrystore.rest.standalone.springboot.service.SyndicationService;
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
public class ResourceController {

	private final EntryService entryService;
	private final ResourceService resourceService;
	private final SyndicationService syndicationService;

	@Operation(
		summary = "Returns a resource.",
		description = "Depending on the entry’s character the resource may be binary, JSON or RDF. See Knowledge Base for details.")
	@GetMapping(path = "/{context-id}/resource/{entry-id}")
	public ResponseEntity<String> getResource(
		@PathVariable("context-id") String contextId,
		@PathVariable("entry-id") String entryId,
		@RequestParam(required = false) String rdfFormat,
		@RequestParam(required = false) String syndication,

		@RequestParam(required = false, defaultValue = "50") Integer feedSize,
		@RequestParam(name = "lang", required = false, defaultValue = "en") String language,
		@RequestHeader(value = "Accept", required = false, defaultValue = MediaType.APPLICATION_JSON_VALUE) String acceptHeader
	) {
		String mediaType;
		// for 'rdfFormat' param data should be sent properly - i.e. html encoded '+' as %2B
		// however, we also support the non-encoded values here, and since Spring-boot automatically decodes the params
		// (+ is replaced with a space) we need to replace the space back to '+'
		if (rdfFormat != null) {
			mediaType = rdfFormat.trim().replace(' ', '+');
		} else {
			mediaType = acceptHeader;
		}

		Entry entry = entryService.getEntryByContextIdAndEntryId(contextId, entryId);

		String responseBody;
		MediaType responseMediaType;

		if (syndication != null) {
			SyndFeed feed = syndicationService.getSyndicationFeedSolr(entry, syndication, language, feedSize);
			responseBody = convertSyndFeedToXml(feed);
			responseMediaType = mapFeedTypeToMediaType(feed.getFeedType(), mediaType);
		} else {
			responseBody = resourceService.getResource(entry, rdfFormat, syndication, feedSize);
			responseMediaType = null;
		}

		return ResponseEntity
			.ok()
			.contentType(responseMediaType)
			.body(responseBody);
	}

	private static String convertSyndFeedToXml(SyndFeed feed) {
		try {
			// TODO: SyndFeedOutput seems thread-safe, hence should be fine to instantiate it only once?
			return new SyndFeedOutput().outputString(feed, true);
		} catch (FeedException fe) {
			throw new IllegalStateException("Exception serializing the syndication feed with title: " + feed.getTitle());
		}
	}

	private static MediaType mapFeedTypeToMediaType(String feedType, String requestMediaType) {

		String feedMediaType = null;
		if (feedType != null) {
			if (feedType.startsWith("rss_")) {
				feedMediaType = MediaType.APPLICATION_RSS_XML_VALUE;
			} else if (feedType.startsWith("atom_")) {
				feedMediaType = MediaType.APPLICATION_ATOM_XML_VALUE;
			}
		}

		String responseMediaType = (feedMediaType != null) ? feedMediaType : requestMediaType;
		return MediaType.parseMediaType(responseMediaType);
	}

}
