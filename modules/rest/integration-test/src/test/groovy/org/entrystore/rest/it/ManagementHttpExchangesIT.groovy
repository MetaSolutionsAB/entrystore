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

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementHttpExchangesIT extends BaseSpec {

	def setupSpec() {
		// Record at least one exchange before the specs that assert the buffer is populated. setupSpec
		// completes before any feature method, so the recording filter's post-response bookkeeping has
		// run by the time the assertions execute.
		def warmup = EntryStoreClient.getRequest('/management/status', '', 'text/plain')
		assert warmup.getResponseCode() == HTTP_OK
	}

	def "GET /management/httpexchanges as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/httpexchanges', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/httpexchanges as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/httpexchanges', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/httpexchanges as admin should list recorded exchanges"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/httpexchanges')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		def exchanges = responseJson['exchanges'] as List
		exchanges.size() > 0
		// Each recorded exchange carries its request method/URI and the response status.
		def first = exchanges[0]
		first['request']['method'] != null
		first['request']['uri'] != null
		first['response']['status'] != null
	}

	def "GET /management/httpexchanges as userInAdminGroup should list recorded exchanges"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/httpexchanges', 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		(responseJson['exchanges'] as List).size() > 0
	}

	def "recorded exchanges should include normal request headers but never Cookie or Authorization"() {
		given: 'an authenticated request that carries the admin session cookie is recorded'
		def warmup = EntryStoreClient.getRequest('/management/status')
		assert warmup.getResponseCode() == HTTP_OK

		when:
		def connection = EntryStoreClient.getRequest('/management/httpexchanges')

		then:
		connection.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		def exchanges = responseJson['exchanges'] as List
		exchanges.size() > 0
		// request-headers recording is on, so ordinary headers are captured...
		exchanges.any { !((it['request']['headers'] ?: [:]) as Map).isEmpty() }
		// ...but cookie-headers and authorization-header are excluded from recording.include, so the
		// auth_token cookie that authenticated requests send is never written into the buffer.
		exchanges.every { exchange ->
			def headerNames = ((exchange['request']['headers'] ?: [:]) as Map).keySet().collect { it.toLowerCase() }
			!headerNames.contains('cookie') && !headerNames.contains('authorization')
		}
	}
}
