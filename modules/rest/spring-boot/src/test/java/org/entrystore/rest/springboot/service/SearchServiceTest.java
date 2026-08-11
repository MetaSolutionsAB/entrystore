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

import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.rest.springboot.configuration.SyndicationProperties;
import org.entrystore.rest.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private LoginAttemptService loginAttemptService;

	private final SyndicationProperties syndicationProperties = new SyndicationProperties(Map.of());

	/**
	 * generateJson delegates the per-entry sections and rights to ResourceJsonSerializer, so the
	 * tests use a real serializer (over the same mocks) instead of a mock to keep asserting the
	 * produced JSON.
	 */
	private ResourceJsonSerializer realSerializer() {
		return new ResourceJsonSerializer(principalManager, repositoryManager, loginAttemptService);
	}

	@Test
	void buildRequestUri_redactsSensitiveQueryParameters() throws Exception {
		// Verifies the redactor is wired into buildRequestUri so the Atom/RSS self-link
		// emitted by SearchService.searchFeed cannot echo a sensitive token back into a
		// publicly-visible response body. Regression sentinel for ENTRYSTORE-1009.
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		request.setQueryString("q=hello&confirm=secret-token-abc");

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search?q=hello&confirm=***", uri);
		assertFalse(uri.contains("secret-token-abc"),
				"sensitive token must not appear in the constructed self-link: " + uri);
	}

	@Test
	void buildRequestUri_withNoQueryString_omitsQuestionMark() throws Exception {
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		// no setQueryString — request.getQueryString() returns null

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search", uri);
	}

	@Test
	void buildRequestUri_withPercentEncodedSensitiveName_redactsValue() throws Exception {
		// Phishing-link bypass scenario from the round-1 review (F1): the controller binds
		// %63onfirm to its `confirm` @RequestParam, so the request flow runs with the real
		// token; the self-link must not echo the plaintext value either.
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		var request = new MockHttpServletRequest("GET", "/search");
		request.setServletPath("/search");
		request.setQueryString("%63onfirm=secret-token-abc");

		String uri = service.buildRequestUri(request);

		assertEquals("https://example.test/store/search?%63onfirm=***", uri);
	}

	@Test
	void generateJson_authorizationOnMetadata_flagsNoAccessToMetadata() {
		Entry entry = mockSearchHit("e1");
		when(entry.getLocalMetadata()).thenThrow(new AuthorizationException(null, null, null));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		assertTrue(child.getBoolean("noAccessToMetadata"));
		assertTrue(child.has("info"));
	}

	@Test
	void generateJson_authorizationOnEntryInfo_flagsNoAccessToEntryInfo() {
		Entry entry = mockSearchHit("e1");
		when(entry.getGraph()).thenThrow(new AuthorizationException(null, null, null));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		assertTrue(child.getBoolean("noAccessToEntryInfo"));
	}

	@Test
	void generateJson_authorizationOnRelations_flagsNoAccessToRelations() {
		Entry entry = mockSearchHit("e1");
		when(entry.getRelations()).thenThrow(new AuthorizationException(null, null, null));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		assertTrue(child.getBoolean("noAccessToRelations"));
	}

	@Test
	void generateJson_noRights_omitsRightsKey() {
		// Search children only get a "rights" key when at least one right exists (generateJson only
		// puts the key for a non-empty rights array) — unlike list serialization, which always emits the key.
		Entry entry = mockSearchHit("e1");
		when(principalManager.getRights(entry)).thenReturn(Set.of());
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		assertFalse(child.has("rights"));
	}

	@Test
	void generateJson_withRights_emitsRightsArray() {
		Entry entry = mockSearchHit("e1");
		when(principalManager.getRights(entry)).thenReturn(Set.of(AccessProperty.Administer, AccessProperty.WriteResource));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		JSONArray rights = child.getJSONArray("rights");
		assertEquals(2, rights.length());
		assertEquals(Set.of("administer", "writeresource"), Set.of(rights.getString(0), rights.getString(1)));
	}

	@Test
	void generateJson_withRelations_emitsRelationKey() {
		Entry entry = mockSearchHit("e1");
		when(entry.getRelations()).thenReturn(new LinkedHashModel());
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject child = firstChild(service.generateJson(0, 10, new QueryResultsDto(List.of(entry)), null));

		assertTrue(child.has(RepositoryProperties.RELATION));
	}

	@Test
	void generateSyndication_noUrlTemplateRequested_resolvesTheDefaultTemplate() throws Exception {
		// generateSyndication is the only place that turns a request-supplied template *name* into a
		// template *value*, and Syndication now receives the resolved value. Passing the raw name through,
		// or dropping the null -> "default" step, would emit bare resource URIs as feed links instead.
		var properties = new SyndicationProperties(Map.of("default", "https://example.com/view/{entryid}"));
		when(repositoryManager.getPrincipalManager()).thenReturn(principalManager);
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, properties, realSerializer());

		String feed = service.generateSyndication(new MockHttpServletRequest("GET", "/search"),
				List.of(mockFeedHit("e1")), "rss_2.0", null, 10, null, "Feed");

		assertTrue(feed.contains("https://example.com/view/e1"),
				"the default template must be applied when no urltemplate is requested; got: " + feed);
	}

	@Test
	void generateSyndication_unknownUrlTemplateName_fallsBackToTheResourceUri() throws Exception {
		var properties = new SyndicationProperties(Map.of("default", "https://example.com/view/{entryid}"));
		when(repositoryManager.getPrincipalManager()).thenReturn(principalManager);
		when(repositoryManager.getRepositoryURL()).thenReturn(new URL("https://example.test/store/"));
		var service = new SearchService(repositoryManager, properties, realSerializer());

		String feed = service.generateSyndication(new MockHttpServletRequest("GET", "/search"),
				List.of(mockFeedHit("e1")), "rss_2.0", null, 10, "nonexistent", "Feed");

		assertTrue(feed.contains("http://example.com/ctx/resource/e1"),
				"an unknown template name must not fall back to the default template; got: " + feed);
	}

	/** A search hit carrying the fields feed generation reads, which the JSON assembly does not touch. */
	private Entry mockFeedHit(String id) {
		Entry entry = mockSearchHit(id);
		lenient().when(entry.getMetadataGraph()).thenReturn(new LinkedHashModel());
		lenient().when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/ctx/entry/" + id));
		lenient().when(entry.getResourceURI()).thenReturn(URI.create("http://example.com/ctx/resource/" + id));
		lenient().when(entry.getCreationDate()).thenReturn(new Date(1000));
		lenient().when(entry.getModifiedDate()).thenReturn(new Date(2000));
		return entry;
	}

	private Entry mockSearchHit(String id) {
		Entry entry = mock(Entry.class);
		lenient().when(entry.getId()).thenReturn(id);
		Entry contextEntry = mock(Entry.class);
		lenient().when(contextEntry.getId()).thenReturn("c1");
		Context context = mock(Context.class);
		lenient().when(context.getEntry()).thenReturn(contextEntry);
		lenient().when(entry.getContext()).thenReturn(context);
		lenient().when(entry.getGraphType()).thenReturn(GraphType.None);
		lenient().when(entry.getEntryType()).thenReturn(EntryType.Local);
		Metadata localMetadata = mock(Metadata.class);
		lenient().when(localMetadata.getGraph()).thenReturn(new LinkedHashModel());
		lenient().when(entry.getLocalMetadata()).thenReturn(localMetadata);
		lenient().when(entry.getGraph()).thenReturn(new LinkedHashModel());
		lenient().when(entry.getRelations()).thenReturn(null);
		lenient().when(principalManager.getRights(entry)).thenReturn(Set.of());
		return entry;
	}

	private static JSONObject firstChild(String generatedJson) {
		return new JSONObject(generatedJson)
				.getJSONObject("resource")
				.getJSONArray("children")
				.getJSONObject(0);
	}
}
