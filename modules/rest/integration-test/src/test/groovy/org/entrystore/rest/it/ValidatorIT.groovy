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
import spock.lang.Unroll

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ValidatorIT extends BaseSpec {

	private static final String VALID_TURTLE = '''\
		@prefix dc: <http://purl.org/dc/terms/> .
		<http://example.org/a> dc:title "Title" .
		'''.stripIndent()

	private static final String VALID_NTRIPLES =
		'<http://example.org/a> <http://purl.org/dc/terms/title> "Title" .\n'

	private static final String VALID_TRIG = '''\
		<http://example.org/graph> {
			<http://example.org/a> <http://purl.org/dc/terms/title> "Title" .
		}
		'''.stripIndent()

	private static final String VALID_RDFXML = '''\
		<?xml version="1.0"?>
		<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
		         xmlns:dc="http://purl.org/dc/terms/">
		  <rdf:Description rdf:about="http://example.org/a">
		    <dc:title>Title</dc:title>
		  </rdf:Description>
		</rdf:RDF>
		'''.stripIndent()

	private static final String VALID_JSONLD =
		'{"@id":"http://example.org/a","http://purl.org/dc/terms/title":"Title"}'

	private static final String VALID_RDFJSON = '{' +
		'"http://example.org/a":{' +
		'"http://purl.org/dc/terms/title":[{"type":"literal","value":"Title"}]' +
		'}}'

	def "POST /validator as guest should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator', VALID_TURTLE, '', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /validator as user with valid Turtle should return 200"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator', VALID_TURTLE, 'user', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
	}

	def "POST /validator as admin with valid Turtle should return 200"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator', VALID_TURTLE, 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
	}

	@Unroll
	def "POST /validator as admin with valid #label body should return 200"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator', body, 'admin', contentType)

		then:
		connection.getResponseCode() == HTTP_OK

		where:
		label        | contentType                  | body
		'RDF/XML'    | 'application/rdf+xml'        | VALID_RDFXML
		'N-Triples'  | 'application/n-triples'      | VALID_NTRIPLES
		'TriG'       | 'application/trig'           | VALID_TRIG
		'legacy N3'  | 'text/rdf+n3'                | VALID_NTRIPLES
		'JSON-LD'    | 'application/ld+json'        | VALID_JSONLD
		'RDF/JSON'   | 'application/rdf+json'       | VALID_RDFJSON
	}

	def "POST /validator as admin with format query param overriding Content-Type should return 200"() {
		when:
		def connection = EntryStoreClient.postRequest(
			'/validator?format=text/turtle', VALID_TURTLE, 'admin', 'application/octet-stream')

		then:
		connection.getResponseCode() == HTTP_OK
	}

	def "POST /validator as admin with malformed RDF body should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator',
			'this is not valid turtle <<<>>>', 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		def errorBody = JSON_PARSER.parseText(connection.errorStream.text)
		errorBody.status == 400
		errorBody.error
	}

	def "POST /validator as admin with statement containing invalid IRI literal should return Bad-Request 400"() {
		given:
		def body = '<http://example.org/a> <http://example.org/p> "http://bad uri/x" .\n'

		when:
		def connection = EntryStoreClient.postRequest('/validator', body, 'admin',
			'application/n-triples')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /validator as admin with unsupported Content-Type should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/validator', VALID_TURTLE, 'admin', 'text/plain')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /validator as admin with format query param overriding to unsupported type should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest(
			'/validator?format=text/plain', VALID_TURTLE, 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /validator as admin with format query param taking precedence over disagreeing Content-Type should return 200"() {
		when:
		def connection = EntryStoreClient.postRequest(
			'/validator?format=text/turtle', VALID_TURTLE, 'admin', 'application/n-triples')

		then:
		connection.getResponseCode() == HTTP_OK
	}

	def "POST /validator as admin with body larger than 10MB should return Payload-Too-Large 413"() {
		given:
		def size = 11 * 1024 * 1024
		def builder = new StringBuilder(size)
		def line = '<http://example.org/a> <http://example.org/p> <http://example.org/o> .\n'
		while (builder.length() < size) {
			builder.append(line)
		}

		when:
		def connection = EntryStoreClient.postRequest('/validator', builder.toString(),
			'admin', 'application/n-triples')

		then:
		connection.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

}
