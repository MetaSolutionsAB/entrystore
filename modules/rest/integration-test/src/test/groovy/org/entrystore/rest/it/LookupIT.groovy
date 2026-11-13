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
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static org.awaitility.Awaitility.await

class LookupIT extends BaseSpec {

	def static contextId = '700'
	def static resourceUrl = 'https://example.org/lookup-test-resource'
	def static entryId
	def static ownedResourceUrl = EntryStoreClient.baseUrl + '/' + contextId + '/resource/lookupZOwner'
	def static sharedResourceUrl = 'https://example.org/lookup-shared'
	def static publicOwnedResourceUrl = EntryStoreClient.baseUrl + '/' + contextId + '/resource/lookupZPublicOwner'
	def static publicSharedResourceUrl = 'https://example.org/lookup-public-shared'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])

		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def newMetadataIri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/_newId'

		def params = [entrytype: 'link', resource: resourceUrl]
		def body = createTitleMetadataBody(newResourceIri, 'Lookup Test Entry')
		// Grant guest ReadMetadata so entry is indexed with public:true in Solr
		body.info = guestReadAcl(newMetadataIri)
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

		// A Local entry and a guest-readable Link that shares its resource URI; the Link has the lower entry URI
		def ownerBody = createTitleMetadataBody(ownedResourceUrl, 'Owner')
		getOrCreateEntry(contextId, [id: 'lookupZOwner'], ownerBody)
		createPublicEntry('lookupALink', [entrytype: 'link', resource: ownedResourceUrl], ownedResourceUrl, 'Link')

		// Four Links sharing an external URI, created highest id first so that creation order is not the pick
		(4..1).each { n ->
			getOrCreateEntry(contextId, [id: 'lookupShared' + n, entrytype: 'link', resource: sharedResourceUrl],
				createTitleMetadataBody(sharedResourceUrl, 'Shared ' + n))
		}
		awaitIndexed('uri:"' + entryUri('lookupZOwner') + '" AND public:false')

		// The same two shapes with every entry guest-readable, as the global lookup answers only with public
		// entries. Each is indexed before the next is created, the entry that must not be picked first.
		createPublicEntry('lookupAPublicLink', [entrytype: 'link', resource: publicOwnedResourceUrl],
			publicOwnedResourceUrl, 'Public Link')
		createPublicEntry('lookupZPublicOwner', [:], publicOwnedResourceUrl, 'Public Owner')
		(4..1).each { n ->
			createPublicEntry('lookupPublicShared' + n, [entrytype: 'link', resource: publicSharedResourceUrl],
				publicSharedResourceUrl, 'Public Shared ' + n)
		}
	}

	private static String entryUri(String id) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + id
	}

	/** Creates a guest-readable entry and waits until Solr serves it as public. */
	private void createPublicEntry(String id, Map params, String resourceUri, String title) {
		def body = createTitleMetadataBody(resourceUri, title)
		body.info = guestReadAcl(EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + id)
		getOrCreateEntry(contextId, [id: id] + params, body)
		awaitIndexed('uri:"' + entryUri(id) + '" AND public:true')
	}

	/** The entry info that grants guest ReadMetadata, which also makes the entry public in Solr. */
	private static Map guestReadAcl(String metadataUri) {
		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		return [(metadataUri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
	}

	/** The single dcterms:title in a lookup response, which tells which entry the lookup picked. */
	private static String pickedTitle(HttpURLConnection conn) {
		def responseJson = JSON_PARSER.parseText(conn.inputStream.text) as Map
		assert responseJson.keySet().size() == 1
		def titles = responseJson.values()[0][NameSpaceConst.DC_TERM_TITLE]
		assert titles.size() == 1
		return titles[0]['value']
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

	// --- Context-scoped lookup: several entries share the resource URI ---

	def "GET /{context-id}/lookup should return the Local entry that owns the URI rather than a Link to it"() {
		given:
		def queryParams = convertMapToQueryParams([uri: ownedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Owner'
	}

	def "GET /{context-id}/lookup should return the entry the caller may read rather than an unreadable owner"() {
		given:
		def queryParams = convertMapToQueryParams([uri: ownedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Link'
	}

	def "GET /{context-id}/lookup should return the Link with the lowest entry URI among Links sharing an external URI"() {
		given:
		def queryParams = convertMapToQueryParams([uri: sharedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Shared 1'
	}

	// --- Context-scoped lookup: the caller may read no entry with the resource URI ---

	def "GET /{context-id}/lookup as guest should return 401 when no entry with the URI is readable"() {
		given:
		def queryParams = convertMapToQueryParams([uri: sharedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /{context-id}/lookup as guest should return 401 for a scope that reads no metadata of the unreadable entry"() {
		given:
		def queryParams = convertMapToQueryParams([uri: sharedResourceUrl, scope: 'external'])

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.getHeaderField('Last-Modified') == null
		conn.getHeaderField('ETag') == null
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
		pickedTitle(conn) == 'Lookup Test Entry'
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

	// --- Global lookup: several entries share the resource URI ---

	def "GET /lookup should return the public Local entry that owns the URI rather than a Link to it"() {
		given:
		def queryParams = convertMapToQueryParams([uri: publicOwnedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Public Owner'
	}

	def "GET /lookup should return a public Link rather than the owner of the URI that is not public"() {
		given:
		def queryParams = convertMapToQueryParams([uri: ownedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, 'admin', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Link'
	}

	def "GET /lookup should return the Link with the lowest entry URI among public Links sharing an external URI"() {
		given:
		def queryParams = convertMapToQueryParams([uri: publicSharedResourceUrl])

		when:
		def conn = EntryStoreClient.getRequest('/lookup' + queryParams, '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_OK
		pickedTitle(conn) == 'Public Shared 1'
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
