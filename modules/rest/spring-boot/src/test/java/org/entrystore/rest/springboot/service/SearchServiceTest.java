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

import org.apache.solr.client.solrj.request.SolrQuery;
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
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.configuration.SyndicationProperties;
import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.dto.FacetValueDto;
import org.entrystore.rest.springboot.model.dto.FacetValuesDto;
import org.entrystore.rest.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
	 * generateJson delegates the per-entry sections and rights to ResourceSerializationService, so the
	 * tests use a real serializer (over the same mocks) instead of a mock to keep asserting the
	 * produced JSON.
	 */
	private ResourceSerializationService realSerializer() {
		return new ResourceSerializationService(principalManager, repositoryManager, loginAttemptService);
	}

	@ParameterizedTest(name = "limit {0} clamps to {1}")
	@CsvSource({
			"150, 100", // above the configured maximum — capped at solrMaxLimit
			"100, 100", // exactly the maximum — unchanged
			"42, 42",   // in range — unchanged
			"0, 0",     // 0 is allowed on purpose: enables count-only requests
			"-1, 50",   // negative — falls back to the default page size
	})
	void clampLimit_clampsToConfiguredBounds(int requested, int expected) {
		var service = new SearchService(null, null, null);
		// @Value-injected field — set via reflection since there is no Spring context here; mirrors the default.
		ReflectionTestUtils.setField(service, "solrMaxLimit", 100);

		assertEquals(expected, service.clampLimit(requested));
	}

	@Test
	void solrMaxLimit_bindsFromTheConfiguredProperty() {
		// Pins the placeholder key itself, which the reflection-set field above cannot: a misspelt
		// @Value key would leave every case above green while an operator's entrystore.solr.max-limit
		// was silently ignored and results stayed capped at the default.
		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(SearchService.class, () -> new SearchService(null, null, null))
				.withPropertyValues("entrystore.solr.max-limit=7")
				.run(context -> assertEquals(7, context.getBean(SearchService.class).clampLimit(150)));
	}

	@Test
	void solrMaxFacetLimit_bindsFromTheConfiguredProperty() {
		// Same key-pinning rationale as above, observed through the facet settings the cap is applied to.
		var request = new FacetSettingsRequestParams();
		request.setFacetLimit(5000);

		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(SearchService.class, () -> new SearchService(null, null, null))
				.withPropertyValues("entrystore.solr.facet-max-limit=7")
				.run(context -> assertEquals(7, context.getBean(SearchService.class).toFacetSettings(request).limit));
	}

	@Test
	void toFacetSettings_withoutFacetLimit_usesDefaultFacetLimit() {
		var service = new SearchService(null, null, null);
		ReflectionTestUtils.setField(service, "solrMaxFacetLimit", 1000);

		assertEquals(100, service.toFacetSettings(new FacetSettingsRequestParams()).limit);
	}

	@Test
	void findEntriesSolr_clampsRowsToConfiguredMaximum() {
		// The cap must hold inside the service, not only for callers that remembered clampLimit().
		SolrSearchIndex solrIndex = mock(SolrSearchIndex.class);
		when(repositoryManager.getIndex()).thenReturn(solrIndex);
		when(solrIndex.sendQuery(any())).thenReturn(new QueryResult(Set.of(), 0, List.of()));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());
		ReflectionTestUtils.setField(service, "solrMaxLimit", 100);

		service.findEntriesSolr("q", null, 0, 5000, List.of(), new SolrSearchIndex.FacetSettings());

		ArgumentCaptor<SolrQuery> query = ArgumentCaptor.forClass(SolrQuery.class);
		verify(solrIndex).sendQuery(query.capture());
		assertEquals(100, query.getValue().getRows());
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

	@Test
	void findEntriesSolr_literalFacet_addsTheUnlimitedCompanionField() {
		SolrSearchIndex index = mock(SolrSearchIndex.class);
		when(repositoryManager.getIndex()).thenReturn(index);
		when(index.sendQuery(any(SolrQuery.class))).thenReturn(new QueryResult(Set.of(), 0, List.of()));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		service.findEntriesSolr("*:*", null, 0, 10, List.of(),
				facetSettings("rdfType,metadata.predicate.literal_s.abc12345", null, "sv"));

		SolrQuery query = capturedQuery(index);
		assertEquals(List.of("rdfType", "metadata.predicate.literal_s.abc12345", "metadata.predicate.literal_l.abc12345"),
				List.of(query.getFacetFields()));
		assertEquals("-1", query.get("f.metadata.predicate.literal_l.abc12345.facet.limit"));
		assertEquals("-1", query.get("f.metadata.predicate.literal_s.abc12345.facet.limit"),
				"with facetLang the client field is unlimited so the top-N is taken after the language filter");
	}

	@Test
	void generateJson_literalFacet_carriesLangArraysPerBucket() {
		var facet = new FacetValuesDto("metadata.predicate.literal_s.abc12345", List.of(
				new FacetValueDto("Sverige", 2, List.of("nb", "sv")),
				new FacetValueDto("Stockholm", 1, List.of()),
				new FacetValueDto(null, 4, List.of())));
		var service = new SearchService(repositoryManager, syndicationProperties, realSerializer());

		JSONObject facetJson = firstFacetField(service.generateJson(0, 10, new QueryResultsDto(List.of(), 0, List.of(facet)), null));

		assertEquals("metadata.predicate.literal_s.abc12345", facetJson.getString("name"));
		assertEquals(3, facetJson.getInt("valueCount"));
		JSONObject sverige = facetJson.getJSONArray("values").getJSONObject(0);
		assertEquals("Sverige", sverige.getString("name"));
		assertEquals(2, sverige.getLong("count"));
		assertEquals(List.of("nb", "sv"), sverige.getJSONArray("lang").toList());
		JSONObject stockholm = facetJson.getJSONArray("values").getJSONObject(1);
		assertFalse(stockholm.has("lang"), "an untagged-only label carries no lang array");
		JSONObject missing = facetJson.getJSONArray("values").getJSONObject(2);
		assertFalse(missing.has("name"), "the facet.missing bucket stays nameless");
		assertEquals(4, missing.getLong("count"));
	}

	private static SolrSearchIndex.FacetSettings facetSettings(String fields, String matches, String lang) {
		var settings = new SolrSearchIndex.FacetSettings();
		settings.fields = fields;
		settings.matches = matches;
		settings.lang = lang;
		settings.minCount = 1;
		settings.limit = 100;
		return settings;
	}

	private static SolrQuery capturedQuery(SolrSearchIndex index) {
		ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
		verify(index).sendQuery(captor.capture());
		return captor.getValue();
	}

	private static JSONObject firstFacetField(String generatedJson) {
		return new JSONObject(generatedJson).getJSONArray("facetFields").getJSONObject(0);
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
