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

import org.entrystore.repository.RepositoryManager
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.http.HttpMethod

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE

class ModificationLockOutIT extends BaseSpec {

	static def contextId = '90'

	def setupSpec() {
		// Pre-create a context while the lockout is OFF; later tests use it under lockout.
		getOrCreateContext([contextId: contextId])
	}

	def cleanup() {
		// Reset after every feature method so a failure does not leak the lockout into other specs.
		setLockOut(false)
	}

	def "GET requests are allowed while modification lockout is active"() {
		given:
		setLockOut(true)

		when:
		def connection = EntryStoreClient.getRequest('/auth/user')

		then:
		connection.getResponseCode() == HTTP_OK
	}

	def "POST returns 503 with maintenance JSON envelope while modification lockout is active"() {
		given:
		setLockOut(true)

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=lockoutGroup')

		then:
		connection.getResponseCode() == HTTP_UNAVAILABLE
		connection.getContentType().contains('application/json')
		def body = JSON_PARSER.parseText(connection.errorStream.text)
		body['status'] == HTTP_UNAVAILABLE
		body['path'] == '/_principals/groups'
		body['error'] == 'The service is being maintained and does not accept modification requests right now, please check back later'
		body['timestamp'] != null
	}

	def "unauthenticated POST returns 503 (not 401) while modification lockout is active"() {
		given:
		setLockOut(true)

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=anonLockoutGroup', EntryStoreClient.emptyJsonBody, '')

		then: 'lockout filter runs before Spring Security, so anonymous writes also see 503'
		connection.getResponseCode() == HTTP_UNAVAILABLE
	}

	def "PUT returns 503 while modification lockout is active"() {
		given:
		def entryId = createEntry(contextId, [entrytype: 'link', resource: 'https://example.com/lockoutTarget'])
		setLockOut(true)

		when:
		def connection = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId,
				'{"resource":"https://example.com/updated"}')

		then:
		connection.getResponseCode() == HTTP_UNAVAILABLE
	}

	def "DELETE returns 503 while modification lockout is active"() {
		given:
		def entryId = createEntry(contextId, [entrytype: 'link', resource: 'https://example.com/lockoutTarget'])
		setLockOut(true)

		when:
		def connection = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + entryId)

		then:
		connection.getResponseCode() == HTTP_UNAVAILABLE
	}

	def "POST /auth/cookie passes through the lockout filter and reaches the login handler"() {
		given:
		setLockOut(true)

		when: 'Post invalid credentials so we can observe that the request reached the login handler'
		def body = 'auth_username=admin&auth_password=wrongpass'
		def connection = EntryStoreClient.postRequest('/auth/cookie', body, '', 'application/x-www-form-urlencoded')

		then: 'login handler runs and rejects bad creds with 401 — proves lockout filter did not short-circuit'
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/logout is allowed while modification lockout is active"() {
		given:
		def isolatedAuth = EntryStoreClient.authorize('user').toString()
		def isolatedCsrf = EntryStoreClient.csrfTokens['user']
		def userCookies = EntryStoreClient.csrfHeaders(isolatedAuth, isolatedCsrf.toString())
		setLockOut(true)

		when:
		def connection = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
	}

	def "OPTIONS preflight is allowed through the lockout filter"() {
		given:
		setLockOut(true)

		when: 'Browser sends a preflight for a cross-origin POST'
		def connection = EntryStoreClient.sendRequestAsStream(
				HttpMethod.OPTIONS, '/_principals/groups', null, '', null,
				[Origin: 'http://example.com', 'Access-Control-Request-Method': 'POST'])

		then: 'preflight succeeds (CORS handler responds with 200 + CORS headers), not blocked by the lockout filter'
		connection.getResponseCode() == HTTP_OK
		connection.getHeaderField('Access-Control-Allow-Origin') == 'http://example.com'
	}

	def "PATCH returns 503 while modification lockout is active"() {
		given: 'HttpURLConnection (used by EntryStoreClient) does not support PATCH; use java.net.http.HttpClient'
		def entryId = createEntry(contextId, [entrytype: 'link', resource: 'https://example.com/lockoutTarget'])
		setLockOut(true)

		when:
		def request = HttpRequest.newBuilder()
				.uri(URI.create(EntryStoreClient.origin + '/' + contextId + '/entry/' + entryId))
				.header('Content-Type', 'application/json')
				.header('Cookie', EntryStoreClient.cookies['admin'].toString())
				.method('PATCH', HttpRequest.BodyPublishers.ofString('{"resource":"https://example.com/patched"}'))
				.build()
		def response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())

		then:
		response.statusCode() == HTTP_UNAVAILABLE
	}

	def "POST /auth/loginx (path-prefix lookalike) is blocked while modification lockout is active"() {
		given: 'Locks the exact-match allowlist behavior — only /auth/login, /auth/cookie, /auth/logout pass through'
		setLockOut(true)

		when:
		def connection = EntryStoreClient.postRequest('/auth/loginx', '', '', 'application/x-www-form-urlencoded')

		then:
		connection.getResponseCode() == HTTP_UNAVAILABLE
	}

	def "writes succeed again after modification lockout is cleared"() {
		given:
		setLockOut(true)
		def lockedConn = EntryStoreClient.postRequest('/_principals/groups?name=lockoutGroupTwo')
		assert lockedConn.getResponseCode() == HTTP_UNAVAILABLE

		when:
		setLockOut(false)
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=lockoutGroupTwo')

		then:
		connection.getResponseCode() == HTTP_CREATED
	}

	private static void setLockOut(boolean lockout) {
		appInstance.getBean(RepositoryManager.class).setModificationLockOut(lockout)
	}
}
