package org.entrystore.rest.util;

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
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.restlet.data.MediaType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class Syndication {

	public static final String URL_PARAM_TEMPLATE = "urltemplate";

	private static final String VAR_ENTRYID = "\\{entryid}";

	private static final String VAR_CONTEXTID = "\\{contextid}";

	private static final String VAR_ENTRYURI = "\\{entryuri}";

	private static final String VAR_RESOURCEURI = "\\{resourceuri}";

	public static String convertSyndFeedToXml(SyndFeed feed) {
		try {
			// TODO: SyndFeedOutput seems thread-safe, hence should be fine to instantiate it only once?
			return new SyndFeedOutput().outputString(feed, true);
		} catch (FeedException fe) {
			log.error(fe.getMessage());
			return fe.getMessage();
		}
	}

	public static MediaType convertFeedTypeToMediaType(String feedType) {
		if (feedType != null) {
			if (feedType.startsWith("rss_")) {
				return MediaType.APPLICATION_RSS;
			} else if (feedType.startsWith("atom_")) {
				return MediaType.APPLICATION_ATOM;
			}
		}
		return null;
	}

	public static SyndFeed createFeedFromEntries(RepositoryManager repositoryManager,
												 List<Entry> entries,
												 String language,
												 int limit,
												 String urlTemplate) {
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

				String link = constructSyndLinkFromUrlTemplate(repositoryManager.getConfiguration(), Objects.requireNonNullElse(urlTemplate, "default"), entry);
				if (link == null) {
					link = entry.getResourceURI().toString();
				}
				syndEntry.setLink(link);

				URI creator = entry.getCreator();
				if (creator != null) {
					try {
						Entry creatorEntry = repositoryManager.getPrincipalManager().getByEntryURI(creator);
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

	private static String constructSyndLinkFromUrlTemplate(Config config, String templateName, Entry entry) {
		String template = config.getString(Settings.SYNDICATION_URL_TEMPLATE + "." + templateName);
		if (template != null) {
			return template.replaceAll(VAR_ENTRYID, encode(entry.getId(), UTF_8)).
				replaceAll(VAR_CONTEXTID, encode(entry.getContext().getEntry().getId(), UTF_8)).
				replaceAll(VAR_ENTRYURI, encode(entry.getEntryURI().toString(), UTF_8)).
				replaceAll(VAR_RESOURCEURI, encode(entry.getResourceURI().toString(), UTF_8));
		}
		return null;
	}

}
