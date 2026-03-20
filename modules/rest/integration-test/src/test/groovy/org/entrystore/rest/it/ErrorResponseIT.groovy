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

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ErrorResponseIT extends BaseSpec {

	// ========================
	// 400 Bad Request
	// ========================

	def "GET /search?type=sparql&query=dc:title&syndication=random-string as admin should return BAD-REQUEST 400 due to invalid syndication format"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'random-string']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), 'admin', 'text/html')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['error'] == 'Invalid syndication feed type: \'random-string\''
	}

	def "GET /search without required query param should return 400 with JSON error response"() {
		given:
		def queryParams = [type: 'solr']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 400
		resp['error'] != null
		resp['timestamp'] != null
		resp['path'] != null
	}

	def "POST /{context-id} with invalid entrytype should return 400 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err400ctx'])
		def params = [entrytype: 'invalidType']

		when:
		def conn = EntryStoreClient.postRequest('/err400ctx' + convertMapToQueryParams(params))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 400
		resp['error'] != null
		resp['timestamp'] != null
	}

	def "POST /_principals as guest should return 401 with JSON error response"() {
		when:
		def conn = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams([graphtype: 'user']),
				JsonOutput.toJson([resource: [name: 'GuestCreatedUser']]), '', 'application/json')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 401
		resp['error'] == 'Unauthorized'
	}

	// ========================
	// 403 Forbidden (authenticated non-admin)
	// ========================

	def "POST /_principals as non-admin user should return 403 with JSON error response"() {
		when:
		def conn = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams([graphtype: 'user']),
				JsonOutput.toJson([resource: [name: 'ForbiddenUser']]), 'user', 'application/json')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 403
		resp['error'] != null
		resp['timestamp'] != null
		resp['path'] != null
	}

	def "DELETE /_principals/entry/{entry-id} as non-admin user should return 403 with JSON error response"() {
		given:
		def user = UserUtil.createUser('ErrForbiddenDeleteTarget')
		def entryId = user['entryId'].toString()

		when:
		def conn = EntryStoreClient.deleteRequest('/_principals/entry/' + entryId, null, 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 403
		resp['error'] != null
	}

	// ========================
	// 404 Not Found
	// ========================

	def "GET /{context-id}/entry/{entry-id} for non-existent entry should return 404 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err404ctx'])

		when:
		def conn = EntryStoreClient.getRequest('/err404ctx/entry/non-existent-id')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 404
		resp['error'] != null
		resp['error'].toString().contains('non-existent-id')
		resp['timestamp'] != null
		resp['path'] != null
	}

	def "GET /{context-id}/metadata/{entry-id} for non-existent entry should return 404 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err404ctx'])

		when:
		def conn = EntryStoreClient.getRequest('/err404ctx/metadata/non-existent-id')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 404
		resp['error'] != null
	}

	def "GET /{context-id}/resource/{entry-id} for non-existent entry should return 404 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err404ctx'])

		when:
		def conn = EntryStoreClient.getRequest('/err404ctx/resource/non-existent-id')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 404
		resp['error'] != null
	}

	def "POST /non-existent-context should return 404 with JSON error response"() {
		given:
		def params = [entrytype: 'link', resource: 'http://example.org/err404']

		when:
		def conn = EntryStoreClient.postRequest('/non-existent-context-xyz' + convertMapToQueryParams(params))

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 404
		resp['error'] != null
		resp['error'].toString().contains('non-existent-context-xyz')
	}

	// ========================
	// 405 Method Not Allowed
	// ========================

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 on a non-list entry should return 405 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err405ctx'])
		def entryId = createEntry('err405ctx', [entrytype: 'link', resource: 'http://example.org/err405'])

		when:
		def conn = EntryStoreClient.getRequest('/err405ctx/resource/' + entryId + '?syndication=rss_2.0')

		then:
		conn.getResponseCode() == 405
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 405
		resp['error'] != null
		resp['error'].toString().contains('not a context or a list')
		resp['timestamp'] != null
		resp['path'] != null
	}

	// ========================
	// 409 Conflict
	// ========================

	def "POST /{context-id}?id=existing-id should return 409 with JSON error response when entry already exists"() {
		given:
		getOrCreateContext([contextId: 'err409ctx'])
		def params = [entrytype: 'link', resource: 'http://example.org/err409', id: 'duplicateId']
		getOrCreateEntry('err409ctx', params)

		when:
		def conn = EntryStoreClient.postRequest('/err409ctx' + convertMapToQueryParams(params))

		then:
		conn.getResponseCode() == HTTP_CONFLICT
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 409
		resp['error'] != null
		resp['error'].toString().contains('already exists')
		resp['timestamp'] != null
		resp['path'] != null
	}

	// ========================
	// 406 Not Acceptable (CustomResponseException)
	// ========================

	def "GET /{context-id}/metadata/{entry-id}?format=application/invalid-rdf should return 406 with JSON error response"() {
		given:
		getOrCreateContext([contextId: 'err406ctx'])
		def entryId = createEntry('err406ctx', [entrytype: 'link', resource: 'http://example.org/err406'])

		when:
		def conn = EntryStoreClient.getRequest('/err406ctx/metadata/' + entryId + '?format=application/invalid-rdf-format')

		then:
		conn.getResponseCode() == 406
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.getErrorStream().text)
		resp['status'] == 406
		resp['error'] != null
		resp['timestamp'] != null
		resp['path'] != null
	}
}
