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

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class TokenResourceIT extends BaseSpec {

	static def dtf = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
		.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
		.toFormatter()
	static def password = 'newPass12345'
	static final String DELETE_NOTHING_USER = 'userForTokenDeleteNothing@test.com'

	def setupSpec() {
		EntryStoreClient.snapshotCreds()
		EntryStoreClient.creds.put('userForTokenManagement@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDelete@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDeleteCurrent@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementUpdate@test.com', password)
		EntryStoreClient.creds.put('userForTokenHandleReload@test.com', password)
		EntryStoreClient.creds.put('userForTokenHandleRelogin@test.com', password)
		EntryStoreClient.creds.put(DELETE_NOTHING_USER, password)
		UserUtil.createUserWithPassword(DELETE_NOTHING_USER, password)
	}

	def cleanupSpec() {
		EntryStoreClient.restoreCreds()
	}

	def "GET /auth/tokens should get unauthorized for a non-authenticated user"() {
		when:
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '')

		then:
		tokensConnection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /auth/tokens should list the user's logins by session handle and mark the current one"() {
		given:
		def username = 'userForTokenManagement@test.com'
		UserUtil.createUserWithPassword(username, password)
		def cookie1 = loginWithMaxAge(username, '100')
		def cookie2 = loginWithMaxAge(username, '50')

		when:
		def connection1 = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie1])
		def body1 = connection1.inputStream.text
		def connection2 = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie2])
		def body2 = connection2.inputStream.text

		then:
		connection1.getResponseCode() == HTTP_OK
		def tokens1 = JSON_PARSER.parseText(body1) as Map
		tokens1.size() == 2
		tokens1.keySet().every { it ==~ /[A-Za-z0-9_-]{22}/ }
		tokens1.findAll { it.value['current'] }.size() == 1
		def handle1 = tokens1.find { it.value['current'] }.key
		def handle2 = tokens1.find { !it.value['current'] }.key
		tokens1[handle1]['userName'] == 'userfortokenmanagement@test.com'
		tokens1[handle1]['lastAccessTime'] != null
		tokens1[handle1]['loginExpiration'] != null
		tokens1[handle1]['loginTime'] != null
		tokens1[handle1]['lastUsedIpAddress'] != null
		tokens1[handle1]['loginTokenMaxAge'] == 100
		tokens1[handle1]['lastUsedUserAgent'] != null
		tokens1[handle2]['userName'] == 'userfortokenmanagement@test.com'
		tokens1[handle2]['loginTokenMaxAge'] == 50

		and: 'the other login sees the same handles, with itself as current'
		connection2.getResponseCode() == HTTP_OK
		def tokens2 = JSON_PARSER.parseText(body2) as Map
		tokens2.keySet() == tokens1.keySet()
		tokens2[handle2]['current'] == true
		tokens2[handle1]['current'] == false

		and: 'neither response carries a session id'
		EntryStoreClient.assertNoSessionIds(connection1, body1, [cookie1, cookie2])
		EntryStoreClient.assertNoSessionIds(connection2, body2, [cookie1, cookie2])
	}

	def "GET /auth/tokens should report the same handle with updated timestamps on the next request"() {
		given:
		def username = 'userForTokenManagementUpdate@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login = EntryStoreClient.loginIsolated(username)
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])
		assert tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text) as Map
		def handle = tokensRespJson.find { it.value['current'] }.key
		def oldLastAccessTime = LocalDateTime.parse(tokensRespJson[handle]['lastAccessTime'].toString(), dtf)
		def oldLoginExpiration = LocalDateTime.parse(tokensRespJson[handle]['loginExpiration'].toString(), dtf)
		def oldLoginTime = tokensRespJson[handle]['loginTime']

		when:
		// the assertions below truncate to whole milliseconds (ChronoUnit.MILLIS.between(...) > 0),
		// so the second request has to land in a strictly later millisecond to be observable
		Thread.sleep(5)
		def tokensNewConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])

		then:
		tokensNewConnection.getResponseCode() == HTTP_OK
		def tokensNewRespJson = JSON_PARSER.parseText(tokensNewConnection.inputStream.text) as Map
		tokensNewRespJson.keySet() == [handle] as Set
		tokensNewRespJson[handle]['current'] == true
		def newLastAccessTime = LocalDateTime.parse(tokensNewRespJson[handle]['lastAccessTime'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLastAccessTime, newLastAccessTime) > 0
		def newLoginExpiration = LocalDateTime.parse(tokensNewRespJson[handle]['loginExpiration'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLoginExpiration, newLoginExpiration) > 0
		oldLoginTime == tokensNewRespJson[handle]['loginTime']
	}

	def "GET /auth/tokens should report the same handle after the user's properties are reloaded"() {
		given: 'the default entrystore.auth.session.reload-interval of 10 seconds'
		def username = 'userForTokenHandleReload@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login = EntryStoreClient.loginIsolated(username)
		def handle = EntryStoreClient.currentSessionHandle(login.authCookie)

		when: 'the next request reloads the user'
		Thread.sleep(10_500)
		def handleOfTheReloadingRequest = EntryStoreClient.currentSessionHandle(login.authCookie)

		then:
		handleOfTheReloadingRequest == handle
		EntryStoreClient.currentSessionHandle(login.authCookie) == handle
	}

	def "a login with a valid session cookie should get a new session id and a new handle"() {
		given:
		def username = 'userForTokenHandleRelogin@test.com'
		UserUtil.createUserWithPassword(username, password)
		def first = EntryStoreClient.loginIsolated(username)
		def firstHandle = EntryStoreClient.currentSessionHandle(first.authCookie)

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie',
				createFormBody([auth_username: username, auth_password: password]), '',
				'application/x-www-form-urlencoded', [Cookie: first.authCookie])

		then:
		loginConnection.getResponseCode() == HTTP_OK
		def second = EntryStoreClient.findSetCookies(loginConnection, 'auth_token')
				.find { EntryStoreClient.parseSetCookieAttributes(it)['max-age'] != '0' }
		EntryStoreClient.sessionIdOf(second) != EntryStoreClient.sessionIdOf(first.authCookie)
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', 'application/json', [Cookie: second])
		tokensConnection.getResponseCode() == HTTP_OK
		def body = tokensConnection.inputStream.text
		def tokens = JSON_PARSER.parseText(body) as Map
		tokens.size() == 1
		tokens.values().first()['current'] == true
		tokens.keySet().first() != firstHandle
		EntryStoreClient.assertNoSessionIds(tokensConnection, body, [first.authCookie, second])

		and: 'the session id before the login no longer authenticates'
		EntryStoreClient.getRequest('/auth/tokens', '', 'application/json', [Cookie: first.authCookie])
				.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should get unauthorized for a non-authenticated user"() {
		when:
		def tokensConnection = EntryStoreClient.deleteRequest('/auth/tokens', '[]', '')

		then:
		tokensConnection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should end the session with the given handle and keep the other one"() {
		given:
		def username = 'userForTokenManagementDelete@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login1 = EntryStoreClient.loginIsolated(username)
		def handle1 = EntryStoreClient.currentSessionHandle(login1.authCookie)
		def login2 = EntryStoreClient.loginIsolated(username)
		def handle2 = EntryStoreClient.currentSessionHandle(login2.authCookie)
		def body = JsonOutput.toJson([token: handle2])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login1.authCookie, login1.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.assertNoSessionIds(tokensDeleteConnection, '', [login1.authCookie, login2.authCookie])
		def token1Connection = EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login1.authCookie])
		token1Connection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(token1Connection.inputStream.text) as Map
		tokensRespJson.keySet() == [handle1] as Set
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login2.authCookie])
				.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens with the handle of the current session should log the user out"() {
		given:
		def username = 'userForTokenManagementDeleteCurrent@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login = EntryStoreClient.loginIsolated(username)
		def body = JsonOutput.toJson([token: EntryStoreClient.currentSessionHandle(login.authCookie)])
		assert EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_OK

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login.authCookie, login.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens with #kind should answer 204 and end no session"() {
		given:
		def login = EntryStoreClient.loginIsolated(DELETE_NOTHING_USER)
		def otherUserLogin = EntryStoreClient.loginIsolated('user')
		def body = JsonOutput.toJson([token: tokenOf(login.authCookie, otherUserLogin.authCookie)])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login.authCookie, login.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_OK
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: otherUserLogin.authCookie])
				.getResponseCode() == HTTP_OK

		where:
		kind                    | tokenOf
		'its session id'        | { String own, String other -> EntryStoreClient.sessionIdOf(own) }
		'its cookie value'      | { String own, String other -> EntryStoreClient.authTokenValue(own) }
		'an unknown handle'     | { String own, String other -> 'AAAAAAAAAAAAAAAAAAAAAA' }
		"another user's handle" | { String own, String other -> EntryStoreClient.currentSessionHandle(other) }
	}

	/**
	 * Logs the user in with the given auth_maxage, which loginIsolated does not support, and returns its auth_token
	 * Set-Cookie line.
	 */
	private static String loginWithMaxAge(String username, String maxAge) {
		def body = createFormBody([auth_username: username, auth_password: password, auth_maxage: maxAge])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', body, '',
				'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		return EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
	}
}
