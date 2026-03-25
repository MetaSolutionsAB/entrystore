/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class IgnoreAuthIT extends BaseSpec {

	def static contextId = '80'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /auth/user?ignoreAuth with valid cookie should return guest user"() {
		given:
		def cookie = EntryStoreClient.cookies['admin'].toString()

		when:
		def connection = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json', [Cookie: cookie])

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(connection))
		jsonResp['user'] == 'guest'
		jsonResp['id'] == '_guest'
	}

	def "GET /auth/user?ignoreAuth without cookie should return guest user"() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(connection))
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
		def guestResp = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(ignoreAuthConn))
		guestResp['user'] == 'guest'

		when: "subsequent request without ignoreAuth returns authenticated user"
		def normalConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookie])

		then:
		normalConn.getResponseCode() == HTTP_OK
		def authResp = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(normalConn))
		authResp['user'] == 'admin'
	}

	def "GET protected resource with ignoreAuth should return 401"() {
		given: "admin cookie"
		def cookie = EntryStoreClient.cookies['admin'].toString()

		when: "request contexts list with ignoreAuth using admin cookie"
		def connection = EntryStoreClient.getRequest('/_contexts?ignoreAuth', '', 'application/json', [Cookie: cookie])

		then: "should be treated as guest and denied access"
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST create entry with ignoreAuth should return 401"() {
		given:
		def cookie = EntryStoreClient.cookies['admin'].toString()
		def params = [entrytype: 'link', resource: 'https://example.com/test']

		when: "attempt to create an entry in _principals context with ignoreAuth using admin cookie"
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params) + '&ignoreAuth', '', '', 'application/json', [Cookie: cookie])

		then: "should be treated as guest and denied access"
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

}
