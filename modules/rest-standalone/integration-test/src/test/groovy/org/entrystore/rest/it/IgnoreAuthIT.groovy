package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_OK

class IgnoreAuthIT extends BaseSpec {

	def "GET /auth/user?ignoreAuth with valid cookie should return guest user"() {
		given:
		def cookie = EntryStoreClient.cookies['admin'].toString()

		when:
		def connection = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json', [Cookie: cookie])

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(connection.getInputStream().text)
		jsonResp['user'] == 'guest'
		jsonResp['id'] == '_guest'
	}

	def "GET /auth/user?ignoreAuth without cookie should return guest user"() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(connection.getInputStream().text)
		jsonResp['user'] == 'guest'
		jsonResp['id'] == '_guest'
	}

	def "Session should be preserved after using ignoreAuth"() {
		given:
		def cookie = EntryStoreClient.cookies['admin'].toString()

		when: "request with ignoreAuth returns guest"
		def ignoreAuthConn = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json', [Cookie: cookie])

		then:
		ignoreAuthConn.getResponseCode() == HTTP_OK
		def guestResp = JSON_PARSER.parseText(ignoreAuthConn.getInputStream().text)
		guestResp['user'] == 'guest'

		when: "subsequent request without ignoreAuth returns authenticated user"
		def normalConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookie])

		then:
		normalConn.getResponseCode() == HTTP_OK
		def authResp = JSON_PARSER.parseText(normalConn.getInputStream().text)
		authResp['user'] == 'admin'
	}

}
