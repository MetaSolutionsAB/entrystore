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

import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyndicationTest {

	@Test
	void convertSyndFeedToXml_validFeed_returnsFeedXml() {
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType("rss_2.0");
		feed.setTitle("Test feed");
		feed.setDescription("Test description");
		feed.setLink("https://example.com/feed");

		String xml = Syndication.convertSyndFeedToXml(feed);

		assertTrue(xml.contains("<title>Test feed</title>"));
		assertTrue(xml.contains("<description>Test description</description>"));
	}

	@Test
	void convertSyndFeedToXml_unserializableFeed_throwsInsteadOfReturningErrorMessageAsBody() {
		// rss_2.0 requires channel title/description/link, so a feed without description/link
		// fails serialization; the title identifies the failing feed in the logs
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType("rss_2.0");
		feed.setTitle("Broken feed");

		var ex = assertThrows(InternalServerErrorException.class, () -> Syndication.convertSyndFeedToXml(feed));

		assertEquals("Error serializing the syndication feed with title: Broken feed", ex.getMessage());
		assertInstanceOf(FeedException.class, ex.getCause());
	}

	@Test
	void convertFeedTypeToMediaType_rss_returnsRssWithUtf8Charset() {
		MediaType mediaType = Syndication.convertFeedTypeToMediaType("rss_2.0");
		assertEquals(new MediaType(MediaType.APPLICATION_RSS_XML, UTF_8), mediaType);
	}

	@Test
	void convertFeedTypeToMediaType_atom_returnsAtomWithUtf8Charset() {
		MediaType mediaType = Syndication.convertFeedTypeToMediaType("atom_1.0");
		assertEquals(new MediaType(MediaType.APPLICATION_ATOM_XML, UTF_8), mediaType);
	}

	@Test
	void convertFeedTypeToMediaType_nonFeedType_returnsNull() {
		assertNull(Syndication.convertFeedTypeToMediaType("text/html"));
		assertNull(Syndication.convertFeedTypeToMediaType(null));
	}

	@Test
	void createFeedFromEntries_producesLimitPlusOneEntries() {
		// Pins the off-by-one of the post-increment limit check (`limitedCount++ >= limit`):
		// a limit of 2 yields 3 feed entries. Kept as-is for backwards compatibility.
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		List<Entry> entries = List.of(mockFeedEntry("e1"), mockFeedEntry("e2"), mockFeedEntry("e3"),
				mockFeedEntry("e4"), mockFeedEntry("e5"));

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, entries, null, 2, null);

		assertEquals(3, feed.getEntries().size());
	}

	@Test
	void createFeedFromEntries_nullUrlTemplate_looksUpDefaultTemplate() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		when(config.getString(Settings.SYNDICATION_URL_TEMPLATE + ".default"))
				.thenReturn("https://example.com/view/{entryid}");
		Entry entry = mockFeedEntry("e1");
		// the {contextid} replacement resolves the context eagerly even when the template omits it
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getId()).thenReturn("c1");
		Context context = mock(Context.class);
		when(context.getEntry()).thenReturn(contextEntry);
		when(entry.getContext()).thenReturn(context);

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, List.of(entry), null, 10, null);

		assertEquals("https://example.com/view/e1", feed.getEntries().getFirst().getLink());
	}

	@Test
	void createFeedFromEntries_noConfiguredTemplate_fallsBackToResourceUri() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		when(config.getString(Settings.SYNDICATION_URL_TEMPLATE + ".default")).thenReturn(null);
		Entry entry = mockFeedEntry("e1");

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, List.of(entry), null, 10, null);

		assertEquals("http://example.com/ctx/resource/e1", feed.getEntries().getFirst().getLink());
	}

	@Test
	void createFeedFromEntries_urlTemplate_substitutesPlaceholders() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		when(config.getString(Settings.SYNDICATION_URL_TEMPLATE + ".custom"))
				.thenReturn("https://example.com/{contextid}/{entryid}?uri={entryuri}");
		Entry entry = mockFeedEntry("e1");
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getId()).thenReturn("c1");
		Context context = mock(Context.class);
		when(context.getEntry()).thenReturn(contextEntry);
		when(entry.getContext()).thenReturn(context);

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, List.of(entry), null, 10, "custom");

		String expectedEntryUri = URLEncoder.encode("http://example.com/ctx/entry/e1", UTF_8);
		assertEquals("https://example.com/c1/e1?uri=" + expectedEntryUri, feed.getEntries().getFirst().getLink());
	}

	@Test
	void createFeedFromEntries_authorizationException_skipsEntry() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		Entry forbidden = mockFeedEntry("forbidden");
		when(forbidden.getCreationDate()).thenThrow(new AuthorizationException(null, null, null));
		List<Entry> entries = List.of(forbidden, mockFeedEntry("allowed"));

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, entries, null, 10, null);

		assertEquals(1, feed.getEntries().size());
	}

	@Test
	void createFeedFromEntries_linkBuilder_isUsedPerEntry() {
		PrincipalManager pm = mock(PrincipalManager.class);
		List<Entry> entries = List.of(mockFeedEntry("e1"), mockFeedEntry("e2"));

		SyndFeed feed = Syndication.createFeedFromEntries(pm, entries, null, 10,
				entry -> "https://custom.example.com/" + entry.getId());

		assertEquals("https://custom.example.com/e1", feed.getEntries().get(0).getLink());
		assertEquals("https://custom.example.com/e2", feed.getEntries().get(1).getLink());
	}

	@Test
	void createFeedFromEntries_entryWithoutTitle_getsMissingTitlePlaceholder() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Config config = mock(Config.class);
		Entry entry = mockFeedEntry("e1");

		SyndFeed feed = Syndication.createFeedFromEntries(pm, config, List.of(entry), null, 10, null);

		assertEquals("Missing title", feed.getEntries().getFirst().getTitle());
	}

	private static Entry mockFeedEntry(String id) {
		Entry entry = mock(Entry.class);
		when(entry.getMetadataGraph()).thenReturn(new LinkedHashModel());
		when(entry.getId()).thenReturn(id);
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/ctx/entry/" + id));
		when(entry.getResourceURI()).thenReturn(URI.create("http://example.com/ctx/resource/" + id));
		when(entry.getCreationDate()).thenReturn(new Date(1000));
		when(entry.getModifiedDate()).thenReturn(new Date(2000));
		when(entry.getCreator()).thenReturn(null);
		return entry;
	}

}
