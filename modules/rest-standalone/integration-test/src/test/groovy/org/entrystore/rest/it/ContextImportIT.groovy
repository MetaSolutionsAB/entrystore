package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.eclipse.jetty.http.HttpMethod
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ContextImportIT extends BaseSpec {

	def static contextExportId = 'context-export'
	def static contextImportId = 'context-import-test'
	def static entryIdInExportOriginally = 'export-entry-id'
	def static entryId2InImportOriginally = 'import-entry-id-should-be-overridden'
	def static resourceUrl = 'https://bbc.co.uk'
	static def password = 'newPass12345'
	static def genericCredsClone = [:]

	def setupSpec() {
		getOrCreateContext([contextId: contextExportId, name: 'context for Export'])
		def params = [entrytype: 'link', resource: resourceUrl, id: entryIdInExportOriginally]
		getOrCreateEntry(contextExportId, params)

		getOrCreateContext([contextId: contextImportId, name: 'context for Import'])
		def params2 = [entrytype: 'link', resource: resourceUrl, id: entryId2InImportOriginally]
		getOrCreateEntry(contextImportId, params2)

		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForImport@test.com', password)
		EntryStoreClient.creds.put('userForImportAdminGroup@test.com', password)
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
	}

	def "POST /{context-id}/import as guest should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import as guest for non-existing context should return Unauthorized 401"() {
		when:
		def contextId = 'non-existing-context-id'
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import as authorized but non-admin should return Forbidden 403"() {
		given:
		def username = 'userForImport@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK

		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '', username)

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /{context-id}/import as admin for non-existing context should return Not-Found 404"() {
		given:
		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/import as member of admin group for non-existing context should return Not-Found 404"() {
		given:
		def username = 'userForImportAdminGroup@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()
		def resourceUri = user['resourceUri'].toString()
		def requestBody = JsonOutput.toJson([entryId])
		assert EntryStoreClient.putRequest('/_principals/resource/_admins', requestBody).getResponseCode() == HTTP_NO_CONTENT
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK

		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/import as admin with invalid zip-file should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '[]',
			'admin', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/import as admin with zip-file as body should import context from the file, overriding existing entries"() {
		given:
		// check existing list of entries for context that will be overridden
		def contextConn = EntryStoreClient.getRequest('/' + contextImportId)
		assert contextConn.getResponseCode() == HTTP_OK
		assert contextConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(contextConn.getInputStream().text) == [entryId2InImportOriginally]

		// check context name for context that will be overridden
		def contextNameConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		assert contextNameConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(contextNameConn.getInputStream().text) == [name: 'context for Import']

		// get the ZIP file by exporting context with ID `contextExportId`
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK

		when:
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + contextImportId + '/import',
			exportConn.getInputStream(), 'admin', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		connection.getInputStream().text == '<textarea></textarea>'

		def conn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def getResponseJson = JSON_PARSER.parseText(conn.getInputStream().text)
		getResponseJson['entryId'] == contextImportId
		getResponseJson['info'] != null

		// check if overridden context changed it's entries
		def contextConn2 = EntryStoreClient.getRequest('/' + contextImportId)
		contextConn2.getResponseCode() == HTTP_OK
		contextConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(contextConn2.getInputStream().text) == [entryIdInExportOriginally]

		// check if overridden context changed it's name
		def contextNameConn2 = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		contextNameConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(contextNameConn2.getInputStream().text) == [name: 'context for Import']
	}

	def "POST /{context-id}/import with multi-part file as body should import context from the file, overriding existing entries"() {
		given:
		def contextImportId = 'context-import-test2'
		getOrCreateContext([contextId: contextImportId, name: 'context for Import 2'])

		// check existing list of entries for context that will be overridden
		def contextConn = EntryStoreClient.getRequest('/' + contextImportId)
		assert contextConn.getResponseCode() == HTTP_OK
		assert contextConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(contextConn.getInputStream().text) == []

		// check context name for context that will be overridden
		def contextNameConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		assert contextNameConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(contextNameConn.getInputStream().text) == [name: 'context for Import 2']

		// get the ZIP file by exporting context with ID `contextExportId`
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK
		def tempFile = File.createTempFile('export-file', '.zip')
		tempFile.withOutputStream { out ->
			out << exportConn.getInputStream()
		}

		when:
		def connection = EntryStoreClient.postRequestMultiPart('/' + contextImportId + '/import',
			tempFile, 'admin')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		connection.getInputStream().text == '<textarea></textarea>'

		def conn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def getResponseJson = JSON_PARSER.parseText(conn.getInputStream().text)
		getResponseJson['entryId'] == contextImportId
		getResponseJson['info'] != null

		// check if overridden context changed it's entries
		def contextConn2 = EntryStoreClient.getRequest('/' + contextImportId)
		contextConn2.getResponseCode() == HTTP_OK
		contextConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(contextConn2.getInputStream().text) == [entryIdInExportOriginally]

		// check if overridden context changed it's name
		def contextNameConn2 = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		contextNameConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(contextNameConn2.getInputStream().text) == [name: 'context for Import 2']
	}
}
