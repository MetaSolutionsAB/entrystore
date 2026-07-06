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
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		// rss_2.0 requires channel title/description/link, so an empty feed fails serialization
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType("rss_2.0");

		var ex = assertThrows(InternalServerErrorException.class, () -> Syndication.convertSyndFeedToXml(feed));

		assertEquals("Error serializing the syndication feed", ex.getMessage());
		assertInstanceOf(FeedException.class, ex.getCause());
	}

}
