package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ContextImportIT extends BaseSpec {

	def static contextExportId = 'context-export'
	def static contextImportId = 'context-import-test'
	def static entryId = 'export-entry-id'
	def static entryId2 = 'import-entry-id-should-be-overridden'
	def static resourceUrl = 'https://bbc.co.uk'

	def setupSpec() {
		getOrCreateContext([contextId: contextExportId, name: 'contextExported'])
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		getOrCreateEntry(contextExportId, params)

		getOrCreateContext([contextId: contextImportId, name: 'contextImported'])
		def params2 = [entrytype: 'link', resource: resourceUrl, id: entryId2]
		getOrCreateEntry(contextImportId, params2)
	}

	def "POST /{context-id}/import as non-admin and for non-existing context should return Bad-Request 400"() {
		given:
		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/import as non-admin on existing context should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import should import context from a zip file, overriding existing entries"() {
		given:
		// check existing list of entries for context that will be overridden
		def contextConn = EntryStoreClient.getRequest('/' + contextImportId)
		contextConn.getResponseCode() == HTTP_OK
		assert contextConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(contextConn.getInputStream().text) == [entryId2]

		// check context name for context that will be overridden
		def contextNameConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		assert contextNameConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(contextNameConn.getInputStream().text) == [name: 'contextImported']

		// get the ZIP file by exporting context with ID `contextExportId`
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK

		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', null, 'admin', 'application/zip')
		connection.setDoOutput(true)
		exportConn.getInputStream().transferTo(connection.getOutputStream())
		connection.connect()

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
		JSON_PARSER.parseText(contextConn2.getInputStream().text) == [entryId]

		// check if overridden context changed it's name
		def contextNameConn2 = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		contextNameConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(contextNameConn2.getInputStream().text) == [name: 'contextImported']
	}
}
