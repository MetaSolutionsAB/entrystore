package org.entrystore.rest.standalone.springboot.controller;

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.model.api.ListFilter;
import org.entrystore.rest.standalone.springboot.service.EntryService;
import org.entrystore.rest.standalone.springboot.service.ResourceService;
import org.entrystore.rest.standalone.springboot.service.SyndicationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceController {

	private final EntryService entryService;
	private final ResourceService resourceService;
	private final SyndicationService syndicationService;

	private final RepositoryManagerImpl repositoryManager;

	@Operation(
		summary = "Returns a resource.",
		description = "Depending on the entry’s character the resource may be binary, JSON or RDF. See Knowledge Base for details.")
	@GetMapping(path = "/{context-id}/resource/{entry-id}")
	public ResponseEntity<Object> getResource(
		@PathVariable("context-id") String contextId,
		@PathVariable("entry-id") String entryId,
		@RequestParam(required = false) String rdfFormat,
		@RequestParam(required = false) String syndication,
		@RequestParam(required = false, defaultValue = "50") Integer feedSize,
		@RequestParam(name = "lang", required = false, defaultValue = "en") String language,
		@RequestParam(required = false) String download,
		@ModelAttribute ListFilter listFilter,
		@RequestHeader(value = "Accept", required = false, defaultValue = "application/rdf+xml") String acceptHeader
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

		} else if (entry.getEntryType() == EntryType.Local && entry.getGraphType() == GraphType.None) {

			File file = resourceService.serializeResourceNoneAsFile(entry);
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
			responseBody = resourceService.serializeResourceAsJson(entry, mediaType, listFilter);
			responseMediaType = MediaType.APPLICATION_JSON;
		}

		return ResponseEntity
			.ok()
			.contentType(responseMediaType)
			.body(responseBody);
	}

	private static String convertSyndFeedToXml(SyndFeed feed) {
		try {
			// TODO: SyndFeedOutput seems thread-safe, hence should be fine to instantiate it only once per application, instead of per request?
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

	private HttpHeaders buildFileDownloadResponseHeaders(Entry entry, String mediaType, boolean isDownload) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(mediaType));

		// set file name
		String fileName = entry.getFilename();
		if (fileName == null) {
			fileName = entry.getId();
		}

		ContentDisposition contentDisposition;
		if (!repositoryManager.getConfiguration().getBoolean(Settings.HTTP_ALLOW_CONTENT_DISPOSITION_INLINE, true)
			|| isDownload) {
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

}
