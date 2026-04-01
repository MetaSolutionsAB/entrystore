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

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED


class LogoutIT extends BaseSpec {

	// In the logout tests below we query endpoints with manually attached user cookies, this is for test clarity
	// and to not invalidate the shared cookie in "EntryStoreClient.cookies['user']"

	def 'GET /auth/logout as non-admin user in JSON format should logout the user'() {
		given:
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn))
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		userConn2.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(userConn2.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'Session expired or invalid'
		responseJson['status'] != null
		responseJson['timestamp'] != null
	}

	def 'GET /auth/logout as non-admin user in HTML format should logout the user'() {
		given:
		// Intentional difference from Restlet: Restlet returned 200 OK with an HTML body for Accept: text/html,
		// but the Spring Boot version always returns 204 No-Content regardless of the Accept header.
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn))
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'text/html', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		userConn2.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(userConn2.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'Session expired or invalid'
		responseJson['status'] != null
		responseJson['timestamp'] != null
	}

	def 'GET /auth/logout as guest in JSON format should silently do nothing as there is no session to logout'() {
		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '')

		then: 'we\'re a guest'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn))
		jsonUserConn['user'] == 'guest'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '')

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '')

		then: 'we should get a OK with a guest user'
		userConn2.getResponseCode() == HTTP_OK
		userConn2.getContentType().contains('application/json')
		def jsonUserConn2 = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn2))
		jsonUserConn2['user'] == 'guest'
	}

	def 'GET /auth/logout as admin in JSON format should logout the user'() {
		given:
		def userCookies = [Cookie: EntryStoreClient.authorize('admin').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the admin'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn))
		jsonUserConn['user'] == 'admin'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		userConn2.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(userConn2.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'Session expired or invalid'
		responseJson['status'] != null
		responseJson['timestamp'] != null
	}

	def 'POST /auth/logout as user in JSON format should logout the user'() {
		given:
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(EntryStoreClient.getResponseBody(userConn))
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'user should be logged out'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		userConn2.getContentType().contains('application/json')
		def jsonUserConn2 = JSON_PARSER.parseText(userConn2.getErrorStream().text)
		jsonUserConn2['error'] != null
		jsonUserConn2['error'] == 'Session expired or invalid'
		jsonUserConn2['status'] != null
		jsonUserConn2['timestamp'] != null
	}
}
