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

import com.github.benmanes.caffeine.cache.Cache;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
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
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SolrSearchIndexTest {

	private SolrSearchIndex index;
	private SolrClient solrServer;
	private Map<URI, Future> reindexingMap;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception {
		RepositoryManager rm = mock(RepositoryManager.class);
		Config config = new PropertiesConfiguration("EntryStore Test Configuration");
		when(rm.getConfiguration()).thenReturn(config);
		when(rm.getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
		solrServer = mock(SolrClient.class);

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

	@Test
	public void addGenericMetadataFieldsIndexesOneLiteralLTermPerLabelAndLanguage() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		IRI predicate = vf.createIRI("http://example.org/ns/place");
		IRI subjectA = vf.createIRI("http://example.org/a");
		IRI subjectB = vf.createIRI("http://example.org/b");
		Model model = new LinkedHashModel();
		model.add(subjectA, predicate, vf.createLiteral("Sverige", "sv"));
		model.add(subjectB, predicate, vf.createLiteral("Sverige", "sv"));
		model.add(subjectA, predicate, vf.createLiteral("Sverige", "nb"));
		model.add(subjectA, predicate, vf.createLiteral("Sweden", "en"));
		model.add(subjectB, predicate, vf.createLiteral("Sverige"));
		String hash = Hashing.hash(predicate.stringValue(), HashType.MD5).substring(0, 8);
		SolrInputDocument doc = new SolrInputDocument();

		index.addGenericMetadataFields(doc, model, false);

		// literal_s keeps collapsing the same label across languages
		assertEquals(Set.of("Sverige", "Sweden"), Set.copyOf(doc.getFieldValues("metadata.predicate.literal_s." + hash)));
		// literal_l keeps one term per (label, language); the repeated "Sverige"@sv is deduplicated
		Collection<Object> langTerms = doc.getFieldValues("metadata.predicate.literal_l." + hash);
		assertEquals(4, langTerms.size());
		Set<LangFacetValue> decoded = langTerms.stream()
				.map(Object::toString)
				.map(LangFacetValue::decode)
				.collect(Collectors.toSet());
		assertEquals(Set.of(
				new LangFacetValue("Sverige", "sv"),
				new LangFacetValue("Sverige", "nb"),
				new LangFacetValue("Sweden", "en"),
				new LangFacetValue("Sverige", null)), decoded);
	}

	@Test
	public void addGenericMetadataFieldsWritesRelatedLiteralLFieldForRelatedGraph() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		IRI predicate = vf.createIRI("http://example.org/ns/place");
		Model model = new LinkedHashModel();
		model.add(vf.createIRI("http://example.org/a"), predicate, vf.createLiteral("Sweden", "en"));
		String hash = Hashing.hash(predicate.stringValue(), HashType.MD5).substring(0, 8);
		SolrInputDocument doc = new SolrInputDocument();

		index.addGenericMetadataFields(doc, model, true);

		assertEquals(new LangFacetValue("Sweden", "en"),
				LangFacetValue.decode(doc.getFieldValue("related.metadata.predicate.literal_l." + hash).toString()));
		assertNull(doc.getFieldValues("metadata.predicate.literal_l." + hash));
	}

	@Test
	public void addGenericMetadataFieldsNormalisesTheCompanionLanguageTag() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		IRI predicate = vf.createIRI("http://example.org/ns/place");
		Model model = new LinkedHashModel();
		model.add(vf.createIRI("http://example.org/a"), predicate, vf.createLiteral("Colour", "en-gb"));
		model.add(vf.createIRI("http://example.org/a"), predicate, vf.createLiteral("F\u00e4rg", "SV"));
		String hash = Hashing.hash(predicate.stringValue(), HashType.MD5).substring(0, 8);
		SolrInputDocument doc = new SolrInputDocument();

		index.addGenericMetadataFields(doc, model, false);

		Set<LangFacetValue> decoded = doc.getFieldValues("metadata.predicate.literal_l." + hash).stream()
				.map(Object::toString)
				.map(LangFacetValue::decode)
				.collect(Collectors.toSet());
		assertEquals(Set.of(new LangFacetValue("Colour", "en-GB"), new LangFacetValue("F\u00e4rg", "sv")), decoded);
	}

	@Test
	public void addGenericMetadataFieldsSkipsTheCompanionForLabelsOverTheCap() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		IRI predicate = vf.createIRI("http://example.org/ns/description");
		String longLabel = "x".repeat(LangFacetValue.MAX_LABEL_LENGTH + 1);
		Model model = new LinkedHashModel();
		model.add(vf.createIRI("http://example.org/a"), predicate, vf.createLiteral(longLabel, "en"));
		model.add(vf.createIRI("http://example.org/a"), predicate, vf.createLiteral("short", "en"));
		String hash = Hashing.hash(predicate.stringValue(), HashType.MD5).substring(0, 8);
		SolrInputDocument doc = new SolrInputDocument();

		index.addGenericMetadataFields(doc, model, false);

		// literal_s still carries the long label; only the language companion is capped
		assertEquals(Set.of(longLabel, "short"), Set.copyOf(doc.getFieldValues("metadata.predicate.literal_s." + hash)));
		Collection<Object> langTerms = doc.getFieldValues("metadata.predicate.literal_l." + hash);
		assertEquals(1, langTerms.size());
		assertEquals(new LangFacetValue("short", "en"), LangFacetValue.decode(langTerms.iterator().next().toString()));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rejectedDocumentCountGrowsWhenSolrRejectsAnAddBatch() throws Exception {
		// An HTTP 400 (for example an unknown field under an outdated schema) discards the batch; the count is
		// what lets startup tell a rejected reindex from a successful one.
		when(solrServer.request(any(), any()))
				.thenThrow(new RemoteSolrException("localhost", 400, "unknown field metadata.predicate.literal_l.x", null));
		Field f = SolrSearchIndex.class.getDeclaredField("postQueue");
		f.setAccessible(true);
		Cache<URI, SolrInputDocument> postQueue = (Cache<URI, SolrInputDocument>) f.get(index);
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("uri", "http://example.org/e1");
		assertEquals(0, index.getRejectedDocumentCount());

		postQueue.put(URI.create("http://example.org/e1"), doc);

		assertTrue(index.waitForQueueDrain(), "a discarded batch leaves the queue empty");
		assertEquals(1, index.getRejectedDocumentCount());
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
