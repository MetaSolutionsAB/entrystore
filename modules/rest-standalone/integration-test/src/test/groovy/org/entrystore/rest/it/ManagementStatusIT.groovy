package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends BaseSpec {

	def "GET /management/status as guest should reply with text status, when no Accept header defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', '')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.getInputStream().text == 'UP'
	}

	def "GET /management/status as guest should reply with json status, when json Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['repositoryStatus'] == 'online'
		responseJson['version'] != null
		(responseJson['version'] as String).length() > 2
	}

	def "GET /management/status?extended as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status?extended=true', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}

	def "GET /management/status?extended as non-admin user should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status?extended=true', 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}

	def "GET /management/status?extended as admin should reply with detailed status"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status?extended=true')

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

	def "GET /management/status?extended&includeStats as admin should reply with detailed status"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status?extended=true&includeStats')

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
		responseJson['stats'] != null
		responseJson['stats']['groupCount'] != null
		responseJson['stats']['userCount'] != null
		responseJson['stats']['contextCount'] != null
		responseJson['stats']['tripleCount'] != null
		responseJson['stats']['namedGraphCount'] != null
	}

	def "GET /management/status?extended&includeStats as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status?extended=true&includeStats', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}
}
