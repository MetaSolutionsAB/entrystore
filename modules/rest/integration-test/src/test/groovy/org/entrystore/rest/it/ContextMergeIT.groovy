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

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ContextMergeIT extends BaseSpec {

	def static contextMergeId = 'context-merge-test'

	def setupSpec() {
		getOrCreateContext([contextId: contextMergeId, name: 'context for Merge'])
	}

	def "POST /{context-id}/merge as guest should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextMergeId + '/merge', 'dummyBody', '',
			'text/turtle')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/merge as guest for non-existing context should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/nonexistent-ctx/merge', 'dummyBody', '',
			'text/turtle')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/merge as non-admin user should return Forbidden 403"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextMergeId + '/merge', 'dummyBody',
			'user', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /{context-id}/merge as admin for non-existing context should return Not-Found 404"() {
		when:
		def connection = EntryStoreClient.postRequest('/nonexistent-ctx/merge', 'dummyBody',
			'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/merge as member of admin group for non-existing context should return Not-Found 404"() {
		when:
		def connection = EntryStoreClient.postRequest('/nonexistent-ctx/merge', 'dummyBody',
			'userInAdminGroup', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/merge as admin with invalid RDF body should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextMergeId + '/merge',
			'this is not valid turtle', 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/merge as admin with unsupported content type should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextMergeId + '/merge',
			'some body', 'admin', 'text/plain')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/merge as admin with valid Turtle and resourceId should return 200 and create entry"() {
		given:
		def resourceId = 'merge-single-entry'
		def turtleBody = '''\
			@prefix dc: <http://purl.org/dc/terms/> .
			@prefix ex: <http://example.org/> .
			ex:subject dc:title "Merged Entry Title" .
			'''.stripIndent()

		when:
		def connection = EntryStoreClient.postRequest(
			'/' + contextMergeId + '/merge?resourceId=' + resourceId,
			turtleBody, 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK

		def entryConn = EntryStoreClient.getRequest('/' + contextMergeId + '/entry/' + resourceId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryJson = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(entryConn))
		entryJson['entryId'] == resourceId

		def metadataConn = EntryStoreClient.getRequest('/' + contextMergeId + '/metadata/' + resourceId)
		metadataConn.getResponseCode() == HTTP_OK
		def metadataBody = EntryStoreClient.getResponseBody(metadataConn)
		metadataBody.contains('Merged Entry Title')
	}

	def "POST /{context-id}/merge as admin with mergeResourceId markers should return 200 and create multiple entries"() {
		given:
		def turtleBody = '''\
			@prefix eterms: <http://entrystore.org/terms/> .
			@prefix dc: <http://purl.org/dc/terms/> .
			_:b1 eterms:mergeResourceId "multi-merge-1" .
			_:b1 dc:title "First Merged Entry" .
			_:b2 eterms:mergeResourceId "multi-merge-2" .
			_:b2 dc:title "Second Merged Entry" .
			'''.stripIndent()

		when:
		def connection = EntryStoreClient.postRequest('/' + contextMergeId + '/merge',
			turtleBody, 'admin', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK

		def entry1Conn = EntryStoreClient.getRequest('/' + contextMergeId + '/entry/multi-merge-1')
		entry1Conn.getResponseCode() == HTTP_OK
		def entry1Json = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(entry1Conn))
		entry1Json['entryId'] == 'multi-merge-1'

		def entry2Conn = EntryStoreClient.getRequest('/' + contextMergeId + '/entry/multi-merge-2')
		entry2Conn.getResponseCode() == HTTP_OK
		def entry2Json = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(entry2Conn))
		entry2Json['entryId'] == 'multi-merge-2'
	}

	def "POST /{context-id}/merge as member of admin group should return 200"() {
		given:
		def resourceId = 'merge-admin-group-entry'
		def turtleBody = '''\
			@prefix dc: <http://purl.org/dc/terms/> .
			@prefix ex: <http://example.org/> .
			ex:subject dc:title "Admin Group Merged Entry" .
			'''.stripIndent()

		when:
		def connection = EntryStoreClient.postRequest(
			'/' + contextMergeId + '/merge?resourceId=' + resourceId,
			turtleBody, 'userInAdminGroup', 'text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK

		def entryConn = EntryStoreClient.getRequest('/' + contextMergeId + '/entry/' + resourceId)
		entryConn.getResponseCode() == HTTP_OK
	}

	def "POST /{context-id}/merge as admin with format query parameter should return 200"() {
		given:
		def resourceId = 'merge-format-param-entry'
		def turtleBody = '''\
			@prefix dc: <http://purl.org/dc/terms/> .
			@prefix ex: <http://example.org/> .
			ex:subject dc:title "Format Param Entry" .
			'''.stripIndent()

		when:
		def connection = EntryStoreClient.postRequest(
			'/' + contextMergeId + '/merge?resourceId=' + resourceId + '&format=text/turtle',
			turtleBody, 'admin', 'application/octet-stream')

		then:
		connection.getResponseCode() == HTTP_OK

		def entryConn = EntryStoreClient.getRequest('/' + contextMergeId + '/entry/' + resourceId)
		entryConn.getResponseCode() == HTTP_OK
	}
}
