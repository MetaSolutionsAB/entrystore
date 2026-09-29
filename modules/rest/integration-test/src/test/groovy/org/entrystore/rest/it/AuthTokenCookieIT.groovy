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

import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient

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

	def "POST /auth/cookie with a stale auth_token should expire it on /store but not on the issuing path /store/"() {
		when:
		def conn = login([Cookie: 'auth_token=' + STALE_TOKEN])

		then:
		conn.getResponseCode() == HTTP_OK
		EntryStoreClient.expiredCookies(conn, 'auth_token').keySet() == ['/store'] as Set

		and: 'the new session works'
		def newToken = EntryStoreClient.findSetCookies(conn, 'auth_token').find { !it.contains('Max-Age=0') }
		def user = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: newToken.split(';')[0]])
		user.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(user.inputStream.text)['user'] == 'user'
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

	private static void assertExpiredOnStoreAndLegacyPath(HttpURLConnection conn) {
		def expired = EntryStoreClient.expiredCookies(conn, 'auth_token')
		assert expired.keySet() == ['/store', '/store/'] as Set
		expired.values().each { attributes ->
			assert attributes['samesite'] == 'None'
			assert attributes.containsKey('secure')
		}
	}
}
