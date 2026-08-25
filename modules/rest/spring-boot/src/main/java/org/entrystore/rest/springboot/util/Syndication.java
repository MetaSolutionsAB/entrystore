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

package org.entrystore.rest.springboot.util;

import com.google.common.html.HtmlEscapers;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class Syndication {

	private static final String VAR_ENTRYID = "\\{entryid}";

	private static final String VAR_CONTEXTID = "\\{contextid}";

	private static final String VAR_ENTRYURI = "\\{entryuri}";

	private static final String VAR_RESOURCEURI = "\\{resourceuri}";

	// SyndFeedOutput is stateless (WireFeedOutput holds no instance state; generators are statically cached)
	private static final SyndFeedOutput SYND_FEED_OUTPUT = new SyndFeedOutput();

	public static String convertSyndFeedToXml(SyndFeed feed) {
		try {
			return SYND_FEED_OUTPUT.outputString(feed, true);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("Invalid syndication feed type: '" + feed.getFeedType() + "'");
		} catch (FeedException fe) {
			throw new InternalServerErrorException("Error serializing the syndication feed with title: " + feed.getTitle(), fe);
		}
	}

	public static MediaType convertFeedTypeToMediaType(String feedType) {
		if (feedType != null) {
			if (feedType.startsWith("rss_")) {
				return new MediaType(MediaType.APPLICATION_RSS_XML, UTF_8);
			} else if (feedType.startsWith("atom_")) {
				return new MediaType(MediaType.APPLICATION_ATOM_XML, UTF_8);
			}
		}
		return null;
	}

	/**
	 * Creates a feed whose entry links are built from the given URL template. The template is the
	 * already-resolved value, not the name a client asked for — resolve it through
	 * {@code SyndicationProperties#template(String)} first. A null template falls back to each entry's
	 * resource URI.
	 */
	public static SyndFeed createFeedFromEntries(PrincipalManager principalManager,
												 String resolvedUrlTemplate,
												 List<Entry> entries,
												 String language,
												 int limit) {
		return createFeedFromEntries(principalManager, entries, language, limit, entry -> {
			String link = constructSyndLinkFromUrlTemplate(resolvedUrlTemplate, entry);
			return link != null ? link : entry.getResourceURI().toString();
		});
	}

	public static SyndFeed createFeedFromEntries(PrincipalManager principalManager,
												 List<Entry> entries,
												 String language,
												 int limit,
												 Function<Entry, String> linkBuilder) {

		SyndFeed feed = new SyndFeedImpl();
		feed.setDescription(format("Syndication feed containing max %d items", limit));

		List<SyndEntry> syndEntries = new ArrayList<>();
		int limitedCount = 0;

		for (Entry entry : entries) {
			try {
				String title = EntryUtil.getTitle(entry, language);
				String description = EntryUtil.getDescription(entry, language);

				if (title == null && description == null) {
					log.debug("Entry has neither title, nor description: {}", entry.getEntryURI());
				}

				SyndEntry syndEntry = new SyndEntryImpl();
				syndEntry.setTitle(Objects.requireNonNullElse(title, "Missing title"));

				if (description != null) {
					SyndContent syndContentDescription = new SyndContentImpl();
					syndContentDescription.setType("text/plain");
					syndContentDescription.setValue(description);
					syndEntry.setDescription(syndContentDescription);
				}

				syndEntry.setPublishedDate(entry.getCreationDate());
				syndEntry.setUpdatedDate(entry.getModifiedDate());
				syndEntry.setLink(linkBuilder.apply(entry));

				URI creator = entry.getCreator();
				if (creator != null) {
					try {
						Entry creatorEntry = principalManager.getByEntryURI(creator);
						String creatorName = EntryUtil.getName(creatorEntry);
						if (creatorName != null) {
							syndEntry.setAuthor(creatorName);
						}
					} catch (AuthorizationException ae) {
						log.debug(ae.getMessage());
					}
				}

				syndEntries.add(syndEntry);
			} catch (AuthorizationException e) {
				log.debug(e.getMessage());
				continue;
			}

			if (limitedCount++ >= limit) {
				break;
			}
		}

		feed.setEntries(syndEntries);

		return feed;
	}

	private static String constructSyndLinkFromUrlTemplate(String template, Entry entry) {
		if (template != null) {
			return template.replaceAll(VAR_ENTRYID, encode(entry.getId(), UTF_8)).
					replaceAll(VAR_CONTEXTID, encode(entry.getContext().getEntry().getId(), UTF_8)).
					replaceAll(VAR_ENTRYURI, encode(entry.getEntryURI().toString(), UTF_8)).
					replaceAll(VAR_RESOURCEURI, encode(entry.getResourceURI().toString(), UTF_8));
		}
		return null;
	}

	public static String sanitizeFeedTitle(String feedTitle) {
		String result = feedTitle;
		if (result != null) {
			if (result.length() > 64) {
				result = result.substring(0, 63);
			}
			result = HtmlEscapers.htmlEscaper().escape(result);
		}
		return result;
	}

}
