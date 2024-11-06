package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.*
import static java.nio.charset.StandardCharsets.UTF_8

class NameResourceIT extends BaseSpec {

	def static contextIdWithName = '70'
	def static contextIdWithoutName = '71'

	def setupSpec() {
		getOrCreateContext([contextId: contextIdWithName, name: URLEncoder.encode('The Context Name', UTF_8)])
		getOrCreateContext([contextId: contextIdWithoutName])
	}

	def "GET /{context-id}/entry/{entry-id}/name on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextIdWithName + '/entry/randomEntryId/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/octet-stream') // content-type is octet-stream for some reason, no body
	}

	def "GET /{context-id}/entry/{entry-id}/name on a String entry should return 404"() {
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

	def "PUT /{context-id}/entry/{entry-id}/name on a User should edit the user name"() {
		given:
		// create user
		def requestResourceName = [name: 'Test User name']
		def params = [graphtype: 'user']
		def createUserBody = JsonOutput.toJson([resource: requestResourceName])
		def createUserConn = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), createUserBody)
		assert createUserConn.getResponseCode() == HTTP_CREATED
		assert createUserConn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(createUserConn.getInputStream().text)
		assert responseJson['entryId'] != null
		def userEntryId = responseJson['entryId'].toString()

		def newUserName = 'totally new Name'
		def body = JsonOutput.toJson([name: newUserName])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + userEntryId + '/name', body)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == newUserName.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name with name=null on a User should not remove user name, as username is required for a User"() {
		given:
		// create user
		def requestResourceName = [name: 'Test User name']
		def params = [graphtype: 'user']
		def createUserBody = JsonOutput.toJson([resource: requestResourceName])
		def createUserConn = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), createUserBody)
		assert createUserConn.getResponseCode() == HTTP_CREATED
		assert createUserConn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(createUserConn.getInputStream().text)
		assert responseJson['entryId'] != null
		def userEntryId = responseJson['entryId'].toString()

		def newUserName = null
		def body = JsonOutput.toJson([name: newUserName])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + userEntryId + '/name', body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST

		// Name should stay unchanged
		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == requestResourceName['name'].toLowerCase()
	}
}
