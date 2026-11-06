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

package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_OK

/**
 * Facets, facet counts and the result count of a search must not reveal anything about entries the caller cannot
 * read (ENTRYSTORE-1120). A public and a private entry share a marker, a label and a language; only the private one
 * has a value under a further predicate. Guest and a user who is not a member of the context may see the public
 * entry only, while the admin keeps seeing both.
 */
class SearchReadAccessIT extends BaseSpec {

	static final String CONTEXT = 'search-read-access'
	static final String PUBLIC_ID = 'readAccessPublic'
	static final String PRIVATE_ID = 'readAccessPrivate'
	static final String PUBLIC_URI = EntryStoreClient.baseUrl + '/' + CONTEXT + '/entry/' + PUBLIC_ID

	static final String MARKER_IRI = 'http://example.org/ns/searchReadAccessIT-marker'
	static final String MARKER_FIELD = literalField(MARKER_IRI)
	static final String MARKER = 'readaccessmarker'
	static final String LABEL_IRI = 'http://example.org/ns/searchReadAccessIT-label'
	static final String LABEL_FIELD = literalField(LABEL_IRI)
	static final String PRIVATE_ONLY_IRI = 'http://example.org/ns/searchReadAccessIT-private-only'
	static final String PRIVATE_ONLY_FIELD = literalField(PRIVATE_ONLY_IRI)

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT])
		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT + '/resource/_newId'
		def metadataIri = EntryStoreClient.baseUrl + '/' + CONTEXT + '/metadata/_newId'
		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		// created first, so sorting by modified date ascending puts the public entry on the first page
		getOrCreateEntry(CONTEXT, [id: PUBLIC_ID, graphtype: 'string'], [resource: 'public',
			metadata: [(resourceIri): [
				(MARKER_IRI): [[type: 'literal', value: MARKER]],
				(LABEL_IRI) : [[type: 'literal', value: 'readaccesssharedlabel', lang: 'en'],
							   [type: 'literal', value: 'readaccesspubliclabel', lang: 'en']]]],
			info    : [(metadataIri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]])
		// readable by the owner and the members of the context's group only
		getOrCreateEntry(CONTEXT, [id: PRIVATE_ID, graphtype: 'string'], [resource: 'private',
			metadata: [(resourceIri): [
				(MARKER_IRI)      : [[type: 'literal', value: MARKER]],
				(LABEL_IRI)       : [[type: 'literal', value: 'readaccesssharedlabel', lang: 'en'],
									 [type: 'literal', value: 'readaccessprivatelabel', lang: 'en']],
				(PRIVATE_ONLY_IRI): [[type: 'literal', value: 'readaccessprivateonly']]]]])
		awaitAdminSearchCount(MARKER_FIELD + ':' + MARKER, 2)
	}

	def "GET /search?type=solr with limit=0 and #facetParams as #callerName should facet only the public entry"() {
		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: MARKER_FIELD + ':' + MARKER, limit: '0'] + facetParams), caller)

		then:
		conn.getResponseCode() == HTTP_OK
		bucketsOf(JSON_PARSER.parseText(conn.inputStream.text), facetParams.facetFields) == buckets

		where:
		caller | facetParams                                                        || buckets
		''     | [facetFields: 'uri']                                               || [(PUBLIC_URI): 1]
		// facetMinCount=0 counts as 1, or Solr would list the private label with count 0
		''     | [facetFields: LABEL_FIELD, facetMinCount: '0']                     || [readaccesspubliclabel: 1, readaccesssharedlabel: 1]
		''     | [facetFields: PRIVATE_ONLY_FIELD, facetMissing: 'true']            || [(null): 1]
		''     | [facetFields: LABEL_FIELD, facetMatches: 'readaccessprivatelabel'] || [:]
		// facetLang counts its candidate labels in a request of its own
		''     | [facetFields: LABEL_FIELD, facetLang: 'en']                        || [readaccesspubliclabel: 1, readaccesssharedlabel: 1]
		''     | [facetFields: MARKER_FIELD]                                        || [(MARKER): 1]
		'user' | [facetFields: 'uri']                                               || [(PUBLIC_URI): 1]
		'user' | [facetFields: LABEL_FIELD, facetMinCount: '0']                     || [readaccesspubliclabel: 1, readaccesssharedlabel: 1]
		'user' | [facetFields: PRIVATE_ONLY_FIELD, facetMissing: 'true']            || [(null): 1]
		'user' | [facetFields: LABEL_FIELD, facetMatches: 'readaccessprivatelabel'] || [:]
		'user' | [facetFields: LABEL_FIELD, facetLang: 'en']                        || [readaccesspubliclabel: 1, readaccesssharedlabel: 1]
		'user' | [facetFields: MARKER_FIELD]                                        || [(MARKER): 1]
		callerName = caller ?: 'guest'
	}

	def "GET /search?type=solr with limit=1 as #callerName should return and count only the public entry"() {
		when:
		// the public entry fills the page, so without the filter the private hit would never be fetched and checked
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: MARKER_FIELD + ':' + MARKER, limit: '1', sort: 'modified asc']), caller)

		then:
		conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] == 1
		respJson['resource']['children'].collect { it['entryId'] } == [PUBLIC_ID]

		where:
		caller << ['', 'user']
		callerName = caller ?: 'guest'
	}

	def "GET /search?type=solr as admin should still find, count and facet both entries"() {
		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: MARKER_FIELD + ':' + MARKER, limit: '10', facetFields: LABEL_FIELD, facetLang: 'en']))

		then:
		conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] == 2
		bucketsOf(respJson, LABEL_FIELD) ==
			[readaccesssharedlabel: 2, readaccesspubliclabel: 1, readaccessprivatelabel: 1]
	}
}
