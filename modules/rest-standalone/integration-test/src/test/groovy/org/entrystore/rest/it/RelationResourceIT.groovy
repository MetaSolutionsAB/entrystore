package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK

class RelationResourceIT extends BaseSpec {

	def static contextId = '60'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/relations/{entry-id} on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/randomEntryId')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getErrorStream().text)
		json['error'] == 'Entry not found'
	}

	def "GET /{context-id}/relations/{entry-id} on a String entry should return no relations"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/' + entryId)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		// empty json = no relations for this entry
		(json as Map).keySet().size() == 0
	}

	def "GET /{context-id}/relations/{entry-id} on a Context entry should return relation to home context"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		(json as Map).keySet().size() == 1
		def relationJsonKey = (json as Map).keySet()[0].toString()
		json[relationJsonKey] == [(NameSpaceConst.TERM_HOME_CONTEXT): [[type : 'uri',
																		value: EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId]]]
	}
}
