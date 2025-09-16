package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class NameResourceIT extends BaseSpec {

	def static contextIdWithName = '70'
	def static contextIdWithoutName = '71'

	def setupSpec() {
		getOrCreateContext([contextId: contextIdWithName, name: 'The Context Name'])
		getOrCreateContext([contextId: contextIdWithoutName])
	}

	def "GET /{context-id}/entry/{entry-id}/name on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextIdWithName + '/entry/randomEntryId/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/octet-stream') // content-type is octet-stream for some reason, no body
	}

	def "GET /{context-id}/entry/{entry-id}/name on a String entry without name should return 404"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextIdWithName, params, body)
		assert entryId.length() > 0

		when:
		def connection = EntryStoreClient.getRequest('/' + contextIdWithName + '/entry/' + entryId + '/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/octet-stream') // content-type is octet-stream for some reason, no body
	}

	def "GET /{context-id}/entry/{entry-id}/name on a Context entry should return context name"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithName + '/name')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		json['name'] == 'The Context Name'
	}

	def "PUT /{context-id}/entry/{entry-id}/name on a Context entry should edit the context name"() {
		given:
		def newName = 'new Name / with slash symbol, and {, and }, and [ or ], plus < and >, ouh yeah'
		def body = JsonOutput.toJson([name: newName])

		when:
		def connection = EntryStoreClient.putRequest('/_contexts/entry/' + contextIdWithName + '/name', body)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithName + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == newName
	}

	def "PUT /{context-id}/entry/{entry-id}/name with null name on a Context entry should remove the context name"() {
		given:
		def newName = null
		def body = JsonOutput.toJson([name: newName])

		when:
		def connection = EntryStoreClient.putRequest('/_contexts/entry/' + contextIdWithName + '/name', body)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithName + '/name')
		getConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/entry/{entry-id}/name on a Context without a name should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithoutName + '/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "PUT /{context-id}/entry/{entry-id}/name on a Context without name should add the context name"() {
		given:
		def newName = 'totally new Name'
		def body = JsonOutput.toJson([name: newName])

		when:
		def connection = EntryStoreClient.putRequest('/_contexts/entry/' + contextIdWithoutName + '/name', body)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithoutName + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == newName
	}

	def "PUT /{context-id}/entry/{entry-id}/name on a User should edit the username"() {
		given:
		def username = 'Username 1'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()

		def newUsername = 'totally new Name'
		def body = JsonOutput.toJson([name: newUsername])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == newUsername.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name with name=null on a User should not remove user name, as a username is required for a User"() {
		given:
		def username = 'Username 2'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId']

		def newUsername = null
		def body = JsonOutput.toJson([name: newUsername])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST

		// Name should stay unchanged
		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == username.toLowerCase()
	}
}
