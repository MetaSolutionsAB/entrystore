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

package org.entrystore.repository.util;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SolrSearchIndexTest {

	private SolrSearchIndex index;
	private Map<URI, Future> reindexingMap;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception {
		RepositoryManager rm = mock(RepositoryManager.class);
		Config config = new PropertiesConfiguration("EntryStore Test Configuration");
		when(rm.getConfiguration()).thenReturn(config);
		when(rm.getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
		SolrClient solrServer = mock(SolrClient.class);

		index = new SolrSearchIndex(rm, solrServer);

		Field f = SolrSearchIndex.class.getDeclaredField("reindexing");
		f.setAccessible(true);
		reindexingMap = (Map<URI, Future>) f.get(index);
	}

	@AfterEach
	public void tearDown() {
		if (index != null) {
			index.shutdown();
		}
	}

	@Test
	public void isIndexingReturnsTrueWhenAnyContextIsBeingIndexed() {
		URI ctx = URI.create("http://localhost:8181/_contexts/entry/1");
		reindexingMap.put(ctx, CompletableFuture.completedFuture(null));

		assertTrue(index.isIndexing(), "isIndexing() must be true while any per-context reindex is in progress");
	}

	@Test
	public void isIndexingReturnsFalseWhenNoContextIsBeingIndexed() {
		assertFalse(index.isIndexing(), "isIndexing() must be false when reindexing map is empty");
	}

	@Test
	public void isIndexingWithUriReturnsTrueOnlyForMatchingContext() {
		URI ctx1 = URI.create("http://localhost:8181/_contexts/entry/1");
		URI ctx2 = URI.create("http://localhost:8181/_contexts/entry/2");
		reindexingMap.put(ctx1, CompletableFuture.completedFuture(null));

		assertTrue(index.isIndexing(ctx1));
		assertFalse(index.isIndexing(ctx2));
	}

	@Test
	public void isIndexingWithNullReturnsFalseWhenOnlyPerContextEntriesPresent() {
		// Regression-protect: the no-arg method must NOT silently probe the `null` key,
		// and the URI overload must keep its literal containsKey() semantics.
		URI ctx = URI.create("http://localhost:8181/_contexts/entry/1");
		reindexingMap.put(ctx, CompletableFuture.completedFuture(null));

		assertFalse(index.isIndexing(null));
	}

	@Disabled("To be implemented")
	@Test
	public void testShutdown() throws Exception {
		// TODO
	}

	@Test
	public void clearSolrIndexBuildsUtcMillisecondDateRangeDeleteQuery() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Date expiration = Date.from(Instant.parse("2024-01-15T10:30:00.123Z"));

		assertTrue(index.clearSolrIndex(client, expiration, null));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"indexedAt:[* TO 2024\\-01\\-15T10\\:30\\:00.123Z}",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexBuildsContextOnlyDeleteQuery() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getResourceURI()).thenReturn(URI.create("http://localhost:8181/1"));

		assertTrue(index.clearSolrIndex(client, null, contextEntry));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"context:http\\:\\/\\/localhost\\:8181\\/1",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexCombinesDateAndContextClausesWithAnd() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getResourceURI()).thenReturn(URI.create("http://localhost:8181/1"));
		Date expiration = Date.from(Instant.parse("2024-01-15T10:30:00.123Z"));

		assertTrue(index.clearSolrIndex(client, expiration, contextEntry));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"indexedAt:[* TO 2024\\-01\\-15T10\\:30\\:00.123Z} AND context:http\\:\\/\\/localhost\\:8181\\/1",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexReturnsFalseWhenSolrRequestFails() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new SolrServerException("solr down"));

		assertFalse(index.clearSolrIndex(client, new Date(), null));
	}

	@Test
	public void dateToSolrDateStringNormalizesOffsetAndUndefinedTimezoneToUtc() throws Exception {
		Method dateToSolrDateString = SolrSearchIndex.class.getDeclaredMethod("dateToSolrDateString", XMLGregorianCalendar.class);
		dateToSolrDateString.setAccessible(true);
		DatatypeFactory factory = DatatypeFactory.newInstance();

		// +02:00 offset must be shifted to the same instant in UTC
		XMLGregorianCalendar withOffset = factory.newXMLGregorianCalendar("2024-01-15T12:30:00.123+02:00");
		assertEquals("2024-01-15T10:30:00.123Z", dateToSolrDateString.invoke(index, withOffset));

		// undefined timezone must be interpreted as UTC, not the JVM default zone
		XMLGregorianCalendar undefinedTimezone = factory.newXMLGregorianCalendar("2024-01-15T10:30:00.123");
		assertEquals("2024-01-15T10:30:00.123Z", dateToSolrDateString.invoke(index, undefinedTimezone));
	}

	@Disabled("To be implemented")
	@Test
	public void testReindexLiterals() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testConstructSolrInputDocument() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testPostEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemoveEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSendQuery() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testExtractFulltext() throws Exception {
		// TODO
	}
}
