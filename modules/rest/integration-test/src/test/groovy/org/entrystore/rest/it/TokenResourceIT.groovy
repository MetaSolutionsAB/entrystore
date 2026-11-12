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

	// Each has only the sessions of the one test that names it, so that test can count them
	private static final String USER_LISTING = 'userForTokenManagement@test.com'
	private static final String USER_DELETING = 'userForTokenManagementDelete@test.com'
	private static final String USER_DELETING_CURRENT = 'userForTokenManagementDeleteCurrent@test.com'
	// Shared by the tests that do not count sessions
	private static final String USER_SHARED = 'userForTokenManagementUpdate@test.com'

	// entrystore.auth.session.reload-interval of the ITs, in milliseconds
	private static final long RELOAD_INTERVAL_MILLIS = 2000

	def setupSpec() {
		EntryStoreClient.snapshotCreds()
		[USER_LISTING, USER_DELETING, USER_DELETING_CURRENT, USER_SHARED].each {
			EntryStoreClient.creds.put(it, password)
			UserUtil.createUserWithPassword(it, password)
		}
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

	def "GET /auth/tokens should get a list of all currently active logins for of an authenticated user"() {
		given:
		// raw login ritual kept: loginIsolated does not support auth_maxage
		def bodyParams1 = createFormBody([auth_username: USER_LISTING, auth_password: password, auth_maxage: '100'])
		def loginConnection1 = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection1.getResponseCode() == HTTP_OK
		def cookie1 = EntryStoreClient.findSetCookie(loginConnection1, 'auth_token')
		def bodyParams2 = createFormBody([auth_username: USER_LISTING, auth_password: password, auth_maxage: '50'])
		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams2, '', 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = EntryStoreClient.findSetCookie(loginConnection2, 'auth_token')

		when:
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie1])

		then:
		tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text) as Map
		tokensRespJson.keySet().size() == 2

		and: 'the session making the request is the current one'
		def current = tokensRespJson.values().findAll { it['current'] == true }
		current.size() == 1
		current[0]['userName'] == 'userfortokenmanagement@test.com'
		current[0]['lastAccessTime'] != null
		current[0]['loginExpiration'] != null
		current[0]['loginTime'] != null
		current[0]['lastUsedIpAddress'] != null
		current[0]['loginTokenMaxAge'] == 100
		current[0]['lastUsedUserAgent'] != null

		and: 'the other one is not'
		def other = tokensRespJson.values().findAll { it['current'] == false }
		other.size() == 1
		other[0]['userName'] == 'userfortokenmanagement@test.com'
		other[0]['loginTokenMaxAge'] == 50

		and: 'the keys are handles: 22 base64url characters, and neither session ids nor cookie values'
		tokensRespJson.keySet().every { it ==~ /[A-Za-z0-9_-]{22}/ }
		[cookie1, cookie2].every {
			!tokensRespJson.containsKey(EntryStoreClient.sessionIdOf(it)) &&
					!tokensRespJson.containsKey(EntryStoreClient.cookieValueOf(it))
		}

		and: 'the other session lists the same handles, with itself as the current one'
		def tokensOfOther = JSON_PARSER.parseText(
				EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie2]).inputStream.text) as Map
		tokensOfOther.keySet() == tokensRespJson.keySet()
		tokensOfOther.find { it.value['current'] == true }.key ==
				tokensRespJson.find { it.value['current'] == false }.key
	}

	def "GET /auth/tokens should get a list of all currently active logins of an authenticated user with updated timestamps"() {
		given:
		def login = EntryStoreClient.loginIsolated(USER_SHARED)
		def token = EntryStoreClient.handleOf(login.authCookie)
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])
		assert tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text)
		def oldLastAccessTime = LocalDateTime.parse(tokensRespJson[token]['lastAccessTime'].toString(), dtf)
		def oldLoginExpiration = LocalDateTime.parse(tokensRespJson[token]['loginExpiration'].toString(), dtf)
		def oldLoginTime = tokensRespJson[token]['loginTime']

		when:
		// the assertions below truncate to whole milliseconds (ChronoUnit.MILLIS.between(...) > 0),
		// so the second request has to land in a strictly later millisecond to be observable
		Thread.sleep(5)
		def tokensNewConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])

		then:
		tokensNewConnection.getResponseCode() == HTTP_OK
		def tokensNewRespJson = JSON_PARSER.parseText(tokensNewConnection.inputStream.text)
		def newLastAccessTime = LocalDateTime.parse(tokensNewRespJson[token]['lastAccessTime'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLastAccessTime, newLastAccessTime) > 0
		def newLoginExpiration = LocalDateTime.parse(tokensNewRespJson[token]['loginExpiration'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLoginExpiration, newLoginExpiration) > 0
		oldLoginTime == tokensNewRespJson[token]['loginTime']
	}

	def "GET /auth/tokens should list a session under the same handle after its user was reloaded"() {
		given:
		def login = EntryStoreClient.loginIsolated(USER_SHARED)
		def handleAtLogin = EntryStoreClient.handleOf(login.authCookie)

		when: 'the next request of the session reloads its user'
		Thread.sleep(RELOAD_INTERVAL_MILLIS + 200)
		def handleAfterReload = EntryStoreClient.handleOf(login.authCookie)

		then:
		handleAfterReload == handleAtLogin
	}

	def "GET /auth/tokens should report a session unchanged after requests served as guest carried its cookie"() {
		given: 'two sessions of the user; the second one reads what /auth/tokens reports about the first'
		def login = EntryStoreClient.loginIsolated(USER_SHARED)
		def observer = EntryStoreClient.loginIsolated(USER_SHARED)
		def handle = EntryStoreClient.handleOf(login.authCookie)
		def infoSeenByObserver = {
			JSON_PARSER.parseText(EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: observer.authCookie])
					.inputStream.text)[handle]
		}
		def before = infoSeenByObserver()

		when: 'a JSONP and an ignoreAuth request carry the cookie of the first session'
		def guestHeaders = [Cookie: login.authCookie, 'User-Agent': 'served-as-guest']
		def jsonpConn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', '*/*', guestHeaders)
		def ignoreAuthConn = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json', guestHeaders)

		then:
		jsonpConn.getResponseCode() == HTTP_OK
		ignoreAuthConn.getResponseCode() == HTTP_OK

		and: 'the session info is as before'
		def after = infoSeenByObserver()
		after['lastAccessTime'] == before['lastAccessTime']
		after['lastUsedUserAgent'] == before['lastUsedUserAgent']
		after['loginExpiration'] == before['loginExpiration']
	}

	def "GET /auth/tokens should give every login its own handle"() {
		when:
		def handle1 = EntryStoreClient.handleOf(EntryStoreClient.loginIsolated(USER_SHARED).authCookie)
		def handle2 = EntryStoreClient.handleOf(EntryStoreClient.loginIsolated(USER_SHARED).authCookie)

		then:
		handle1 != handle2
	}

	def "GET #path should not contain a session id or cookie value of the user"() {
		given:
		def login = EntryStoreClient.loginIsolated(USER_SHARED)
		def otherLogin = EntryStoreClient.loginIsolated(USER_SHARED)
		// a cookie value starts with its session id, so the session ids cover both
		def sessionIds = [login, otherLogin].collect { EntryStoreClient.sessionIdOf(it.authCookie) }

		when:
		def conn = EntryStoreClient.getRequest(path, '', 'application/json', [Cookie: login.authCookie])

		then:
		def body = EntryStoreClient.bodyOf(conn)
		def headers = conn.getHeaderFields().collect { it.key + ': ' + it.value.join(', ') }.join('\n')
		sessionIds.every { !body.contains(it) && !headers.contains(it) }

		where:
		path << [
			'/auth/tokens',
			'/auth/tokens?callback=cb',
			'/auth/user',
			'/auth/user?callback=cb',
			'/search?type=solr&query=title:*',
			'/search?type=solr&query=title:*&callback=cb'
		]
	}

	def "DELETE /auth/tokens should get unauthorized for a non-authenticated user"() {
		when:
		def tokensConnection = EntryStoreClient.deleteRequest('/auth/tokens', '[]', '')

		then:
		tokensConnection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should delete a specified token of the authenticated user"() {
		given:
		def login1 = EntryStoreClient.loginIsolated(USER_DELETING)
		def handle1 = EntryStoreClient.handleOf(login1.authCookie)
		def login2 = EntryStoreClient.loginIsolated(USER_DELETING)
		def body = JsonOutput.toJson([token: EntryStoreClient.handleOf(login2.authCookie)])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login1.authCookie, login1.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		def token1Connection = EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login1.authCookie])
		token1Connection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(token1Connection.inputStream.text)
		(tokensRespJson as Map).keySet().size() == 1
		tokensRespJson[handle1] != null
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login2.authCookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should delete current login token of the authenticated user and log him out"() {
		given:
		def login = EntryStoreClient.loginIsolated(USER_DELETING_CURRENT)
		def body = JsonOutput.toJson([token: EntryStoreClient.handleOf(login.authCookie)])
		assert EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_OK

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login.authCookie, login.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens with #description should end no session"() {
		given: 'two sessions of the user and one of another user'
		def login = EntryStoreClient.loginIsolated(USER_SHARED)
		def otherLogin = EntryStoreClient.loginIsolated(USER_SHARED)
		def strangerLogin = EntryStoreClient.loginIsolated('user')
		def body = JsonOutput.toJson([token: token.call(otherLogin.authCookie, strangerLogin.authCookie)])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login.authCookie, login.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		[login, otherLogin, strangerLogin].every {
			EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: it.authCookie]).getResponseCode() == HTTP_OK
		}

		where:
		description                            | token
		"a session id of the user"             | { own, stranger -> EntryStoreClient.sessionIdOf(own) }
		"a cookie value of the user"           | { own, stranger -> EntryStoreClient.cookieValueOf(own) }
		"an unknown handle"                    | { own, stranger -> 'AAAAAAAAAAAAAAAAAAAAAA' }
		"the handle of another user's session" | { own, stranger -> EntryStoreClient.handleOf(stranger) }
	}
}
