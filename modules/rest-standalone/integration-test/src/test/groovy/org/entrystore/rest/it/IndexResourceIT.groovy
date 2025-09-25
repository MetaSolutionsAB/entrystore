package org.entrystore.rest.it

import org.awaitility.core.ConditionEvaluationLogger
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

class IndexResourceIT extends BaseSpec {

	def static contextId = '60'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/entry/{entry-id}/index on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/randomEntryId/index')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getErrorStream().text)
		json['error'] == 'Entry not found'
	}

	def "GET /{context-id}/entry/{entry-id}/index on a String entry should return index info"() {
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
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		json['entryType'] == 'Local'
		json['graphType'] == 'String'
		json['rdfType'] == NameSpaceConst.TERM_STRING
	}

	def "GET /{context-id}/entry/{entry-id}/index on a Context entry should return context index"() {
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
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		json['entryType'] == 'Local'
		json['graphType'] == 'Context'
		json['rdfType'] == NameSpaceConst.TERM_CONTEXT
	}
}
