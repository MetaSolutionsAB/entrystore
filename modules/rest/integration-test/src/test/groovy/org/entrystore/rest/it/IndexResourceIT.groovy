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

import org.awaitility.core.ConditionEvaluationLogger
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static org.awaitility.Awaitility.await

class IndexResourceIT extends BaseSpec {

	def static contextId = '60'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/entry/{entry-id}/index as guest on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/randomEntryId/index', '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == "No entry with id 'randomEntryId' found in context '${contextId}'"
	}

	def "GET /{context-id}/entry/{entry-id}/index as admin on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/randomEntryId/index')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == 'No entry with id \'randomEntryId\' found in context \'60\''
	}

	def "GET /{context-id}/entry/{entry-id}/index as guest should respond with Unauthorized 401"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		// The Administer-ACL check fires before the Solr lookup, so the response is the same regardless
		// of indexing state — no need to poll.
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '/index', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		JSON_PARSER.parseText(connection.errorStream.text)['error'] == 'Not authorized'
	}

	def "GET /{context-id}/entry/{entry-id}/index as non-admin user should respond with Forbidden"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def connection = null
		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.pollInterval(100, TimeUnit.MILLISECONDS)
			.atMost(20, TimeUnit.SECONDS)
			.until({
				connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '/index', 'user')
				return connection.getResponseCode() != HTTP_NOT_FOUND
			})

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "GET /{context-id}/entry/{entry-id}/index as admin on a String entry should return index info"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def connection = null
		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.pollInterval(100, TimeUnit.MILLISECONDS)
			.atMost(20, TimeUnit.SECONDS)
			.until({
				connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '/index')
				return connection.getResponseCode() != HTTP_NOT_FOUND
			})

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['entryType'] == 'Local'
		json['graphType'] == 'String'
		json['rdfType'] == NameSpaceConst.TERM_STRING
	}

	def "GET /{context-id}/entry/{entry-id}/index as admin on a Context entry should return context index"() {
		when:
		def connection = null

		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.pollInterval(100, TimeUnit.MILLISECONDS)
			.atMost(20, TimeUnit.SECONDS)
			.until({
				connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextId + '/index')
				return connection.getResponseCode() != HTTP_NOT_FOUND
			})

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['entryType'] == 'Local'
		json['graphType'] == 'Context'
		json['rdfType'] == NameSpaceConst.TERM_CONTEXT
	}
}
