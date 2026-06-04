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
import static java.net.HttpURLConnection.HTTP_OK

class JsonpIT extends BaseSpec {

	private static final String CONTEXT_ID = 'jsonp-it-ctx'
	private static final String SPARQL_QUERY = 'SELECT * WHERE { ?s ?p ?o } LIMIT 1'

	private static String entryUrl

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def params = [entrytype: 'link', resource: 'https://example.org/jsonp']
		def body = [metadata: [(resourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type: 'literal', value: 'JSONP entry']]]]]
		def entryId = createEntry(CONTEXT_ID, params, body)
		entryUrl = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + entryId
	}

	def "GET entry with ?callback= should wrap the JSON body as JSONP"() {
		when:
		def conn = EntryStoreClient.getRequest(entryUrl + '?callback=cb', 'admin')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/javascript')
		// The wrapped body no longer matches the controller's ETag/Last-Modified, so it must be
		// non-cacheable end-to-end (here 'private, no-store' from CacheControlFilter on this admin request).
		conn.getHeaderField('Cache-Control').contains('no-store')
		def jsonpBody = conn.getInputStream().text
		jsonpBody.startsWith('cb(')
		jsonpBody.endsWith(')')
		// The wrapped payload (between the first '(' and the last ')') must be the unmodified JSON object.
		def innerJson = jsonpBody.substring(jsonpBody.indexOf('(') + 1, jsonpBody.lastIndexOf(')'))
		JSON_PARSER.parseText(innerJson) instanceof Map
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
