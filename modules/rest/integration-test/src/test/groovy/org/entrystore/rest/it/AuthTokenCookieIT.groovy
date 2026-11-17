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
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * A browser upgraded from 5.x still sends its 5.x auth_token (issued on the base-URL path /store/, HttpOnly,
 * often cross-site), which 6.0 does not know. The client cannot remove that cookie, so EntryStore must expire
 * it, on both the 5.x path /store/ and the 6.0 path /store, with the configured SameSite and Secure attributes
 * (SameSite=None and thus Secure in the IT config).
 */
class AuthTokenCookieIT extends BaseSpec {

	// 5.x issued 128 random alphanumeric characters as auth_token
	static final String STALE_TOKEN = RandomStringUtils.secure().nextAlphanumeric(128)

	def "GET /auth/user with an unknown auth_token should respond with 401 and expire the cookie on /store and /store/"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		assertExpiredOnStoreAndLegacyPath(conn)
	}

	def "GET /_principals/entry/_guest with an unknown auth_token should respond with 401 and expire the cookie on /store and /store/"() {
		when:
		def conn = EntryStoreClient.getRequest('/_principals/entry/_guest', '', 'application/json',
				[Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		assertExpiredOnStoreAndLegacyPath(conn)
	}

	def "#method #path with an unknown auth_token should reach the handler as guest and expire the cookie on /store and /store/"() {
		given: 'an unknown confirmation token, answered 400, and SSO disabled, answered 404, both by the handler'
		def body = method == 'POST'
				? JsonOutput.toJson([confirm: 'unknownConfirmToken', email: 'stale@test.com', password: 'newPass12345'])
				: null

		when:
		def conn = EntryStoreClient.sendRequestAsStream(HttpMethod.valueOf(method), path,
				body == null ? null : new ByteArrayInputStream(body.bytes), '', body == null ? null : 'application/json',
				[Cookie: 'auth_token=' + STALE_TOKEN, Accept: 'text/html'])

		then:
		conn.getResponseCode() == status
		assertExpiredOnStoreAndLegacyPath(conn)

		where:
		method | path                    || status
		'GET'  | '/auth/login'           || HTTP_OK
		'GET'  | '/auth/saml'            || HTTP_NOT_FOUND
		'GET'  | '/auth/oidc'            || HTTP_NOT_FOUND
		'GET'  | '/auth/cas'             || HTTP_NOT_FOUND
		'GET'  | '/auth/signup'          || HTTP_OK
		'GET'  | '/auth/pwreset'         || HTTP_OK
		'POST' | '/auth/signup/confirm'  || HTTP_BAD_REQUEST
		'POST' | '/auth/pwreset/confirm' || HTTP_BAD_REQUEST
	}

	def "GET /auth/user?ignoreAuth with an unknown auth_token should respond as guest and keep the cookie"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json',
				[Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'guest'
		EntryStoreClient.findSetCookies(conn, 'auth_token').isEmpty()
	}

	def "GET /auth/user?callback= with an unknown auth_token should respond as guest and keep the cookie"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', '*/*',
				[Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_OK
		userOf(conn.inputStream.text) == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null
	}

	def "GET /auth/user?ignoreAuth with an ended session should respond as guest and keep the cookie"() {
		given: 'a session of the user that another of its sessions ended'
		def ended = endedSessionCookie()

		when:
		def conn = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', 'application/json', [Cookie: ended])

		then:
		conn.getResponseCode() == HTTP_OK
		userOf(conn.inputStream.text) == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null

		and: 'the next request that is not served as guest expires the cookie'
		def next = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: ended])
		next.getResponseCode() == HTTP_UNAUTHORIZED
		assertExpiredOnStoreAndLegacyPath(next)
	}

	def "GET /auth/user?callback= with an ended session should respond as guest and keep the cookie"() {
		given: 'a session of the user that another of its sessions ended'
		def ended = endedSessionCookie()

		when:
		def conn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', '*/*', [Cookie: ended])

		then:
		conn.getResponseCode() == HTTP_OK
		userOf(conn.inputStream.text) == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null

		and: 'the next request that is not served as guest expires the cookie'
		def next = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: ended])
		next.getResponseCode() == HTTP_UNAUTHORIZED
		assertExpiredOnStoreAndLegacyPath(next)
	}

	/** The user name in a /auth/user body, plain or wrapped as JSONP with the callback cb. */
	private static String userOf(String body) {
		def json = body.startsWith('cb(') ? body.substring('cb('.length(), body.length() - 1) : body
		return JSON_PARSER.parseText(json)['user']
	}

	/** @return the Set-Cookie line of a session of 'user' that another of the user's sessions ended */
	private static String endedSessionCookie() {
		def ending = EntryStoreClient.loginIsolated('user')
		def ended = EntryStoreClient.loginIsolated('user')
		def endedHandle = EntryStoreClient.handleOf(ended.authCookie)
		assert EntryStoreClient.deleteRequest('/auth/tokens', JsonOutput.toJson([token: endedHandle]), '',
				'application/json', EntryStoreClient.csrfHeaders(ending.authCookie, ending.csrf))
				.getResponseCode() == HTTP_NO_CONTENT
		return ended.authCookie
	}

	def "POST /auth/logout with an unknown auth_token should expire the cookie on /store and /store/"() {
		given: 'a matching XSRF pair, as CSRF protection applies whenever an auth_token cookie is present'
		def headers = EntryStoreClient.csrfHeaders('auth_token=' + STALE_TOKEN, 'someCsrfToken')

		when:
		def conn = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json', headers)

		then:
		conn.getResponseCode() == HTTP_NO_CONTENT
		assertExpiredOnStoreAndLegacyPath(conn)
	}

	def "POST /auth/cookie should issue auth_token on the base-URL path /store/ as 5.x did"() {
		when:
		def conn = login([:])

		then:
		conn.getResponseCode() == HTTP_OK
		def issued = EntryStoreClient.findSetCookies(conn, 'auth_token')
				.collect { EntryStoreClient.parseSetCookieAttributes(it) }
				.findAll { it['max-age'] != '0' }
		issued*.path == ['/store/']
	}

	def "POST /auth/cookie with a stale auth_token should only set the new cookie, as 5.x did"() {
		when:
		def conn = login([Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_OK
		EntryStoreClient.expiredCookies(conn, 'auth_token').isEmpty()

		and: 'the new session works'
		userWithCookie(EntryStoreClient.findCookieValue(conn, 'auth_token')) == 'user'
	}

	def "POST /auth/cookie with a stale and a valid auth_token should not expire /store or /store/"() {
		given: 'a browser holding a stale copy on /store and its login on /store/'
		def validToken = EntryStoreClient.findCookieValue(login([:]), 'auth_token')

		when:
		def conn = login([Cookie: 'auth_token=' + validToken + '; auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_OK
		EntryStoreClient.expiredCookies(conn, 'auth_token').isEmpty()

		and: 'the new session works, also sent along with the stale copy'
		def newToken = EntryStoreClient.findCookieValue(conn, 'auth_token')
		userWithCookie(newToken) == 'user'
		userWithCookie(newToken + '; auth_token=' + STALE_TOKEN) == 'user'
	}

	def "logging in again with the previous cookie should keep a libcurl cookie jar logged in"() {
		given: 'a jar that, like libcurl, keys cookies by path without the trailing slash, as FME and curl -b -c do'
		def jar = new LibcurlCookieJar()

		when: 'it logs in three times, each time sending the previous cookie, and makes a request after each login'
		def users = (1..3).collect { loginThenRequestUser(jar) }

		then: 'every request runs as the user, not every second one as guest'
		users == ['user', 'user', 'user']
	}

	// Guard: passes before the fix, as Jetty selects the valid one of several session cookies.
	def "a request with a stale and a valid auth_token should be authenticated by the valid one"() {
		given:
		def validToken = EntryStoreClient.findCookieValue(login([:]), 'auth_token')

		when: 'the stale cookie comes first, as browsers send the cookie with the longer path first'
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json',
				[Cookie: 'auth_token=' + STALE_TOKEN + '; auth_token=' + validToken])

		then:
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'user'
	}

	private static HttpURLConnection login(Map<String, String> headers) {
		def body = 'auth_username=user&auth_password=' + EntryStoreClient.creds['user']
		return EntryStoreClient.postRequest('/auth/cookie', body, '', 'application/x-www-form-urlencoded', headers)
	}

	private static String loginThenRequestUser(LibcurlCookieJar jar) {
		def conn = login(jar.requestHeaders())
		assert conn.getResponseCode() == HTTP_OK
		jar.store(conn)
		def user = EntryStoreClient.getRequest('/auth/user', '', 'application/json', jar.requestHeaders())
		assert user.getResponseCode() == HTTP_OK
		jar.store(user)
		return userOf(user.inputStream.text)
	}

	private static String userWithCookie(String cookie) {
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: 'auth_token=' + cookie])
		assert conn.getResponseCode() == HTTP_OK
		return userOf(conn.inputStream.text)
	}

	/**
	 * Applies Set-Cookie lines in response order and keys each cookie by name and path without the trailing slash,
	 * as libcurl's cookie engine does, so an expiry on /store removes a cookie set on /store/. All cookies are
	 * host-only on one host.
	 */
	private static class LibcurlCookieJar {

		private final Map<String, String> cookies = [:]

		void store(HttpURLConnection conn) {
			for (int i = 1; conn.getHeaderFieldKey(i) != null; i++) {
				if (conn.getHeaderFieldKey(i).equalsIgnoreCase('Set-Cookie')) {
					def line = conn.getHeaderField(i)
					def nameValue = line.split(';')[0].split('=', 2)
					def attributes = EntryStoreClient.parseSetCookieAttributes(line)
					def path = attributes['path'] ?: '/'
					def key = nameValue[0] + ' ' + (path.length() > 1 ? path.replaceFirst('/$', '') : path)
					if (attributes['max-age'] == '0') {
						cookies.remove(key)
					} else {
						cookies[key] = nameValue[0] + '=' + nameValue[1]
					}
				}
			}
		}

		Map<String, String> requestHeaders() {
			return cookies.isEmpty() ? [:] : [Cookie: cookies.values().join('; ')]
		}
	}

	private static void assertExpiredOnStoreAndLegacyPath(HttpURLConnection conn) {
		def expired = EntryStoreClient.expiredCookies(conn, 'auth_token')
		assert expired.keySet() == ['/store', '/store/'] as Set
		expired.values().each { attributes ->
			assert attributes['samesite'] == 'None'
			assert attributes.containsKey('secure')
		}
	}
}
