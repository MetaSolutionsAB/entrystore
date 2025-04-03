package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends BaseSpec {

	// Spring boot defaults to JSON when no content type is specified

	def "GET /management/status should reply with text status UP, when text Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', null, 'text/plain')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.getInputStream().text == 'UP'
	}

	def "GET /management/status should reply with json status, when no Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', null, null)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['repositoryStatus'] == 'online'
		responseJson['version'] != null
		(responseJson['version'] as String).length() > 2
	}

	def "GET /management/status?extended should reply with Unauthorized error for non-admin user"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended', null)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Unauthorized"')
	}

	def "GET /management/status/extended should reply with detailed status for admin user"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['version'] != null
		responseJson['jvm'] != null
		responseJson['baseURI'] != null
		responseJson['repositoryType'] == 'memory'
		responseJson['solr'] != null
		responseJson['solr']['enabled']
		responseJson['solr']['status'] == 'online'
		responseJson['startupTime'] != null
		responseJson['stats'] == null
	}

	def "GET /management/status/extended?include=countStats should reply with detailed status and stats for admin user"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?include=countStats')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['version'] != null
		responseJson['jvm'] != null
		responseJson['baseURI'] != null
		responseJson['repositoryType'] == 'memory'
		responseJson['solr'] != null
		responseJson['solr']['enabled']
		responseJson['solr']['status'] == 'online'
		responseJson['startupTime'] != null
		responseJson['countStats'] != null
		responseJson['countStats']['groupCount'] != null
		responseJson['countStats']['userCount'] != null
		responseJson['countStats']['contextCount'] != null
		responseJson['countStats']['tripleCount'] != null
		responseJson['countStats']['namedGraphCount'] != null
	}

	def "GET /management/status/extended&includeStats should reply with Unauthorized for non-admin user"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended&includeStats', null)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Unauthorized"')
	}
}
