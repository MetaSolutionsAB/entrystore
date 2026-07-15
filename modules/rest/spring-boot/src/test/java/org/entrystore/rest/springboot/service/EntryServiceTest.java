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

package org.entrystore.rest.springboot.service;

import tools.jackson.databind.json.JsonMapper;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryException;
import org.entrystore.rest.springboot.model.api.CreateEntryRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;

import java.net.URI;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntryServiceTest {

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextService contextService;

	@Mock
	private ReservedNamesService reservedNamesService;

	@Mock
	private ResourceJsonSerializer resourceSerializer;

	@Mock
	private Context context;

	@Mock
	private Entry entry;

	@Mock
	private User user;

	private EntryService service;

	@BeforeEach
	void setUp() {
		service = new EntryService(
				principalManager, repositoryManager, contextService,
				reservedNamesService, resourceSerializer, new JsonMapper());
	}

	@Test
	void createLocalEntry_jsonParseFailureWithCleanupAlsoThrowing_preservesOriginalCauseAndSuppressesCleanup() {
		// Pins the rollback contract: when a JSON parse failure triggers the orphan rollback AND the
		// rollback itself fails, the client-visible BadRequestException must carry the original parse
		// error as its cause (status stays 400, not 500), and the cleanup failure must remain visible
		// on the exception chain for server-side debugging. A regression that drops the cleanup
		// failure, or rethrows it instead of the original error, would change the response status
		// or hide the diagnostic trail.
		URI entryUri = URI.create("http://example.org/1/entry/orphan");
		when(context.createResource(any(), any(), any(), any())).thenReturn(entry);
		when(entry.getEntryURI()).thenReturn(entryUri);
		when(entry.getId()).thenReturn("orphan");
		when(entry.getGraphType()).thenReturn(GraphType.User);
		when(entry.getResource()).thenReturn(user);
		RepositoryException cleanupFailure = new RepositoryException("rdf4j transaction error");
		doThrow(cleanupFailure).when(context).remove(entryUri);

		CreateEntryRequestBody body = new CreateEntryRequestBody("{not-json", null, null, null);

		BadRequestException thrown = assertThrows(BadRequestException.class, () ->
				service.createLocalEntry(context, "orphan", GraphType.User, null, null, body));

		assertEquals("Cannot create an entry with provided JSON/RDF", thrown.getMessage());
		Throwable cause = thrown.getCause();
		assertInstanceOf(JacksonException.class, cause, "Original parse error must be the cause");
		// Walk both levels: the cleanup failure may be attached to the original parse error (current
		// implementation) OR to the rethrown BadRequestException (a plausible future refactor — the
		// cleanup failure relates to this operation, not to the parse error). Either is acceptable
		// as long as it remains visible on the exception chain a server-side log line will print.
		boolean cleanupVisible =
				Arrays.asList(thrown.getSuppressed()).contains(cleanupFailure)
				|| Arrays.asList(cause.getSuppressed()).contains(cleanupFailure);
		assertTrue(cleanupVisible, "Cleanup failure must be visible somewhere on the exception chain");
		verify(context).remove(entryUri);
	}

	@Test
	void createLinkEntry_malformedMetadataJson_logsAndStillReturnsEntry() {
		// The graph setters log-and-continue on malformed RDF/JSON — entry creation must not fail.
		when(context.createLink(any(), any(), any())).thenReturn(entry);
		when(entry.getEntryType()).thenReturn(EntryType.Link);
		when(entry.getId()).thenReturn("e1");
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.org/1/entry/e1"));
		CreateEntryRequestBody body = new CreateEntryRequestBody(null, "{not-json", null, null);

		Entry result = service.createLinkEntry(context, "e1", null, URI.create("http://external.example.com/doc"), null, body);

		assertSame(entry, result);
		verify(entry, never()).setGraphType(any());
	}

	@Test
	void createReferenceEntry_nullResourceUri_returnsNullWithoutCreating() {
		Entry result = service.createReferenceEntry(context, "e1", null, null, null,
				URI.create("http://external.example.com/md"), null);

		assertNull(result);
		verifyNoInteractions(context);
	}

	@Test
	void createLinkReferenceEntry_setsAllGraphsAndCopiesAcl() {
		ContextImpl contextImpl = mock(ContextImpl.class);
		when(contextImpl.createLinkReference(any(), any(), any(), any())).thenReturn(entry);
		when(entry.getEntryType()).thenReturn(EntryType.LinkReference);
		when(entry.getId()).thenReturn("e1");
		Metadata localMetadata = mock(Metadata.class);
		Metadata cachedMetadata = mock(Metadata.class);
		when(entry.getLocalMetadata()).thenReturn(localMetadata);
		when(entry.getCachedExternalMetadata()).thenReturn(cachedMetadata);
		URI listUri = URI.create("http://example.org/1/resource/list1");
		String rdfJson = "{\"http://example.org/1/resource/e1\":"
				+ "{\"http://purl.org/dc/terms/title\":[{\"value\":\"t\",\"type\":\"literal\"}]}}";
		CreateEntryRequestBody body = new CreateEntryRequestBody(null, rdfJson, rdfJson, rdfJson);

		Entry result = service.createLinkReferenceEntry(contextImpl, "e1", GraphType.List,
				URI.create("http://external.example.com/doc"), listUri, URI.create("http://external.example.com/md"), body);

		assertSame(entry, result);
		verify(localMetadata).setGraph(any());
		verify(cachedMetadata).setGraph(any());
		verify(entry).setGraph(any());
		verify(entry).setGraphType(GraphType.List);
		verify(contextImpl).copyACL(listUri, entry);
	}

	@Test
	void createReferenceEntry_metadataBodyIgnored_onlyCachedMetadataAndInfoSet() {
		// setLocalMetadataGraph must no-op for Reference entries even when the body carries metadata.
		when(context.createReference(any(), any(), any(), any())).thenReturn(entry);
		when(entry.getEntryType()).thenReturn(EntryType.Reference);
		when(entry.getId()).thenReturn("e1");
		Metadata cachedMetadata = mock(Metadata.class);
		when(entry.getCachedExternalMetadata()).thenReturn(cachedMetadata);
		String rdfJson = "{\"http://example.org/1/resource/e1\":"
				+ "{\"http://purl.org/dc/terms/title\":[{\"value\":\"t\",\"type\":\"literal\"}]}}";
		CreateEntryRequestBody body = new CreateEntryRequestBody(null, rdfJson, rdfJson, rdfJson);

		Entry result = service.createReferenceEntry(context, "e1", null,
				URI.create("http://external.example.com/doc"), null,
				URI.create("http://external.example.com/md"), body);

		assertSame(entry, result);
		verify(entry, never()).getLocalMetadata();
		verify(cachedMetadata).setGraph(any());
		verify(entry).setGraph(any());
	}

	@Test
	void createLinkEntry_cachedExternalMetadataBodyIgnored_onlyLocalMetadataAndInfoSet() {
		// setCachedMetadataGraph must no-op for Link entries even when the body carries cachedExternalMetadata.
		when(context.createLink(any(), any(), any())).thenReturn(entry);
		when(entry.getEntryType()).thenReturn(EntryType.Link);
		when(entry.getId()).thenReturn("e1");
		Metadata localMetadata = mock(Metadata.class);
		when(entry.getLocalMetadata()).thenReturn(localMetadata);
		String rdfJson = "{\"http://example.org/1/resource/e1\":"
				+ "{\"http://purl.org/dc/terms/title\":[{\"value\":\"t\",\"type\":\"literal\"}]}}";
		CreateEntryRequestBody body = new CreateEntryRequestBody(null, rdfJson, rdfJson, rdfJson);

		Entry result = service.createLinkEntry(context, "e1", null,
				URI.create("http://external.example.com/doc"), null, body);

		assertSame(entry, result);
		verify(localMetadata).setGraph(any());
		verify(entry, never()).getCachedExternalMetadata();
		verify(entry).setGraph(any());
	}
}
