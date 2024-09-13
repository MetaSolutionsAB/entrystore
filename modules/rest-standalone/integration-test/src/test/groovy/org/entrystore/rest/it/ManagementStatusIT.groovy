package org.entrystore.rest.it

import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends Specification {

	static client

	def setupSpec() {
		def args = ['-c', 'file:src/test/resources/entrystore-it.properties', '-p', EntryStoreClient.port.toString()] as String[]
		EntryStoreApplicationStandaloneJetty.main(args)
		client = new EntryStoreClient()
	}

	def "/management/status should reply with status"() {
		when:
		def connection = client.getRequest('/management/status')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.getInputStream().text == 'UP'
	}

	def "/management/status?extended should reply with Unauthorized error for non-admin user"() {
		when:
		def connection = client.getRequest('/management/status?extended=true')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}

	def "/management/status?extended should reply with detailed status for admin user"() {
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
