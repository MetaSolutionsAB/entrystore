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

package org.entrystore.rest.springboot.controller;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.rest.springboot.model.api.GetEntryResponse;
import org.entrystore.rest.springboot.service.EntryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EntryControllerTest {

	private static final Date MODIFIED = new Date(1_700_000_000_123L);

	private final EntryService entryService = mock(EntryService.class);
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final EntryController controller = new EntryController(entryService, objectMapper);

	@Test
	void headEntry_answersWithTheValidatorsOfTheModificationDate_withoutSerializingTheEntry() throws Exception {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);
		var request = new MockHttpServletRequest("HEAD", "/1/entry/2");
		request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

		ResponseEntity<Void> response = controller.headEntry("1", "2", request, new MockHttpServletResponse());

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertNull(response.getBody());
		assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
		assertEquals("\"1700000000123\"", response.getHeaders().getETag());
		assertEquals(1_700_000_000_000L, response.getHeaders().getLastModified());
		assertEquals(List.of(HttpHeaders.ACCEPT, HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION),
				response.getHeaders().getVary());
		verify(entryService).getEntryByContextIdAndEntryId("1", "2");
		verifyNoMoreInteractions(entryService);
	}

	@Test
	void headEntry_withoutAcceptHeader_answersWithTheRdfXmlContentTypeOfTheGet() throws Exception {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);

		ResponseEntity<Void> response = controller.headEntry("1", "2", new MockHttpServletRequest("HEAD", "/1/entry/2"),
				new MockHttpServletResponse());

		assertEquals(MediaType.valueOf("application/rdf+xml"), response.getHeaders().getContentType());
	}

	@Test
	void headEntry_withAJsonFormatCarryingParameters_answersAsTheJsonGetDoes() throws Exception {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);
		var request = new MockHttpServletRequest("HEAD", "/1/entry/2");
		request.setParameter("format", "application/json;charset=UTF-8");

		ResponseEntity<Void> response = controller.headEntry("1", "2", request, new MockHttpServletResponse());

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
	}

	@Test
	void getEntryInJsonFormat_withIncludeAll_computesTheETagFromTheJsonSent() {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);
		when(entryService.getEntryInJsonFormat(entry, null, true, null))
				.thenReturn(GetEntryResponse.builder().entryId("2").build());

		ResponseEntity<byte[]> response = controller.getEntryInJsonFormat("1", "2", null, "", null,
				new MockHttpServletResponse());

		assertNotNull(response.getBody());
		assertEquals("\"" + DigestUtils.md5DigestAsHex(response.getBody()) + "\"", response.getHeaders().getETag());
		assertEquals(1_700_000_000_000L, response.getHeaders().getLastModified());
		assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
	}

	@Test
	void getEntryInJsonFormat_ofAContext_computesTheETagFromTheJsonSent() {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entry.getGraphType()).thenReturn(GraphType.Context);
		when(entryService.getEntryByContextIdAndEntryId("_contexts", "2")).thenReturn(entry);
		when(entryService.getEntryInJsonFormat(entry, null, false, null))
				.thenReturn(GetEntryResponse.builder().entryId("2").build());

		ResponseEntity<byte[]> response = controller.getEntryInJsonFormat("_contexts", "2", null, null, null,
				new MockHttpServletResponse());

		assertEquals("\"" + DigestUtils.md5DigestAsHex(response.getBody()) + "\"", response.getHeaders().getETag());
	}

	@Test
	void getEntryInJsonFormat_withoutIncludeAll_usesTheModificationDateAsETag() {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);
		when(entryService.getEntryInJsonFormat(entry, null, false, null))
				.thenReturn(GetEntryResponse.builder().entryId("2").build());

		ResponseEntity<byte[]> response = controller.getEntryInJsonFormat("1", "2", null, null, null,
				new MockHttpServletResponse());

		assertEquals("\"1700000000123\"", response.getHeaders().getETag());
	}

	@Test
	void headEntry_withIncludeAllAsJson_omitsTheETagThatTheGetComputesFromTheJson() throws Exception {
		Entry entry = mock(Entry.class);
		when(entry.getModifiedDate()).thenReturn(MODIFIED);
		when(entryService.getEntryByContextIdAndEntryId("1", "2")).thenReturn(entry);
		var request = new MockHttpServletRequest("HEAD", "/1/entry/2");
		request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		request.setParameter("includeAll", "");

		ResponseEntity<Void> response = controller.headEntry("1", "2", request, new MockHttpServletResponse());

		assertNull(response.getHeaders().getETag());
		assertEquals(1_700_000_000_000L, response.getHeaders().getLastModified());
		verify(entryService).getEntryByContextIdAndEntryId("1", "2");
		verifyNoMoreInteractions(entryService);
	}
}
