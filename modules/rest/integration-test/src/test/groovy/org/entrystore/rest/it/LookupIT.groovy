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

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

class LookupIT extends BaseSpec {

	def static contextId = '700'
	def static resourceUrl = 'https://example.org/lookup-test-resource'
	def static entryId

	def setupSpec() {
		getOrCreateContext([contextId: contextId])

		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def newMetadataIri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/_newId'

		def params = [entrytype: 'link', resource: resourceUrl]
		def body = createTitleMetadataBody(newResourceIri, 'Lookup Test Entry')
		// Grant guest ReadMetadata so entry is indexed with public:true in Solr
		body.info = [(newMetadataIri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
		entryId = createEntry(contextId, params, body)
		waitForSolrProcessing()
		// Solr commits asynchronously (commitWithin=1000ms) — poll the *global* lookup until the entry
		// is visible: only the global endpoint is Solr-backed (context-scoped lookup resolves directly
		// against the repository and would pass before the commit).
		def lookupPath = '/lookup' + convertMapToQueryParams([uri: resourceUrl])
		await()
			.pollInterval(100, TimeUnit.MILLISECONDS)
			.atMost(10, TimeUnit.SECONDS)
			.until { EntryStoreClient.getRequest(lookupPath, 'admin', 'application/json').getResponseCode() == HTTP_OK }
	}

	// --- Context-scoped lookup: happy path ---

	def "GET /{context-id}/lookup with valid URI should return metadata with correct content"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text)
		(responseJson as Map).keySet().size() == 1
		def resourceKey = (responseJson as Map).keySet()[0].toString()
		responseJson[resourceKey][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = responseJson[resourceKey][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Lookup Test Entry'
	}

	def "GET /{context-id}/lookup with valid URI should return Last-Modified and ETag headers"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Last-Modified') != null
		conn.getHeaderField('ETag') != null
		conn.getHeaderField('ETag').startsWith('"')
	}

	def "GET /{context-id}/lookup with Accept text/turtle should return Turtle format"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'text/turtle')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('text/turtle')
	}

	def "GET /{context-id}/lookup with format param should override Accept header"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl, format: 'text/turtle'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('text/turtle')
	}

	def "GET /{context-id}/lookup with scope=local should return local metadata for Link entry"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl, scope: 'local'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text)
		(responseJson as Map).keySet().size() == 1
		def resourceKey = (responseJson as Map).keySet()[0].toString()
		responseJson[resourceKey][NameSpaceConst.DC_TERM_TITLE] != null
	}

	def "GET /{context-id}/lookup with scope=external for Link entry should return empty graph"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl, scope: 'external'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text)
		(responseJson as Map).keySet().size() == 0
	}

	// --- Context-scoped lookup: error cases ---

	def "GET /{context-id}/lookup with non-existing URI should return 404"() {
		given:
		def queryParams = convertMapToQueryParams([uri: 'https://non-existing.example.org/nothing'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/lookup on non-existing context should return 404"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/nonExistingCtx999/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/lookup with invalid scope should return 400"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl, scope: 'invalid'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /{context-id}/lookup without uri param should return 400"() {
		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup', 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// --- Global lookup: happy path ---

	def "GET /lookup with valid public URI should return metadata"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text)
		(responseJson as Map).keySet().size() == 1
		def resourceKey = (responseJson as Map).keySet()[0].toString()
		responseJson[resourceKey][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = responseJson[resourceKey][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['value'] == 'Lookup Test Entry'
	}

	def "GET /lookup as guest with valid public URI should return metadata"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text)
		(responseJson as Map).keySet().size() == 1
	}

	// --- Global lookup: error cases ---

	def "GET /lookup with non-existing URI should return 404"() {
		given:
		def queryParams = convertMapToQueryParams([uri: 'https://non-existing.example.org/global-nothing'])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /lookup with invalid scope should return 400"() {
		given:
		def queryParams = convertMapToQueryParams([uri: resourceUrl, scope: 'bogus'])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /lookup without uri param should return 400"() {
		when:
		def conn = EntryStoreClient.getRequest('/lookup', 'admin', 'application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}
}
