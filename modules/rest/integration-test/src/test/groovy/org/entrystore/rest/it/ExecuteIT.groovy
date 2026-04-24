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

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ExecuteIT extends BaseSpec {

	static final String CONTEXT_ID = 'execute-it'
	static final String PIPELINE_ID = 'empty-pipeline'
	static final String SOURCE_ID = 'source-entry'
	static final String PLAIN_ENTRY_ID = 'plain-entry'

	static String pipelineEntryUri
	static String sourceEntryUri
	static String plainEntryUri

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID, name: 'context for execute'])

		def pipelineId = createEntry(CONTEXT_ID, [id: PIPELINE_ID, graphtype: 'pipeline'])
		pipelineEntryUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + pipelineId

		// Declare one transform step of type "empty" on the pipeline's resource graph.
		// Pipeline.detectTransforms reads predicate http://entrystore.org/terms/transform (with transformType child).
		def pipelineTurtle = '''\
			@prefix es: <http://entrystore.org/terms/> .
			[] es:transform [ es:transformType "empty" ] .
			'''.stripIndent()
		def putPipelineGraph = EntryStoreClient.putRequest(
			'/' + CONTEXT_ID + '/resource/' + PIPELINE_ID,
			pipelineTurtle, 'admin', 'text/turtle')
		assert putPipelineGraph.getResponseCode() >= 200 && putPipelineGraph.getResponseCode() < 300

		def sourceId = createEntry(CONTEXT_ID, [id: SOURCE_ID])
		sourceEntryUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + sourceId
		// upload some bytes to make this a proper Local InformationResource with mimetype + Data
		def sourceFile = File.createTempFile('execute-it-source', '.bin')
		sourceFile.withOutputStream { out -> out.write('hello pipeline'.bytes) }
		def uploadSource = EntryStoreClient.putRequestFile(
			'/' + CONTEXT_ID + '/resource/' + SOURCE_ID, sourceFile,
			'admin', 'application/octet-stream')
		assert uploadSource.getResponseCode() >= 200 && uploadSource.getResponseCode() < 300

		def plainId = createEntry(CONTEXT_ID, [id: PLAIN_ENTRY_ID])
		plainEntryUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + plainId
	}

	def "POST /{context-id}/execute as guest should return Unauthorized 401"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri, source: sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, '', 'application/json')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/execute as non-writer user should return Forbidden 403"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri, source: sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'user', 'application/json')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /{context-id}/execute on non-existing context as admin should return Not-Found 404"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri, source: sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/nonexistent-ctx/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/execute as admin with malformed JSON should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute',
			'not-json', 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/execute as admin without 'pipeline' field should return Bad-Request 400"() {
		given:
		def body = JsonOutput.toJson([source: sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/execute as admin with pipeline URI outside the context should return Bad-Request 400"() {
		given:
		def body = JsonOutput.toJson([pipeline: EntryStoreClient.baseUrl + '/other-ctx/entry/some-id',
									  source  : sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/execute as admin with source URI outside the context should return Bad-Request 400"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri,
									  source  : EntryStoreClient.baseUrl + '/other-ctx/entry/some-id'])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/execute as admin with non-Pipeline entry as pipeline should return Conflict 409"() {
		given:
		def body = JsonOutput.toJson([pipeline: plainEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_CONFLICT
	}

	def "POST /{context-id}/execute as admin with Pipeline and valid Source should return 201 and result entries"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri, source: sourceEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['result'] instanceof List
		responseJson['result'].size() >= 1

		def resultUri = responseJson['result'][0].toString()
		resultUri.startsWith(EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/')
		resultUri != sourceEntryUri
		resultUri != pipelineEntryUri
		def resultEntryId = resultUri.substring(resultUri.lastIndexOf('/') + 1)
		def resultEntryConn = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/entry/' + resultEntryId)
		resultEntryConn.getResponseCode() == HTTP_OK
	}

	def "POST /{context-id}/execute as admin without 'source' should return 201 (source is optional)"() {
		given:
		def body = JsonOutput.toJson([pipeline: pipelineEntryUri])

		when:
		def connection = EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', body, 'admin', 'application/json')

		then:
		connection.getResponseCode() == HTTP_CREATED
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['result'] instanceof List
		responseJson['result'].size() >= 1
	}

}
