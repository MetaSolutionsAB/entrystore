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
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

class JsonpIT extends BaseSpec {

	private static final String PRIVATE_CONTEXT_ID = 'jsonp-it-ctx'
	private static final String PUBLIC_CONTEXT_ID = 'jsonp-it-public-ctx'
	private static final String SEARCH_MARKER = 'jsonpmarker'
	private static final String SPARQL_QUERY = 'SELECT * WHERE { ?s ?p ?o } LIMIT 1'
	private static final String BASIC_ADMIN = 'Basic ' + Base64.getEncoder().encodeToString('admin:adminpass'.bytes)
	/** The request headers that authenticate a request, by credential kind, given an isolated admin login. */
	private static final Map<String, Closure<Map>> CREDENTIAL_HEADERS = [
		'a session cookie'      : { Map login -> [Cookie: login.authCookie] },
		'HTTP Basic credentials': { Map login -> [Authorization: BASIC_ADMIN] }
	]

	private static String publicEntryUrl
	private static String privateEntryUrl
	private static String publicEntryId
	// Each value ends where the format and callback parameters are appended.
	private static Map<String, String> jsonpPaths
	private static Map<String, String> guestComparedPaths

	def setupSpec() {
		getOrCreateContext([contextId: PRIVATE_CONTEXT_ID])
		getOrCreateContext([contextId: PUBLIC_CONTEXT_ID])
		def privateEntryId = createEntry(PRIVATE_CONTEXT_ID, [:],
				createTitleMetadataBody(newResourceIri(PRIVATE_CONTEXT_ID), SEARCH_MARKER + ' private'))
		privateEntryUrl = EntryStoreClient.baseUrl + '/' + PRIVATE_CONTEXT_ID + '/entry/' + privateEntryId
		publicEntryId = createEntry(PUBLIC_CONTEXT_ID, [:], guestReadable(PUBLIC_CONTEXT_ID,
				createTitleMetadataBody(newResourceIri(PUBLIC_CONTEXT_ID), SEARCH_MARKER + ' public')))
		publicEntryUrl = EntryStoreClient.baseUrl + '/' + PUBLIC_CONTEXT_ID + '/entry/' + publicEntryId

		def listId = createEntry(PUBLIC_CONTEXT_ID, [graphtype: 'list'],
				guestReadable(PUBLIC_CONTEXT_ID, [resource: [publicEntryId]]))
		def graphId = createEntry(PUBLIC_CONTEXT_ID, [graphtype: 'graph'], guestReadable(PUBLIC_CONTEXT_ID, [:]))
		jsonpPaths = [
			'entry'         : publicEntryUrl + '?includeAll&',
			'list resource' : '/' + PUBLIC_CONTEXT_ID + '/resource/' + listId + '?',
			'graph resource': '/' + PUBLIC_CONTEXT_ID + '/resource/' + graphId + '?',
			'user resource' : '/_principals/resource/_admin?'
		]
		guestComparedPaths = [
			'/search'        : '/search?type=solr&query=title:' + SEARCH_MARKER + '&',
			'a private entry': privateEntryUrl + '?includeAll&format=application/json&',
			'a public entry' : publicEntryUrl + '?includeAll&format=application/json&',
			'/auth/user'     : '/auth/user?',
			'/auth/tokens'   : '/auth/tokens?'
		]

		waitForSolrProcessing()
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until {
				def probe = EntryStoreClient.getRequest('/search?type=solr&query=title:' + SEARCH_MARKER)
				probe.getResponseCode() == HTTP_OK && JSON_PARSER.parseText(probe.inputStream.text)['results'] >= 2
			}
	}

	def "anonymous GET entry with ?callback= should wrap the JSON body as JSONP and not be stored"() {
		when:
		def conn = EntryStoreClient.getRequest(publicEntryUrl + '?callback=cb', '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/javascript')
		// The wrapped body no longer matches the controller's ETag/Last-Modified, so it must be non-cacheable.
		conn.getHeaderField('Cache-Control') == 'no-store'
		def jsonpBody = conn.getInputStream().text
		jsonpBody.startsWith('cb(')
		jsonpBody.endsWith(')')
		// The wrapped payload (between the first '(' and the last ')') must be the unmodified JSON object.
		def innerJson = jsonpBody.substring(jsonpBody.indexOf('(') + 1, jsonpBody.lastIndexOf(')'))
		JSON_PARSER.parseText(innerJson) instanceof Map
	}

	def "GET entry with ?callback= and a session cookie should be wrapped, not stored and still private"() {
		when:
		def conn = EntryStoreClient.getRequest(publicEntryUrl + '?callback=cb', 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/javascript')
		conn.getHeaderField('Cache-Control') == 'private, no-store'
	}

	def "conditional JSONP GET of an entry answered 304 should not be stored either"() {
		given:
		def path = publicEntryUrl + '?format=application/json&callback=cb'
		def etag = EntryStoreClient.getRequest(path, 'admin').getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(path, 'admin', 'application/json', ['If-None-Match': etag])

		then:
		etag != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		conn.getHeaderField('Cache-Control') == 'private, no-store'
	}

	// entrystore.js loads cross-origin GETs by JSONP with format=application/json; a script tag accepts anything.
	def "JSONP GET of #target with format=application/json and Accept */* should be wrapped as JSONP"() {
		when:
		def conn = EntryStoreClient.getRequest(jsonpPaths[target] + 'format=application/json&callback=cb', '', '*/*')

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

	def "JSONP GET of #target with #credentials should be answered as to a guest"() {
		given:
		def login = EntryStoreClient.loginIsolated('admin')
		def headers = CREDENTIAL_HEADERS[credentials].call(login)
		def path = guestComparedPaths[target] + 'callback=cb'
		def guestConn = EntryStoreClient.getRequest(path, '', '*/*')
		def guestBody = bodyOf(guestConn)

		when:
		def conn = EntryStoreClient.getRequest(path, '', '*/*', headers)
		def body = bodyOf(conn)

		then:
		conn.getResponseCode() == guestConn.getResponseCode()
		conn.getContentType() == guestConn.getContentType()
		payload(body) == payload(guestBody)
		EntryStoreClient.findSetCookies(conn, 'auth_token').isEmpty()
		EntryStoreClient.assertNoSessionIds(conn, body, [login.authCookie])

		and: 'the session still authenticates a request without a callback'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: login.authCookie])
		userConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(userConn.inputStream.text)['user'] == 'admin'

		where:
		[target, credentials] << [
			['/search', 'a private entry', 'a public entry', '/auth/user', '/auth/tokens'],
			CREDENTIAL_HEADERS.keySet() as List
		].combinations()
	}

	def "JSONP search with a session cookie should list only the public entries"() {
		when:
		def conn = EntryStoreClient.getRequest(guestComparedPaths['/search'] + 'callback=cb', 'admin', '*/*')

		then:
		conn.getResponseCode() == HTTP_OK
		def results = payload(conn.inputStream.text)
		results['results'] == 1
		results['resource']['children'].collect { it['entryId'] } == [publicEntryId]
	}

	def "JSONP GET of a private entry with a session cookie should hold only what a guest may read"() {
		given: 'the title and creator that the session reads without a callback'
		def path = privateEntryUrl + '?includeAll&format=application/json'
		def plainConn = EntryStoreClient.getRequest(path, 'admin', '*/*')
		assert plainConn.getResponseCode() == HTTP_OK
		def plainBody = plainConn.inputStream.text
		assert plainBody.contains(SEARCH_MARKER + ' private')
		assert plainBody.contains(NameSpaceConst.DC_TERM_CREATOR)

		when:
		def body = bodyOf(EntryStoreClient.getRequest(path + '&callback=cb', 'admin', '*/*'))

		then:
		body.startsWith('cb(')
		!body.contains(SEARCH_MARKER + ' private')
		!body.contains(NameSpaceConst.DC_TERM_CREATOR)
	}

	def "GET entry without ?callback= should return plain JSON"() {
		when:
		def conn = EntryStoreClient.getRequest(privateEntryUrl, 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		JSON_PARSER.parseText(conn.getInputStream().text) instanceof Map
	}

	def "GET entry with an invalid ?callback= should respond with Bad-Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest(publicEntryUrl + convertMapToQueryParams([callback: 'alert(1)']),
				'admin')

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

	private static String newResourceIri(String contextId) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
	}

	/** Adds to the request body of a new entry an ACL that lets a guest read its metadata and resource. */
	private static Map guestReadable(String contextId, Map body) {
		def base = EntryStoreClient.baseUrl + '/' + contextId
		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		def guestRead = [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]
		return body + [info: [(base + '/metadata/_newId'): guestRead, (base + '/resource/_newId'): guestRead]]
	}

	private static String bodyOf(HttpURLConnection conn) {
		return conn.getResponseCode() < 400 ? conn.inputStream.text : conn.errorStream?.text
	}

	/** @return the JSON a response carries, unwrapped from a cb(...) callback, without an error's timestamp */
	private static Object payload(String body) {
		def json = body.startsWith('cb(') ? body.substring('cb('.length(), body.length() - 1) : body
		def parsed = JSON_PARSER.parseText(json)
		return parsed instanceof Map ? parsed.findAll { it.key != 'timestamp' } : parsed
	}
}
