package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class NameResourceIT extends BaseSpec {

	static def contextIdWithName = '70'
	static def contextIdWithoutName = '71'

	static def oldUsername = 'extraUserForNameChange'
	static def testUser

	def setupSpec() {
		getOrCreateContext([contextId: contextIdWithName, name: 'The Context Name'])
		getOrCreateContext([contextId: contextIdWithoutName])

		testUser = UserUtil.createUser(oldUsername)
	}

	def "GET /{context-id}/entry/{entry-id}/name as admin on non-existing entry should respond with 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextIdWithName + '/entry/randomEntryId/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def jsonResponse = JSON_PARSER.parseText(connection.getErrorStream().text)
		jsonResponse['error'] == 'Entry not found'
	}

	// TODO: Verify if correct behaviour - Guest has access to an entry name
	def "GET /{context-id}/entry/{entry-id}/name as guest on existing entry with name should respond with context name"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithName + '/name', '')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		json['name'] == 'The Context Name'
	}

	def "GET /{context-id}/entry/{entry-id}/name as guest on non-existing entry should respond with 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextIdWithName + '/entry/randomEntryId/name', '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def jsonResponse = JSON_PARSER.parseText(connection.getErrorStream().text)
		jsonResponse['error'] == 'Entry not found'
	}

	def "GET /{context-id}/entry/{entry-id}/name as admin on a String entry without name should return 404"() {
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
		connection.getContentType().contains('application/json')
		def jsonResponse = JSON_PARSER.parseText(connection.getErrorStream().text)
		jsonResponse['error'] == 'Entry not found'
	}

	def "GET /{context-id}/entry/{entry-id}/name as admin on a Context entry should return context name"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithName + '/name')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.getInputStream().text)
		json['name'] == 'The Context Name'
	}

	def "PUT /{context-id}/entry/{entry-id}/name as guest on a Context entry should not edit the context name, respond with 401"() {
		given:
		def newName = 'new Name / with slash symbol, and {, and }, and [ or ], plus < and >, ouh yeah'
		def body = JsonOutput.toJson([name: newName])

		when:
		def connection = EntryStoreClient.putRequest('/_contexts/entry/' + contextIdWithName + '/name', body, '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "PUT /_contexts/entry/{entry-id}/name as non-admin user on a Context entry should respond with Forbidden and not edit the context name"() {
		given:
		def newName = 'new Name / with slash symbol, and {, and }, and [ or ], plus < and >, ouh yeah'
		def body = JsonOutput.toJson([name: newName])

		when:
		def connection = EntryStoreClient.putRequest('/_contexts/entry/' + contextIdWithName + '/name', body, 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		// Restlet has it as UNAUTHORIZED, was changed in Spring
		//connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "PUT /{context-id}/entry/{entry-id}/name as admin on a Context entry should edit the context name"() {
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
		getConn.getContentType().contains('application/json')
		def jsonResponse = JSON_PARSER.parseText(getConn.getErrorStream().text)
		jsonResponse['error'].toString().contains('Entry not found')
	}

	def "GET /{context-id}/entry/{entry-id}/name on a Context without a name should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextIdWithoutName + '/name')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def jsonResponse = JSON_PARSER.parseText(connection.getErrorStream().text)
		jsonResponse['error'].toString().contains('Entry not found')
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

	def "PUT /{context-id}/entry/{entry-id}/name as guest on a User should not edit the username"() {
		given:
		def entryId = testUser['entryId'].toString()
		def newUsername = 'new username 123'
		def body = JsonOutput.toJson([name: newUsername])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body, '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == oldUsername.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name as non-admin user on another User-entry should respond with Forbidden and not edit the username"() {
		given:
		def entryId = testUser['entryId'].toString()
		def newUsername = 'new username 123'
		def body = JsonOutput.toJson([name: newUsername])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body, 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		// Restlet has it as UNAUTHORIZED, was changed in Spring
		//connection.getResponseCode() == HTTP_FORBIDDEN

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == oldUsername.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name as admin on a User should not edit the username if such name is already taken by another user"() {
		given:
		def entryId = testUser['entryId']
		def newUsername = 'userForNameChange' // name taken by user created during the tests initialization (createCommonUserAccounts)

		// verify testUser name is still $oldUsername
		def conn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		assert conn.getResponseCode() == HTTP_OK
		assert conn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(conn.getInputStream().text)['name'] == oldUsername.toLowerCase()

		def body = JsonOutput.toJson([name: newUsername])

		when: "we try to set the name to '$newUsername'"
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body)

		then: "it should reply with 409 as '$newUsername' is taken by userForNameChange user"
		connection.getResponseCode() == HTTP_CONFLICT

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == oldUsername.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name with name=null on a User should not remove user name, as a username is required for a User"() {
		given:
		def entryId = testUser['entryId'].toString()
		def newUsername = null
		def body = JsonOutput.toJson([name: newUsername])

		when: "we try to set the name to '$newUsername'"
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body)

		then: "it should reply with 400 as username is required"
		connection.getResponseCode() == HTTP_BAD_REQUEST

		// Name should stay unchanged
		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == oldUsername.toLowerCase()
	}

	def "PUT /{context-id}/entry/{entry-id}/name as non-admin user on its own User-entry should edit the username"() {
		given:
		def entryId = EntryStoreClient.createdEsUsers['userForNameChange']['entryId']
		def newUsername = 'new username 123'
		def body = JsonOutput.toJson([name: newUsername])

		when:
		def connection = EntryStoreClient.putRequest('/_principals/entry/' + entryId + '/name', body, 'userForNameChange')

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		def getConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '/name')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(getConn.getInputStream().text)
		json['name'] == newUsername.toLowerCase()
		json['name'] != 'userForNameChange'
	}

	def "PUT /{context-id}/entry/{entry-id}/name as admin on a User should edit the username"() {
		given:
		def entryId = testUser['entryId']
		def newUsername = 'totally new username 321'
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
}
