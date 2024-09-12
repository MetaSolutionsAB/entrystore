package org.entrystore.rest.it


import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends Specification {

	static String host = 'localhost'
	static int port = Math.abs(new Random().nextInt() % 50000) + 10000
	static String origin = 'http://' + host + ':' + port

	def setupSpec() {
		def args = ['-c', 'file:src/test/resources/entrystore-it.properties', '-p', port.toString()] as String[]
		EntryStoreApplicationStandaloneJetty.main(args)
	}

	def "/management/status should reply with status"() {
		given:
		def connection = (HttpURLConnection) new URI(origin + '/management/status').toURL().openConnection()

		when:
		connection.connect()

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.getInputStream().text == 'UP'
	}

	def "/management/status?extended should reply with Unauthorized error for non-admin user"() {
		given:
		def connection = (HttpURLConnection) new URI(origin + '/management/status?extended=true').toURL().openConnection()

		when:
		connection.connect()

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.getErrorStream().text.contains('"error":"Not authorized"')
	}
}
