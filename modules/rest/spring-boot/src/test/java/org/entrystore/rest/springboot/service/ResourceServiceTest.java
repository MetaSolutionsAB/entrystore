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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.StringResource;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.api.ResourceQuery;
import org.entrystore.rest.springboot.model.dto.CompletionState;
import org.entrystore.rest.springboot.model.dto.RenderedFeed;
import org.entrystore.rest.springboot.model.dto.ResourceRepresentation;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.util.RDFJSON;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

	@Mock
	private ResourceJsonSerializer resourceSerializer;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private SyndicationService syndicationService;

	@Mock
	private ListResourceService listResourceService;

	@Mock
	private FileResourceService fileResourceService;

	@Mock
	private UserService userService;

	@Mock
	private ProxyService proxyService;

	@Mock
	private Entry entry;

	private ResourceService service;

	@BeforeEach
	void setUp() {
		service = new ResourceService(resourceSerializer, principalManager, syndicationService, listResourceService,
				fileResourceService, userService, proxyService);
	}

	@Test
	void getResourceRepresentation_localNoneWithDataFile_returnsFileDownloadNamedAfterEntryId() {
		File dataFile = new File("payload.bin");
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.None);
		when(fileResourceService.dataFileForDownload(entry)).thenReturn(dataFile);
		when(fileResourceService.mediaTypeForDownload(entry)).thenReturn(MediaType.IMAGE_PNG);
		when(entry.getFilename()).thenReturn(null);
		when(entry.getId()).thenReturn("42");
		when(resourceSerializer.readDigest(entry)).thenReturn("ab12");

		ResourceRepresentation result = service.getResourceRepresentation(entry, plainQuery());

		var download = assertInstanceOf(ResourceRepresentation.FileDownload.class, result);
		assertSame(dataFile, download.file());
		assertEquals(MediaType.IMAGE_PNG, download.mediaType());
		// No stored filename, so the entry id names the download.
		assertEquals("42", download.filename());
		assertEquals("ab12", download.sha256Digest());
	}

	@Test
	void getResourceRepresentation_localNoneWithoutDataFile_returnsEmpty() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.None);
		when(fileResourceService.dataFileForDownload(entry)).thenReturn(null);

		ResourceRepresentation result = service.getResourceRepresentation(entry, plainQuery());

		assertInstanceOf(ResourceRepresentation.Empty.class, result);
	}

	@Test
	void getResourceRepresentation_localString_returnsTextPlainBody() {
		StringResource resource = mock(StringResource.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.String);
		when(entry.getResource()).thenReturn(resource);
		when(resourceSerializer.serializeResourceString(resource)).thenReturn("hello");

		ResourceRepresentation result = service.getResourceRepresentation(entry, plainQuery());

		assertEquals(new ResourceRepresentation.TextBody("hello", MediaType.TEXT_PLAIN), result);
	}

	@Test
	void getResourceRepresentation_localStringWithoutText_returnsEmptyTextPlainBody() {
		// StringResource.getString() is null when no rdf:value is stored; the controller used to answer 200 with an
		// empty body for that, so the sealed type must not turn it into a 500.
		StringResource resource = mock(StringResource.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.String);
		when(entry.getResource()).thenReturn(resource);
		when(resourceSerializer.serializeResourceString(resource)).thenReturn(null);

		ResourceRepresentation result = service.getResourceRepresentation(entry, plainQuery());

		assertEquals(new ResourceRepresentation.TextBody("", MediaType.TEXT_PLAIN), result);
	}

	@Test
	void getResourceRepresentation_syndicationRequested_delegatesBeforeInspectingEntry() {
		when(syndicationService.renderFeed(entry, "rss_2.0", "sv", 7))
				.thenReturn(new RenderedFeed("<rss/>", MediaType.APPLICATION_RSS_XML));
		var query = new ResourceQuery(null, "application/rdf+xml", "rss_2.0", "sv", 7, emptyListFilter());

		ResourceRepresentation result = service.getResourceRepresentation(entry, query);

		assertEquals(new ResourceRepresentation.TextBody("<rss/>", MediaType.APPLICATION_RSS_XML), result);
		// Syndication is decided before the entry's type: a feed on a list or context must not fall into the
		// list/graph branch.
		verify(entry, never()).getEntryType();
		verify(entry, never()).getGraphType();
	}

	@Test
	void getResourceRepresentation_listWithTurtleRdfFormat_returnsTurtleMediaType() {
		org.entrystore.List list = mock(org.entrystore.List.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		when(list.getGraph()).thenReturn(new LinkedHashModel());
		var query = new ResourceQuery(MediaType.parseMediaType("text/turtle"), "application/rdf+xml", null, "en", 50,
				emptyListFilter());

		ResourceRepresentation result = service.getResourceRepresentation(entry, query);

		ResourceRepresentation.TextBody body = assertInstanceOf(ResourceRepresentation.TextBody.class, result);
		assertEquals(MediaType.parseMediaType("text/turtle"), body.mediaType());
	}

	@Test
	void serializeResourceAsJson_listAsJson_delegatesToListResourceService() {
		// The JSON id array is the one list representation that is not a plain graph serialization.
		org.entrystore.List list = mock(org.entrystore.List.class);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		when(list.getGraph()).thenReturn(new LinkedHashModel());
		ListFilter filter = emptyListFilter();
		when(listResourceService.serializeChildrenIds(entry, filter)).thenReturn("[\"a\"]");

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		assertEquals("[\"a\"]", result);
	}

	// The isList && application/json short-circuit must not swallow a non-list Graph resource: that case goes
	// to GraphUtil.serializeGraph, which routes application/json through RDFJSON. Before ENTRYSTORE-1091 the
	// service called RDFJSON.graphToRdfJson directly here, so nothing pinned the rerouted arm.
	@Test
	void serializeResourceAsJson_graphResourceAsJson_returnsRdfJsonNotIdArray() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI("http://example.com/s"), vf.createIRI("http://purl.org/dc/terms/title"),
				vf.createLiteral("Sample"));
		RDFResource resource = mock(RDFResource.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.Graph);
		when(entry.getResource()).thenReturn(resource);
		when(resource.getGraph()).thenReturn(graph);

		String result = service.serializeResourceAsJson(entry, "application/json", emptyListFilter());

		// Routing: RDF/JSON, not the id array the isList branch would have produced.
		assertEquals(RDFJSON.graphToRdfJson(graph), result);
		// Content, asserted independently of RDFJSON: were graphToRdfJson to regress to an empty object,
		// both sides of the equality above would move together and still match.
		assertTrue(result.contains("http://purl.org/dc/terms/title"),
				"Expected the predicate IRI in the RDF/JSON output");
		assertTrue(result.contains("Sample"), "Expected the literal value in the RDF/JSON output");
		verifyNoInteractions(listResourceService);
	}

	@Test
	void setEntryResource_stringResource_updatesModificationDate() {
		StringResource resource = mock(StringResource.class);
		when(entry.getGraphType()).thenReturn(GraphType.String);
		when(entry.getResource()).thenReturn(resource);

		CompletionState state = service.setEntryResource(entry, "text".getBytes(StandardCharsets.UTF_8),
				"text/plain", null, null, "session-1");

		assertEquals(CompletionState.UPDATED, state);
		verify(resource).setString("text");
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResource_listAsJson_delegatesToListResourceServiceAndUpdatesModificationDate() {
		byte[] body = "[\"a\"]".getBytes(StandardCharsets.UTF_8);
		when(entry.getGraphType()).thenReturn(GraphType.List);

		CompletionState state = service.setEntryResource(entry, body, "application/json", null, null, "session-1");

		assertEquals(CompletionState.UPDATED, state);
		verify(listResourceService).setChildrenFromJson(entry, body);
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResource_groupAsTurtle_delegatesToGraphUpdate() {
		byte[] body = "<a> <b> <c> .".getBytes(StandardCharsets.UTF_8);
		when(entry.getGraphType()).thenReturn(GraphType.Group);

		CompletionState state = service.setEntryResource(entry, body, "text/turtle", null, null, "session-1");

		assertEquals(CompletionState.UPDATED, state);
		verify(listResourceService).setGraph(entry, body, "text/turtle");
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResource_binaryBody_delegatesToFileResourceServiceAndAnswersCreated() {
		byte[] body = {1, 2, 3};
		when(entry.getGraphType()).thenReturn(GraphType.None);

		CompletionState state = service.setEntryResource(entry, body, "image/png", null, "a.png", "session-1");

		// A binary upload is the one PUT that answers 201.
		assertEquals(CompletionState.CREATED, state);
		verify(fileResourceService).setData(entry, body, "image/png", null, "a.png");
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResource_user_delegatesToUserServiceWithSessionId() {
		byte[] body = "{\"language\":\"sv\"}".getBytes(StandardCharsets.UTF_8);
		when(entry.getGraphType()).thenReturn(GraphType.User);

		CompletionState state = service.setEntryResource(entry, body, "application/json", null, null, "session-1");

		assertEquals(CompletionState.UPDATED, state);
		verify(userService).updateSettings(entry, body, "session-1");
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResource_unsupportedGraphType_returnsErrorWithoutUpdatingModificationDate() {
		// Context has no branch in the resource update, so it falls through to ERROR and must not look modified.
		when(entry.getGraphType()).thenReturn(GraphType.Context);

		CompletionState state = service.setEntryResource(entry, new byte[0], "application/json", null, null,
				"session-1");

		assertEquals(CompletionState.ERROR, state);
		verify(entry, never()).updateModificationDate();
	}

	@Test
	void setEntryResourceMultipart_delegatesAndUpdatesModificationDate() {
		MultipartFile file = mock(MultipartFile.class);

		CompletionState state = service.setEntryResourceMultipart(entry, file, "image/png");

		assertEquals(CompletionState.CREATED, state);
		verify(fileResourceService).setDataMultipart(entry, file, "image/png");
		verify(entry).updateModificationDate();
	}

	@Test
	void setEntryResourceMultipart_rejectedUpload_doesNotUpdateModificationDate() {
		// The date bump must stay behind the delegate call: a rejected upload leaves the entry untouched.
		MultipartFile file = mock(MultipartFile.class);
		doThrow(new BadRequestException("rejected")).when(fileResourceService).setDataMultipart(entry, file, null);

		assertThrows(BadRequestException.class, () -> service.setEntryResourceMultipart(entry, file, null));

		verify(entry, never()).updateModificationDate();
	}

	@Test
	void deleteResource_proxyTrueUnauthorized_throwsBeforeOutboundDelete() {
		// Pins the contract that the WriteResource check fires BEFORE the proxy branch
		// reaches the outbound DELETE — otherwise an unauthorized caller could trigger it.
		doThrow(new AuthorizationException(null, null, AccessProperty.WriteResource))
				.when(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		assertThrows(AuthorizationException.class,
				() -> service.deleteResource(entry, "true", false));

		verifyNoInteractions(proxyService);
	}

	@Test
	void deleteResource_proxyTrueOnLink_deletesRemoteUrlOnly() {
		assertProxyDeleteForEntryType(EntryType.Link);
	}

	@Test
	void deleteResource_proxyTrueOnReference_deletesRemoteUrlOnly() {
		assertProxyDeleteForEntryType(EntryType.Reference);
	}

	@Test
	void deleteResource_proxyTrueOnLinkReference_deletesRemoteUrlOnly() {
		assertProxyDeleteForEntryType(EntryType.LinkReference);
	}

	@Test
	void deleteResource_proxyTrueOnLocalEntry_ignoresProxyAndDeletesLocally() {
		// proxy=true only means something for non-local entries; a local entry must never trigger an outbound DELETE.
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.None);

		service.deleteResource(entry, "true", false);

		verify(fileResourceService).deleteData(entry);
		verifyNoInteractions(proxyService);
	}

	private void assertProxyDeleteForEntryType(EntryType entryType) {
		when(entry.getEntryType()).thenReturn(entryType);
		when(entry.getResourceURI()).thenReturn(URI.create("http://example.org/x"));

		service.deleteResource(entry, "true", false);

		verify(proxyService).deleteUrl("http://example.org/x");
		verifyNoInteractions(listResourceService, fileResourceService);
	}

	@Test
	void deleteResource_localList_delegatesRecursiveFlag() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.List);

		service.deleteResource(entry, null, true);

		verify(listResourceService).deleteChildren(entry, true);
		verifyNoInteractions(proxyService, fileResourceService);
	}

	@Test
	void deleteResource_localBinary_delegatesToFileResourceService() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.None);

		service.deleteResource(entry, null, false);

		verify(fileResourceService).deleteData(entry);
		verifyNoInteractions(proxyService, listResourceService);
	}

	private static ResourceQuery plainQuery() {
		return new ResourceQuery(null, "application/rdf+xml", null, "en", 50, emptyListFilter());
	}

	private static ListFilter emptyListFilter() {
		return new ListFilter(null, null, null, null, null, null, null);
	}
}
