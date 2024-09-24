package org.entrystore.rest.it

import groovy.json.JsonSlurper

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends BaseSpec {

	def "GET /management/status should reply with status UP, when no Accept header defined"() {
		when:
		def connection = client.getRequest('/management/status', null, null)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.getInputStream().text == 'UP'
	}

	def "GET /management/status should reply with json status UP, when json Accept header is defined"() {
		when:
		def connection = client.getRequest('/management/status')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['repositoryStatus'] == 'online'
		responseJson['version'] != null
		(responseJson['version'] as String).length() > 2
	}

	def "GET /management/status?extended should reply with Unauthorized error for non-admin user"() {
		when:
		def connection = client.getRequest('/management/status?extended=true')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}

	def "GET /management/status?extended should reply with detailed status for admin user"() {
		when:
		def connection = client.getRequest('/management/status?extended=true', 'admin')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson.version != null
		responseJson.jvm != null
		responseJson.baseURI != null
		responseJson.repositoryType == 'memory'
		responseJson.solr != null
		responseJson.solr.enabled
		responseJson.solr.status == 'online'
		responseJson.startupTime != null
	}
}
