package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_OK

class CorsIT extends BaseSpec {

	def "Simple CORS GET with allowed origin should return Access-Control-Allow-Origin"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://example.com'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://example.com'
	}

	def "CORS request from credentials origin should return Allow-Credentials true"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://localhost:3000'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://localhost:3000'
		connection.getHeaderField('Access-Control-Allow-Credentials') == 'true'
	}

	def "CORS request from non-credentials origin should not return Allow-Credentials true"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://other.com'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://other.com'
		connection.getHeaderField('Access-Control-Allow-Credentials') != 'true'
	}

	def "Preflight OPTIONS should return allowed methods and max-age"() {
		when:
		def connection = EntryStoreClient.sendRequestAsStream(
			HttpMethod.OPTIONS, '/management/status', null, '', null,
			[Origin: 'http://example.com', 'Access-Control-Request-Method': 'PUT'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://example.com'
		connection.getHeaderField('Access-Control-Allow-Methods').contains('PUT')
		connection.getHeaderField('Access-Control-Max-Age') == '7200'
	}

	def "Preflight OPTIONS should include custom allowed headers"() {
		when:
		def connection = EntryStoreClient.sendRequestAsStream(
			HttpMethod.OPTIONS, '/management/status', null, '', null,
			[Origin: 'http://example.com', 'Access-Control-Request-Method': 'GET',
			 'Access-Control-Request-Headers': 'X-Custom-Header'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Headers').contains('X-Custom-Header')
	}

	def "CORS GET should include custom exposed headers"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://example.com'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Expose-Headers').contains('X-Custom-Header')
	}

	def "Disallowed origin should not receive CORS headers"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://disallowed.example.org'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == null
	}

	def "Suffix pattern should match origin ending with pattern"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://app.test.example.com'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://app.test.example.com'
	}

	def "Prefix pattern should match origin starting with pattern"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json',
			[Origin: 'http://prefix.anything.com'])

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://prefix.anything.com'
	}

	def "Request without Origin header should not receive CORS headers"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == null
	}

	def "Status extended endpoint should include CORS info"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended')

		then:
		connection.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['cors'] != null
		responseJson['cors']['enabled'] == true
	}
}
