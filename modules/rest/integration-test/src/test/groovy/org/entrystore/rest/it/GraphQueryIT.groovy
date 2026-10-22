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

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_OK

/**
 * The graphQuery parameter of {@code GET /{context-id}/metadata/{entry-id}} on a guest-readable entry. The shared
 * WireMock server stands in for an internal SPARQL endpoint, which a SERVICE clause must never reach; it records
 * every request, stubbed or not.
 */
class GraphQueryIT extends BaseSpec {

	static final String CONTEXT_ID = 'graph-query-it'
	static final String SERVICE_REJECTED = 'SPARQL SERVICE clauses are not permitted in graphQuery'

	static String entryId

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])

		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		def newResourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def newMetadataIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/_newId'
		def body = createTitleMetadataBody(newResourceIri, 'Public entry')
		body.metadata[newResourceIri][NameSpaceConst.DC_TERM_DESCRIPTION] = [[type: 'literal', value: 'Description']]
		body.info = [(newMetadataIri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
		entryId = createEntry(CONTEXT_ID, [entrytype: 'link', resource: 'https://example.org/graph-query-it'], body)
	}

	static String sparqlEndpoint() {
		return '<http://localhost:' + wireMockServer.port() + '/sparql>'
	}

	static String metadataPath(Map<String, String> params) {
		return '/' + CONTEXT_ID + '/metadata/' + entryId + convertMapToQueryParams(params)
	}

	def "GET metadata with a graphQuery with SERVICE #form as guest should return 400 before any request is sent"() {
		given:
		def graphQuery = 'CONSTRUCT { ?s ?p ?o } WHERE { ' + pattern.replace('ENDPOINT', sparqlEndpoint()) + ' }'

		when:
		def conn = EntryStoreClient.getRequest(metadataPath([graphQuery: graphQuery]), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		JSON_PARSER.parseText(conn.errorStream.text)['error'] == SERVICE_REJECTED
		wireMockServer.verify(0, anyRequestedFor(anyUrl()))

		where:
		form                | pattern
		'plain'             | 'SERVICE ENDPOINT { ?s ?p ?o }'
		'SILENT'            | 'SERVICE SILENT ENDPOINT { ?s ?p ?o }'
		'variable endpoint' | 'VALUES ?endpoint { ENDPOINT } SERVICE ?endpoint { ?s ?p ?o }'
		'in OPTIONAL'       | '?s ?p ?o OPTIONAL { SERVICE ENDPOINT { ?s ?p ?x } }'
		'in subselect'      | '{ SELECT ?s ?p ?o WHERE { SERVICE ENDPOINT { ?s ?p ?o } } }'
		'in FILTER EXISTS'  | '?s ?p ?o FILTER EXISTS { SERVICE ENDPOINT { ?s ?p ?x } }'
	}

	def "GET metadata with recursive and a graphQuery with SERVICE as guest should return 400 before any request"() {
		given:
		def graphQuery = 'CONSTRUCT { ?s ?p ?o } WHERE { SERVICE ' + sparqlEndpoint() + ' { ?s ?p ?o } }'

		when:
		def conn = EntryStoreClient.getRequest(metadataPath([recursive: 'dct', graphQuery: graphQuery]), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		JSON_PARSER.parseText(conn.errorStream.text)['error'] == SERVICE_REJECTED
		wireMockServer.verify(0, anyRequestedFor(anyUrl()))
	}

	def "GET metadata with a graphQuery without SERVICE as guest should return only the constructed triples"() {
		given:
		def graphQuery = 'CONSTRUCT { ?s <' + NameSpaceConst.DC_TERM_TITLE + '> ?o } WHERE { ?s <' +
			NameSpaceConst.DC_TERM_TITLE + '> ?o }'

		when:
		def conn = EntryStoreClient.getRequest(metadataPath([graphQuery: graphQuery]), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def graph = JSON_PARSER.parseText(conn.inputStream.text) as Map
		graph.size() == 1
		def properties = graph.values()[0] as Map
		properties.keySet() == [NameSpaceConst.DC_TERM_TITLE] as Set
		properties[NameSpaceConst.DC_TERM_TITLE].collect { it['value'] } == ['Public entry']
	}

	def "GET metadata with a malformed graphQuery as guest should return 400"() {
		when:
		def conn = EntryStoreClient.getRequest(metadataPath([graphQuery: 'CONSTRUCT { ?s ?p ?o } WHERE {']), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		JSON_PARSER.parseText(conn.errorStream.text)['error'] == 'Malformed SPARQL query'
	}
}
