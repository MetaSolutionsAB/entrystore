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

package org.entrystore.rest.springboot.configuration;

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.service.ContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IfUnmodifiedSinceInterceptorTest {

	private static final String HEADER_DATE = "Mon, 05 Oct 2026 12:00:00 GMT";

	private final ContextService contextService = mock(ContextService.class);

	private final PrincipalManager principalManager = mock(PrincipalManager.class);

	private final Entry entry = mock(Entry.class);

	private final IfUnmodifiedSinceInterceptor interceptor =
			new IfUnmodifiedSinceInterceptor(contextService, principalManager);

	@ParameterizedTest(name = "entry modified at {0} -> 412: {1}")
	@CsvSource({
			"2026-10-05T11:59:59.000Z, false",
			"2026-10-05T12:00:00.000Z, false",
			"2026-10-05T12:00:00.999Z, false",
			"2026-10-05T12:00:01.000Z, true",
			"2026-10-05T13:00:00.000Z, true"
	})
	void comparesAtSecondPrecision(String modified, boolean preconditionFails) {
		givenEntryModifiedAt(modified);
		MockHttpServletRequest request = writeRequest("PUT", "/{context-id}/metadata/{entry-id}");

		if (preconditionFails) {
			CustomResponseException e = assertThrows(CustomResponseException.class,
					() -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
			assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatus());
		} else {
			assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
		}
	}

	@ParameterizedTest(name = "entry modified at {0}, cache at {1} -> 412: {2}")
	@CsvSource({
			"2026-10-05T12:00:00.500Z, 2026-10-05T13:00:00.000Z, false",
			"2026-10-05T13:00:00.000Z, , false",
			"2026-10-05T13:00:00.000Z, 2026-10-05T11:00:00.000Z, false",
			"2026-10-05T13:00:00.000Z, 2026-10-05T12:00:01.000Z, true",
			"2026-10-05T11:00:00.000Z, 2026-10-05T13:00:00.000Z, true"
	})
	void cachedExternalMetadataComparesWithTheCacheDateUnlessTheHeaderIsTheEntryDate(String modified, String cached,
			boolean preconditionFails) {
		givenEntryModifiedAt(modified);
		when(entry.getExternalMetadataCacheDate()).thenReturn(cached == null ? null : Date.from(Instant.parse(cached)));
		MockHttpServletRequest request = writeRequest("PUT", "/{context-id}/{type}/{entry-id}");
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
				Map.of("context-id", "ctx", "entry-id", "1", "type", "cached-external-metadata"));

		if (preconditionFails) {
			assertThrows(CustomResponseException.class,
					() -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
		} else {
			assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
		}
	}

	@Test
	void resourceRouteChecksReadResourceBeforeThePrecondition() {
		givenEntryModifiedAt("2026-10-05T13:00:00.000Z");
		MockHttpServletRequest request = writeRequest("DELETE", "/{context-id}/resource/{entry-id}");

		assertThrows(CustomResponseException.class,
				() -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
		verify(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
	}

	@Test
	void metadataRouteChecksReadMetadataBeforeThePrecondition() {
		givenEntryModifiedAt("2026-10-05T13:00:00.000Z");
		MockHttpServletRequest request = writeRequest("PUT", "/{context-id}/entry/{entry-id}");

		assertThrows(CustomResponseException.class,
				() -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
		verify(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
	}

	@Test
	void getIsNotChecked() {
		givenEntryModifiedAt("2026-10-05T13:00:00.000Z");
		MockHttpServletRequest request = writeRequest("GET", "/{context-id}/metadata/{entry-id}");

		assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
		verifyNoInteractions(contextService, principalManager);
	}

	@Test
	void unknownEntryIsLeftToTheController() {
		when(contextService.getContext("ctx")).thenReturn(mock(Context.class));
		MockHttpServletRequest request = writeRequest("PUT", "/{context-id}/metadata/{entry-id}");

		assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
		verifyNoInteractions(principalManager);
	}

	private void givenEntryModifiedAt(String modified) {
		Context context = mock(Context.class);
		when(contextService.getContext("ctx")).thenReturn(context);
		when(context.get("1")).thenReturn(entry);
		when(entry.getModifiedDate()).thenReturn(Date.from(Instant.parse(modified)));
	}

	private static MockHttpServletRequest writeRequest(String method, String pattern) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/ctx/x/1");
		request.addHeader("If-Unmodified-Since", HEADER_DATE);
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
				Map.of("context-id", "ctx", "entry-id", "1"));
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
		return request;
	}
}
