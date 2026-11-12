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

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class JsonpIT extends BaseSpec {

	private static final String CONTEXT_ID = 'jsonp-it-ctx'
	private static final String PRIVATE_CONTEXT_ID = 'jsonp-it-private-ctx'
	private static final String SPARQL_QUERY = 'SELECT * WHERE { ?s ?p ?o } LIMIT 1'
	// The title of one entry in each of the two contexts, so that one search matches both
	private static final String SEARCH_MARKER = 'jsonpguestmarker'
	private static final String SEARCH_QUERY = literalField(NameSpaceConst.DC_TERM_TITLE) + ':' + SEARCH_MARKER

	private static String entryUrl
	private static Map<String, String> jsonpPaths
	// Each value ends where the callback parameter is appended.
	private static Map<String, String> guestComparisonPaths
	private static String adminBasic

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
		// A JSONP request is served as guest, so what the wrapping tests read is guest-readable
		putContextInfo(CONTEXT_ID, true)
		getOrCreateContext([contextId: PRIVATE_CONTEXT_ID])
		putContextInfo(PRIVATE_CONTEXT_ID, false)
		def publicSearchId = createEntry(CONTEXT_ID, [graphtype: 'string'], searchMarkerBody(CONTEXT_ID))
		def privateId = createEntry(PRIVATE_CONTEXT_ID, [graphtype: 'string'], searchMarkerBody(PRIVATE_CONTEXT_ID))
		awaitAdminSearchCount(SEARCH_QUERY, 2)
		def privateMetadataPath = '/' + PRIVATE_CONTEXT_ID + '/metadata/' + privateId
		assert EntryStoreClient.getRequest(privateMetadataPath, 'admin').getResponseCode() == HTTP_OK
		assert EntryStoreClient.getRequest(privateMetadataPath, '').getResponseCode() == HTTP_UNAUTHORIZED
		guestComparisonPaths = [
			'/search'         : '/search' + convertMapToQueryParams([type: 'solr', query: SEARCH_QUERY]) + '&',
			'private entry'   : '/' + PRIVATE_CONTEXT_ID + '/entry/' + privateId + '?format=application/json&',
			'private metadata': privateMetadataPath + '?format=application/json&',
			'public entry'    : '/' + CONTEXT_ID + '/entry/' + publicSearchId + '?format=application/json&',
			'/auth/user'      : '/auth/user?',
			'/auth/tokens'    : '/auth/tokens?'
		]
		adminBasic = EntryStoreClient.basicAuth('admin', EntryStoreClient.creds['admin'].toString())

		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def params = [entrytype: 'link', resource: 'https://example.org/jsonp']
		def body = createTitleMetadataBody(resourceIri, 'JSONP entry')
		def entryId = createEntry(CONTEXT_ID, params, body)
		entryUrl = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + entryId

		def listId = createEntry(CONTEXT_ID, [graphtype: 'list'], [resource: [entryId]])
		def graphId = createEntry(CONTEXT_ID, [graphtype: 'graph'])
		// Each value ends where the format and callback parameters are appended.
		jsonpPaths = [
			'entry'         : entryUrl + '?includeAll&',
			'list resource' : '/' + CONTEXT_ID + '/resource/' + listId + '?',
			'graph resource': '/' + CONTEXT_ID + '/resource/' + graphId + '?',
			'user resource' : '/_principals/resource/_admin?'
		]
	}

	private static Map searchMarkerBody(String contextId) {
		def resourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		return [resource: 'JSONP search text'] + createTitleMetadataBody(resourceIri, SEARCH_MARKER)
	}

	/** The JSON that a JSONP body wraps. */
	private static Object wrappedJson(String jsonpBody) {
		assert jsonpBody.startsWith('cb(') && jsonpBody.endsWith(')'): 'not wrapped as JSONP: ' + jsonpBody
		return JSON_PARSER.parseText(jsonpBody.substring('cb('.length(), jsonpBody.length() - 1))
	}

	/**
	 * A response body in a form that two requests can be compared by: its JSON without the timestamp that error
	 * responses carry, and whether it is wrapped. An error of the access rules, such as the 401 of /auth/tokens,
	 * is written before the JSONP filter and stays plain JSON.
	 */
	private static Map comparable(String body) {
		def wrapped = body.startsWith('cb(')
		def json = wrapped ? wrappedJson(body) : JSON_PARSER.parseText(body)
		return [wrapped: wrapped, json: json instanceof Map ? json.findAll { it.key != 'timestamp' } : json]
	}

	def "JSONP GET of #target with #credentials should answer what a guest gets and set no cookie"() {
		given:
		def path = guestComparisonPaths[target] + 'callback=cb'
		def guestConn = EntryStoreClient.getRequest(path, '', '*/*')
		def headers = credentials == 'HTTP Basic'
				? [Authorization: adminBasic]
				: [Cookie: EntryStoreClient.loginIsolated('admin').authCookie]

		when:
		def conn = EntryStoreClient.getRequest(path, '', '*/*', headers)

		then:
		conn.getResponseCode() == guestConn.getResponseCode()
		comparable(EntryStoreClient.bodyOf(conn)) == comparable(EntryStoreClient.bodyOf(guestConn))
		conn.getHeaderFields().get('Set-Cookie') == null

		where:
		[target, credentials] << [
			['/search', 'private entry', 'private metadata', 'public entry', '/auth/user', '/auth/tokens'],
			['a session cookie', 'HTTP Basic']
		].combinations()
	}

	def "JSONP GET /auth/user with wrong HTTP Basic credentials should answer as guest"() {
		// HTTP Basic is on in the ITs and would answer these credentials with 401; a JSONP request carries none
		when:
		def conn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', '*/*',
				[Authorization: EntryStoreClient.basicAuth('admin', 'notTheAdminPassword')])

		then:
		conn.getResponseCode() == HTTP_OK
		wrappedJson(conn.inputStream.text)['user'] == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null
	}

	def "JSONP GET /search as admin should not count the entry that only the admin may read"() {
		given:
		def path = guestComparisonPaths['/search']
		def plainConn = EntryStoreClient.getRequest(path, 'admin')
		assert JSON_PARSER.parseText(plainConn.inputStream.text)['results'] == 2

		when:
		def conn = EntryStoreClient.getRequest(path + 'callback=cb', 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		wrappedJson(conn.inputStream.text)['results'] < 2
	}

	def "JSONP GET /auth/user with a session cookie should leave the session logged in"() {
		given:
		def login = EntryStoreClient.loginIsolated('admin')

		when:
		def conn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', '*/*', [Cookie: login.authCookie])

		then: 'the request is served as guest and changes no cookie'
		conn.getResponseCode() == HTTP_OK
		wrappedJson(conn.inputStream.text)['user'] == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null

		and: 'the same cookie still authenticates'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: login.authCookie])
		userConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(userConn.inputStream.text)['user'] == 'admin'
	}

	def "GET entry with ?callback= should wrap the JSON body as JSONP"() {
		when:
		def conn = EntryStoreClient.getRequest(entryUrl + '?callback=cb', 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/javascript')
		// The wrapped body no longer matches the controller's ETag/Last-Modified, so it must be
		// non-cacheable end-to-end. The session cookie of this request is not seen: it gets the guest's value.
		conn.getHeaderField('Cache-Control') == 'no-store'
		def jsonpBody = conn.getInputStream().text
		jsonpBody.startsWith('cb(')
		jsonpBody.endsWith(')')
		// The wrapped payload (between the first '(' and the last ')') must be the unmodified JSON object.
		def innerJson = jsonpBody.substring(jsonpBody.indexOf('(') + 1, jsonpBody.lastIndexOf(')'))
		JSON_PARSER.parseText(innerJson) instanceof Map
	}

	def "anonymous GET entry with ?callback= should be wrapped and not stored"() {
		when:
		def conn = EntryStoreClient.getRequest(entryUrl + '?callback=cb', '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/javascript')
		conn.getHeaderField('Cache-Control') == 'no-store'
	}

	def "conditional JSONP GET of an entry answered 304 should not be stored either"() {
		given:
		def path = entryUrl + '?format=application/json&callback=cb'
		def etag = EntryStoreClient.getRequest(path, 'admin').getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(path, 'admin', 'application/json', ['If-None-Match': etag])

		then:
		etag != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		conn.getHeaderField('Cache-Control') == 'no-store'
	}

	// entrystore.js loads cross-origin GETs by JSONP with format=application/json; a script tag accepts anything.
	def "JSONP GET of #target with format=application/json and Accept */* should be wrapped as JSONP"() {
		when:
		def conn = EntryStoreClient.getRequest(jsonpPaths[target] + 'format=application/json&callback=cb', 'admin',
				'*/*')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith('application/javascript')
		def jsonpBody = conn.getInputStream().text
		jsonpBody.startsWith('cb(')
		jsonpBody.endsWith(')')
		def innerJson = jsonpBody.substring(jsonpBody.indexOf('(') + 1, jsonpBody.lastIndexOf(')'))
		expectedType.isInstance(JSON_PARSER.parseText(innerJson))

		where:
		target           | expectedType
		'entry'          | Map
		'list resource'  | List
		'graph resource' | Map
		'user resource'  | Map
	}

	def "GET entry without ?callback= should return plain JSON"() {
		when:
		def conn = EntryStoreClient.getRequest(entryUrl, 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		JSON_PARSER.parseText(conn.getInputStream().text) instanceof Map
	}

	def "GET entry with an invalid ?callback= should respond with Bad-Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest(entryUrl + convertMapToQueryParams([callback: 'alert(1)']), 'admin')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		def err = JSON_PARSER.parseText(conn.errorStream.text)
		err['error'] == 'Invalid JSONP callback name'
	}

	def "GET /sparql with ?callback= should stream normally and not be wrapped"() {
		given:
		def queryParams = [query: SPARQL_QUERY, format: 'application/sparql-results+json', callback: 'cb']

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
		def sparqlBody = conn.getInputStream().text
		!sparqlBody.startsWith('cb(')
		def json = JSON_PARSER.parseText(sparqlBody)
		json['head'] != null
		json['results'] != null
	}
}
