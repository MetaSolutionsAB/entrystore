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

import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class AuthChallengeIT extends BaseSpec {

	def "GET as guest should return 401 with WWW-Authenticate header when Basic Auth is enabled"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getHeaderField('WWW-Authenticate') != null
		connection.getHeaderField('WWW-Authenticate').contains('Basic')
		connection.getContentType().contains('application/json')
	}

	def "GET as guest with auth_challenge=false should return 401 without WWW-Authenticate header"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?auth_challenge=false', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getHeaderField('WWW-Authenticate') == null
		connection.getContentType().contains('application/json')
	}

	def "GET as guest with auth_challenge=true should return 401 with WWW-Authenticate header"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?auth_challenge=true', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getHeaderField('WWW-Authenticate') != null
		connection.getHeaderField('WWW-Authenticate').contains('Basic')
		connection.getContentType().contains('application/json')
	}

	def "GET as guest with auth_challenge=FALSE (uppercase) should suppress WWW-Authenticate header"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?auth_challenge=FALSE', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getHeaderField('WWW-Authenticate') == null
		connection.getContentType().contains('application/json')
	}

	def "GET with invalid Basic credentials and auth_challenge=false should return 401 without WWW-Authenticate header"() {
		when:
		def invalidBasicAuth = 'Basic ' + Base64.getEncoder().encodeToString('baduser:badpass'.getBytes())
		def connection = EntryStoreClient.getRequest(
				'/management/status/extended?auth_challenge=false', '',
				'application/json', ['Authorization': invalidBasicAuth])

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getHeaderField('WWW-Authenticate') == null
		connection.getContentType().contains('application/json')
	}
}
