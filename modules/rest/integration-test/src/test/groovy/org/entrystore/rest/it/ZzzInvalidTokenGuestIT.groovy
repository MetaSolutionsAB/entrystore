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
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_PARTIAL
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED
import static org.entrystore.rest.springboot.filter.CacheControlFilter.CACHE_CONTROL_CREDENTIALS

/**
 * With {@code entrystore.auth.cookie.invalid-token-error=false} an unknown or expired auth_token is expired and
 * the request is served as guest instead of answering 401, as in 5.x. {@link AuthTokenCookieIT} covers the
 * default (401) against the shared app.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzInvalidTokenGuestIT extends BaseSpec {

	private static final String CONTEXT_ID = 'invalid-token-guest-it-ctx'

	def setupSpec() {
		stopPreexistingAppIfRunning()

		startOwnedApp(['--entrystore.auth.cookie.invalid-token-error=false', '--entrystore.csrf.enabled=true'])
		getOrCreateContext([contextId: CONTEXT_ID])
	}

	// Intentionally no cleanupSpec, see ZzzCsrfDisabledIT.

	def "GET /auth/user with an unknown auth_token should respond as guest and expire the cookie on /store and /store/"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: 'auth_token=unknownToken'])

		then:
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'guest'
		EntryStoreClient.expiredCookies(conn, 'auth_token').keySet() == ['/store', '/store/'] as Set
	}

	def "GET entry with an unknown auth_token should expire the cookie in a response no cache may store"() {
		when:
		def conn = EntryStoreClient.getRequest('/_principals/entry/_admin', '', 'application/json',
				[Cookie: 'auth_token=unknownToken'])

		then:
		conn.getResponseCode() == HTTP_OK
		EntryStoreClient.expiredCookies(conn, 'auth_token').keySet() == ['/store', '/store/'] as Set
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_CREDENTIALS
	}

	def "GET /auth/user with an expired session should respond as guest and expire the cookie on /store and /store/"() {
		given: 'two admin sessions, the first one expiring the second one (users other than admin exist only in the shared app)'
		def firstLogin = login()
		def firstToken = EntryStoreClient.findCookieValue(firstLogin, 'auth_token')
		def firstCsrf = EntryStoreClient.findCookieValue(firstLogin, 'XSRF-TOKEN')
		def secondToken = EntryStoreClient.findCookieValue(login(), 'auth_token')
		def sessionId = secondToken.substring(0, secondToken.indexOf('.node'))
		assert EntryStoreClient.deleteRequest('/auth/tokens', JsonOutput.toJson([token: sessionId]), '',
				'application/json', EntryStoreClient.csrfHeaders('auth_token=' + firstToken, firstCsrf))
				.getResponseCode() == HTTP_NO_CONTENT

		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: 'auth_token=' + secondToken])

		then:
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'guest'
		EntryStoreClient.expiredCookies(conn, 'auth_token').keySet() == ['/store', '/store/'] as Set
	}

	def "conditional GET entry with an unknown auth_token and a matching #header should answer 200 in full, not 304"() {
		given: 'validators that a guest without the cookie gets a 304 for'
		def path = '/_principals/entry/_admin'
		def validator = EntryStoreClient.getRequest(path, '', 'application/json').getHeaderField(validatorHeader)
		assert EntryStoreClient.getRequest(path, '', 'application/json', [(header): validator])
				.getResponseCode() == HTTP_NOT_MODIFIED

		when:
		def conn = EntryStoreClient.getRequest(path, '', 'application/json',
				[Cookie: 'auth_token=unknownToken', (header): validator])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getInputStream().text.contains('_admin')
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_CREDENTIALS

		where:
		header              | validatorHeader
		'If-None-Match'     | 'ETag'
		'If-Modified-Since' | 'Last-Modified'
	}

	def "conditional GET entry with an expired session and a matching If-None-Match should answer 200 in full, not 304"() {
		given:
		def path = '/_principals/entry/_admin'
		def etag = EntryStoreClient.getRequest(path, '', 'application/json').getHeaderField('ETag')
		def staleToken = expiredSessionToken()

		when:
		def conn = EntryStoreClient.getRequest(path, '', 'application/json',
				[Cookie: 'auth_token=' + staleToken, 'If-None-Match': etag])

		then:
		etag != null
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_CREDENTIALS
	}

	def "PUT metadata of a guest-writable entry with an unknown auth_token and an outdated If-Unmodified-Since should answer 412"() {
		given: 'a guest may write the metadata, and the stale cookie needs a CSRF token like any cookie-carrying write'
		def entryId = createGuestEntry()
		def metadataPath = '/' + CONTEXT_ID + '/metadata/' + entryId
		def guestWithStaleCookie = [Cookie: 'auth_token=unknownToken; XSRF-TOKEN=csrf', 'X-XSRF-TOKEN': 'csrf']
		assert EntryStoreClient.putRequest(metadataPath, metadataJson(entryId, 'Guest edit'), '', 'application/json',
				guestWithStaleCookie).getResponseCode() == HTTP_NO_CONTENT

		when:
		def conn = EntryStoreClient.putRequest(metadataPath, metadataJson(entryId, 'Conflicting edit'), '',
				'application/json', guestWithStaleCookie + ['If-Unmodified-Since': 'Thu, 01 Jan 1970 00:00:01 GMT'])

		then:
		conn.getResponseCode() == HTTP_PRECON_FAILED
	}

	def "GET of a guest-readable file with #session, Range and a matching If-Range should answer #status"() {
		given:
		def path = '/' + CONTEXT_ID + '/resource/' + createGuestEntry()
		assert EntryStoreClient.putRequestFile(path, createTempBinaryFile('file', '.bin', 'file content'.bytes))
				.getResponseCode() == HTTP_CREATED
		def etag = EntryStoreClient.getRequest(path, '', 'application/json').getHeaderField('ETag')
		def headers = [Range: 'bytes=5-', 'If-Range': etag]
		if (session == 'an expired session') {
			headers.Cookie = 'auth_token=' + expiredSessionToken()
		}

		when:
		def conn = EntryStoreClient.getRequest(path, '', 'application/json', headers)

		then:
		etag != null
		conn.getResponseCode() == status
		conn.getInputStream().text == body

		where:
		session              | status       | body
		'no cookie'          | HTTP_PARTIAL | 'content'
		'an expired session' | HTTP_OK      | 'file content'
	}

	/** Creates an entry whose metadata a guest may write and whose resource a guest may read. */
	private static String createGuestEntry() {
		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		def base = EntryStoreClient.baseUrl + '/' + CONTEXT_ID
		def body = createTitleMetadataBody(base + '/resource/_newId', 'Guest entry')
		body.info = [
			(base + '/metadata/_newId'): [(NameSpaceConst.TERM_WRITE): [[type: 'uri', value: guestUri]]],
			(base + '/resource/_newId'): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
		return createEntry(CONTEXT_ID, [:], body)
	}

	private static String metadataJson(String entryId, String title) {
		def resourceUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/' + entryId
		return JsonOutput.toJson([(resourceUri): [(NameSpaceConst.DC_TERM_TITLE): [[type: 'literal', value: title]]]])
	}

	/** @return the cookie of an admin session that another session of the same user ended */
	private static String expiredSessionToken() {
		def firstLogin = login()
		def firstToken = EntryStoreClient.findCookieValue(firstLogin, 'auth_token')
		def firstCsrf = EntryStoreClient.findCookieValue(firstLogin, 'XSRF-TOKEN')
		def secondToken = EntryStoreClient.findCookieValue(login(), 'auth_token')
		def sessionId = secondToken.substring(0, secondToken.indexOf('.node'))
		assert EntryStoreClient.deleteRequest('/auth/tokens', JsonOutput.toJson([token: sessionId]), '',
				'application/json', EntryStoreClient.csrfHeaders('auth_token=' + firstToken, firstCsrf))
				.getResponseCode() == HTTP_NO_CONTENT
		return secondToken
	}

	private static HttpURLConnection login() {
		def body = 'auth_username=admin&auth_password=' + EntryStoreClient.creds['admin']
		def conn = EntryStoreClient.postRequest('/auth/cookie', body, '', 'application/x-www-form-urlencoded')
		assert conn.getResponseCode() == HTTP_OK
		return conn
	}
}
