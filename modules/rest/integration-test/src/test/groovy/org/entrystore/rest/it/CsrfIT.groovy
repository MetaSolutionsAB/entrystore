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

import static java.net.HttpURLConnection.HTTP_ACCEPTED
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Verifies that SecurityConfig's CSRF protection only enforces tokens on cookie-authenticated
 * unsafe-method requests, with the documented exemptions (login, signup, password reset, SAML ACS).
 * Mutating {@code /management/**} endpoints are deliberately NOT exempt — they are in scope for any
 * cookie-authenticated user. Basic-auth and other non-session flows must be unaffected.
 */
class CsrfIT extends BaseSpec {

	private static final String GROUPS_PATH = '/_principals/groups'

	def "POST as cookie-authenticated user with valid CSRF token should succeed"() {
		given:
		// Use the IT client's auto-injection path which forwards both Cookie and X-XSRF-TOKEN.
		when:
		def conn = EntryStoreClient.postRequest(GROUPS_PATH, '{}', 'admin')

		then:
		conn.getResponseCode() == HTTP_CREATED
	}

	def "POST as cookie-authenticated user without CSRF token should be rejected"() {
		given:
		def cookie = EntryStoreClient.cookieHeader('admin')
		def groupName = 'csrfRejectedNoTokenGroup'

		when: 'CSRF-less mutation attempt'
		def conn = EntryStoreClient.postRequest(GROUPS_PATH + '?name=' + groupName, '{}', '', 'application/json', [Cookie: cookie])

		then:
		// Spring's CsrfConfigurer routes CSRF-rejected requests through the AuthenticationEntryPoint
		// when the trust resolver sees the request as not yet authenticated at filter time, yielding 401.
		// The security invariant — the cookie-authenticated mutation is blocked — holds either way.
		conn.getResponseCode() == HTTP_UNAUTHORIZED

		when: 'replay the same create with valid CSRF — must succeed (proves the rejected request did not silently mutate state)'
		def replay = EntryStoreClient.postRequest(GROUPS_PATH + '?name=' + groupName, '{}', 'admin')

		then:
		// 409 Conflict here would indicate the rejected POST actually created the group (server-side mutation
		// past the security filter), so the test failed open. We need a fresh 201 Created.
		replay.getResponseCode() == HTTP_CREATED
	}

	def "POST as cookie-authenticated user with mismatched CSRF token should be rejected"() {
		given:
		def cookie = EntryStoreClient.cookieHeader('admin')
		def groupName = 'csrfRejectedBadTokenGroup'

		when: 'mutation attempt with a forged CSRF token'
		def conn = EntryStoreClient.postRequest(GROUPS_PATH + '?name=' + groupName, '{}', '', 'application/json',
				[Cookie: cookie, 'X-XSRF-TOKEN': 'not-a-real-token'])

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED

		when: 'replay the same create with valid CSRF'
		def replay = EntryStoreClient.postRequest(GROUPS_PATH + '?name=' + groupName, '{}', 'admin')

		then:
		// Same invariant as the no-token case: a fresh 201 proves the forged-token POST did not mutate state.
		replay.getResponseCode() == HTTP_CREATED
	}

	def "POST /auth/cookie (login) without CSRF token should succeed - login is exempt"() {
		given:
		def bodyParams = 'auth_username=admin&auth_password=adminpass'

		when:
		def conn = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/signup without CSRF token should not be CSRF-rejected - signup is exempt"() {
		given:
		def body = JsonOutput.toJson([
				email             : 'csrfExemptSignup@test.com',
				password          : 'somePassword123',
				grecaptcharesponse: 'anything'
		])

		when:
		// Signup is unauthenticated and exempt from CSRF. Business validation rejects this body
		// (the recaptcha is fake) — what matters here is that CSRF doesn't block it (no 401/403) and
		// the controller doesn't crash (no 5xx). A weak "not forbidden" would pass on a server crash.
		def conn = EntryStoreClient.postRequest('/auth/signup', body, '')

		then:
		// HTTP_BAD_REQUEST is the expected business response for the fake recaptcha; HTTP_OK would
		// indicate signup proceeded (acceptable if recaptcha is bypassed in the test profile). 5xx
		// or 401/403 would indicate a real regression.
		conn.getResponseCode() < HTTP_INTERNAL_ERROR
		[HTTP_OK, HTTP_BAD_REQUEST].contains(conn.getResponseCode())
	}

	def "PUT /management/logging as cookie-authenticated user without CSRF token should be rejected"() {
		given:
		// Plain session cookie — no XSRF-TOKEN cookie, no X-XSRF-TOKEN header.
		def cookie = EntryStoreClient.cookies['admin'].toString()
		def body = JsonOutput.toJson([:])

		when:
		def conn = EntryStoreClient.putRequest('/management/logging', body, '', 'application/json', [Cookie: cookie])

		then:
		// /management/** mutations are NOT exempt: a cookie-auth request without CSRF token must
		// be blocked before reaching the controller (same envelope as other CSRF-rejected requests).
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "PUT /management/logging as cookie-authenticated user with valid CSRF token should succeed"() {
		given:
		// Use the IT client's auto-injection path which forwards both Cookie and X-XSRF-TOKEN.
		def body = JsonOutput.toJson([:])

		when:
		def conn = EntryStoreClient.putRequest('/management/logging', body, 'admin')

		then:
		// 202 Accepted from the controller (empty config no-ops) or 400 if validation rejects the
		// empty body — but never 401/403 from the CSRF filter and never 5xx from a server crash.
		// A weak "not 401 and not 403" would pass on a server crash and falsely report success.
		conn.getResponseCode() < HTTP_INTERNAL_ERROR
		[HTTP_ACCEPTED, HTTP_BAD_REQUEST].contains(conn.getResponseCode())
	}

	def "POST with Basic auth (no session cookie) should succeed without CSRF token"() {
		given:
		def basicAuth = 'Basic ' + Base64.getEncoder().encodeToString('admin:adminpass'.getBytes())

		when:
		def conn = EntryStoreClient.postRequest(GROUPS_PATH, '{}', '', 'application/json',
				['Authorization': basicAuth])

		then:
		conn.getResponseCode() == HTTP_CREATED
	}

	def "POST /auth/logout as cookie-authenticated user without CSRF token should be rejected"() {
		given:
		// Use an isolated session so we don't invalidate the shared admin cookie.
		def loginConn = EntryStoreClient.postRequest('/auth/cookie',
				'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')
		assert loginConn.getResponseCode() == HTTP_OK
		def isolatedAuth = EntryStoreClient.findSetCookie(loginConn, 'auth_token')
		def isolatedCsrf = EntryStoreClient.findCookieValue(loginConn, 'XSRF-TOKEN')
		def cookieHeader = isolatedAuth + '; XSRF-TOKEN=' + isolatedCsrf

		when:
		def conn = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json',
				[Cookie: cookieHeader])

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/logout as cookie-authenticated user with valid CSRF token should succeed"() {
		given:
		def loginConn = EntryStoreClient.postRequest('/auth/cookie',
				'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')
		assert loginConn.getResponseCode() == HTTP_OK
		def isolatedAuth = EntryStoreClient.findSetCookie(loginConn, 'auth_token')
		def isolatedCsrf = EntryStoreClient.findCookieValue(loginConn, 'XSRF-TOKEN')
		assert isolatedCsrf != null: 'login response must carry XSRF-TOKEN cookie'

		when:
		def conn = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json',
				EntryStoreClient.csrfHeaders(isolatedAuth, isolatedCsrf))

		then:
		conn.getResponseCode() == HTTP_NO_CONTENT
	}

	def "GET requests as cookie-authenticated user never require CSRF"() {
		given:
		def cookie = EntryStoreClient.cookies['admin'].toString()

		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookie])

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "Login response should carry XSRF-TOKEN cookie so SPAs can read it"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/cookie',
				'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		EntryStoreClient.findSetCookie(conn, 'XSRF-TOKEN') != null
		EntryStoreClient.findCookieValue(conn, 'XSRF-TOKEN')?.length() > 0
	}

	def "XSRF-TOKEN cookie carries SameSite + Secure + Path attributes and no HttpOnly"() {
		given:
		// IT config (entrystore-it.properties) sets entrystore.auth.cookie.samesite=none and
		// entrystore.auth.cookie.secure=off. SecurityConfig.csrfTokenRepository() must still emit
		// `Secure` because SameSite=None requires it; if a regression dropped that auto-enable, the
		// browser would silently reject the cookie and every cookie-auth mutation would fail.
		when:
		def conn = EntryStoreClient.postRequest('/auth/cookie',
				'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK

		and:
		def setCookie = EntryStoreClient.findSetCookie(conn, 'XSRF-TOKEN')
		setCookie != null
		def attrs = setCookie.split(';').collect { it.trim().toLowerCase() }
		attrs.contains('samesite=none')
		attrs.contains('secure')
		attrs.contains('path=/')
		// SPAs read the token from JS to forward as X-XSRF-TOKEN, so HttpOnly must NOT be set.
		!attrs.contains('httponly')
	}
}
