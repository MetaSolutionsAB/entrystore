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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import static java.net.HttpURLConnection.HTTP_NOT_ACCEPTABLE
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

/**
 * The format parameter on entry and resource URIs, against the content types 5.8.0 answered with. Rows with a null
 * Accept send HttpURLConnection's default, which ends in a wildcard, as a script tag does.
 * Format values are sent with an unencoded '+', as EntryScape's ResourceInfo links do.
 */
class FormatParameterIT extends BaseSpec {

	private static final String CONTEXT_ID = 'format-param-it'
	private static final String BROWSER_ACCEPT =
			'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8'
	private static final String LINK_RESOURCE = 'https://example.org/format'
	private static final String GRAPH_TURTLE = '<https://example.org/s> <https://example.org/p> "format test" .'

	private static String entryPath
	private static String graphResourcePath

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
		def linkEntryId = createEntry(CONTEXT_ID, [entrytype: 'link', resource: LINK_RESOURCE])
		entryPath = '/' + CONTEXT_ID + '/entry/' + linkEntryId

		def graphEntryId = createEntry(CONTEXT_ID, [graphtype: 'graph'])
		graphResourcePath = '/' + CONTEXT_ID + '/resource/' + graphEntryId
		def put = EntryStoreClient.putRequest(graphResourcePath, GRAPH_TURTLE, 'admin', 'text/turtle')
		assert put.getResponseCode() == HTTP_NO_CONTENT
	}

	def "GET entry with format=#format and Accept #accept should answer #format"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath + '?format=' + format, 'admin', accept)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith(format)
		conn.inputStream.text.contains(LINK_RESOURCE)

		where:
		format                  | accept
		'text/turtle'           | null
		'application/ld+json'   | null
		'application/n-triples' | null
		'application/json'      | null
		'text/turtle'           | BROWSER_ACCEPT
		'application/ld+json'   | BROWSER_ACCEPT
		'application/n-triples' | BROWSER_ACCEPT
		'application/json'      | BROWSER_ACCEPT
		'application/rdf+xml'   | 'application/json'
	}

	def "GET entry with format=application/json should answer the entry JSON, not its graph"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath + '?format=application/json', 'admin', 'text/turtle')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith('application/json')
		JSON_PARSER.parseText(conn.inputStream.text)['entryId'] == entryPath.tokenize('/').last()
	}

	def "GET entry with a browser Accept header and no format should answer RDF/XML"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath, 'admin', BROWSER_ACCEPT)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith('application/rdf+xml')
		conn.inputStream.text.contains('<rdf:RDF')
	}

	def "GET entry with query \"#query\" and Accept #accept should answer 406"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath + query, 'admin', accept)

		then:
		conn.getResponseCode() == HTTP_NOT_ACCEPTABLE

		where:
		query                   | accept
		'?format=image/png'     | '*/*'
		'?format=*/*'           | '*/*'
		'?format=application/*' | '*/*'
		''                      | 'application/json;q=0'
	}

	def "GET entry with query \"#query\" and no Accept header at all should answer #expected"() {
		given: 'HttpURLConnection always sends an Accept header; java.net.http.HttpClient sends none unless told to'
		def request = HttpRequest.newBuilder(URI.create(EntryStoreClient.baseUrl + entryPath + query))
				.header('Cookie', EntryStoreClient.cookies['admin'].toString())
				.GET()
				.build()

		when:
		def response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

		then:
		response.statusCode() == HTTP_OK
		response.headers().firstValue('Content-Type').orElseThrow().startsWith(expected)
		response.body().contains(LINK_RESOURCE)

		where:
		query                           | expected
		''                              | 'application/rdf+xml'
		'?format=text/turtle'           | 'text/turtle'
		'?format=application/ld+json'   | 'application/ld+json'
		'?format=application/n-triples' | 'application/n-triples'
		'?format=application/json'      | 'application/json'
	}

	def "GET Graph resource with format=#format and Accept #accept should answer #format"() {
		when:
		def conn = EntryStoreClient.getRequest(graphResourcePath + '?format=' + format, 'admin', accept)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith(format)
		conn.inputStream.text.contains('format test')

		where:
		format                | accept
		'text/turtle'         | null
		'application/ld+json' | null
		'application/json'    | null
		'text/turtle'         | BROWSER_ACCEPT
		'application/ld+json' | BROWSER_ACCEPT
		'application/json'    | BROWSER_ACCEPT
		'text/turtle'         | 'application/rdf+xml'
		'application/ld+json' | 'application/rdf+xml'
		'application/json'    | 'application/rdf+xml'
	}

	def "GET Graph resource with a browser Accept header and no format should answer RDF/XML"() {
		when:
		def conn = EntryStoreClient.getRequest(graphResourcePath, 'admin', BROWSER_ACCEPT)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith('application/rdf+xml')
		conn.inputStream.text.contains('format test')
	}
}
