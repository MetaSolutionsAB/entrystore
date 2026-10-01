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
import org.entrystore.rest.springboot.model.exception.BadRequestException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
	void convertSyndFeedToXml_unsupportedFeedType_throwsBadRequest() {
		// Rome throws IllegalArgumentException for feed types it has no generator for;
		// the util maps it to a 400 for both the /search and resource syndication routes
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType("bogus_9.9");
		feed.setTitle("Test feed");
		feed.setDescription("Test description");
		feed.setLink("https://example.com/feed");

		var ex = assertThrows(BadRequestException.class, () -> Syndication.convertSyndFeedToXml(feed));

		assertEquals("Invalid syndication feed type: 'bogus_9.9'", ex.getMessage());
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
	void createFeedFromEntries_emitsExactlyLimitEntriesAndLoadsNoMetadataBeyondTheCut() {
		// D5 (ENTRYSTORE-1080): the feed-size cut is applied before any metadata is extracted and
		// counts emitted entries, so a limit of 2 yields exactly 2 — which is what the feed's own
		// "containing max %d items" description promises. The previous post-increment check
		// (`limitedCount++ >= limit`) emitted limit+1 and paid the metadata cost for the extra entry.
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry beyondCut = mockFeedEntry("e3");
		List<Entry> entries = List.of(mockFeedEntry("e1"), mockFeedEntry("e2"), beyondCut,
				mockFeedEntry("e4"), mockFeedEntry("e5"));

		SyndFeed feed = Syndication.createFeedFromEntries(pm, null, entries, null, 2);

		assertEquals(2, feed.getEntries().size());
		verify(beyondCut, never()).getMetadataGraph();
	}

	@Test
	void createFeedFromEntries_noTemplate_fallsBackToResourceUri() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry entry = mockFeedEntry("e1");

		SyndFeed feed = Syndication.createFeedFromEntries(pm, null, List.of(entry), null, 10);

		assertEquals("http://example.com/ctx/resource/e1", feed.getEntries().getFirst().getLink());
	}

	@Test
	void createFeedFromEntries_urlTemplate_substitutesPlaceholders() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry entry = mockFeedEntry("e1");
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getId()).thenReturn("c1");
		Context context = mock(Context.class);
		when(context.getEntry()).thenReturn(contextEntry);
		when(entry.getContext()).thenReturn(context);

		SyndFeed feed = Syndication.createFeedFromEntries(pm,
				"https://example.com/{contextid}/{entryid}?uri={entryuri}", List.of(entry), null, 10);

		String expectedEntryUri = URLEncoder.encode("http://example.com/ctx/entry/e1", UTF_8);
		assertEquals("https://example.com/c1/e1?uri=" + expectedEntryUri, feed.getEntries().getFirst().getLink());
	}

	@Test
	void createFeedFromEntries_authorizationException_skipsEntry() {
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry forbidden = mockFeedEntry("forbidden");
		when(forbidden.getCreationDate()).thenThrow(new AuthorizationException(null, null, null));
		List<Entry> entries = List.of(forbidden, mockFeedEntry("allowed"));

		SyndFeed feed = Syndication.createFeedFromEntries(pm, null, entries, null, 10);

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
		Entry entry = mockFeedEntry("e1");

		SyndFeed feed = Syndication.createFeedFromEntries(pm, null, List.of(entry), null, 10);

		assertEquals("Missing title", feed.getEntries().getFirst().getTitle());
	}

	@Test
	void createFeedFromEntries_templateWithoutContextId_stillResolvesTheContextEagerly() {
		// Every placeholder is substituted unconditionally, so entry.getContext() is dereferenced even for
		// a template that never mentions {contextid} — an entry whose context is unavailable would NPE.
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry entry = mockFeedEntry("e1");
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getId()).thenReturn("c1");
		Context context = mock(Context.class);
		when(context.getEntry()).thenReturn(contextEntry);
		when(entry.getContext()).thenReturn(context);

		SyndFeed feed = Syndication.createFeedFromEntries(pm, "https://example.com/view/{entryid}",
				List.of(entry), null, 10);

		assertEquals("https://example.com/view/e1", feed.getEntries().getFirst().getLink());
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
