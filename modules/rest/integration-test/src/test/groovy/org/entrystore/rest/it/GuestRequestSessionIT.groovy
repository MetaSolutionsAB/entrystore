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

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * A request served as guest ({@code ignoreAuth}, or a GET with {@code callback}) leaves the session its cookie names
 * as it was: it neither uses, renews nor ends it, and sets no cookie.
 */
class GuestRequestSessionIT extends BaseSpec {

	// entrystore.auth.session.reload-interval of the ITs, in milliseconds
	private static final long RELOAD_INTERVAL_MILLIS = 10_000

	def "GET /auth/user?#parameter with #state session should respond as guest and set no cookie"() {
		given:
		def cookie = sessionCookie(state)

		when:
		def conn = EntryStoreClient.getRequest('/auth/user?' + parameter, '', 'application/json', [Cookie: cookie])

		then:
		conn.getResponseCode() == HTTP_OK
		payloadOf(conn.inputStream.text, parameter)['user'] == 'guest'
		conn.getHeaderFields().get('Set-Cookie') == null

		and: 'the session is as it was'
		userOrStatusOf(cookie) == afterwards

		where:
		state             | parameter     || afterwards
		'a live'          | 'ignoreAuth'  || 'user'
		'a live'          | 'callback=cb' || 'user'
		'an ended'        | 'ignoreAuth'  || HTTP_UNAUTHORIZED
		'an ended'        | 'callback=cb' || HTTP_UNAUTHORIZED
		'an idle-expired' | 'ignoreAuth'  || HTTP_UNAUTHORIZED
		'an idle-expired' | 'callback=cb' || HTTP_UNAUTHORIZED
	}

	def "ignoreAuth and JSONP GETs should leave the lastAccessTime and loginExpiration of the session as they were"() {
		given: 'a session due to record its next access, listed through a second session of its user'
		def cookie = cookieOf(login(''))
		def handle = EntryStoreClient.handleOf(cookie)
		def reader = cookieOf(login(''))
		sleep(RELOAD_INTERVAL_MILLIS + 200)
		def before = listedSession(reader, handle)

		when:
		def ignoreAuth = EntryStoreClient.getRequest('/auth/user?ignoreAuth', '', null, [Cookie: cookie])
		def jsonp = EntryStoreClient.getRequest('/auth/user?callback=cb', '', null, [Cookie: cookie])

		then:
		ignoreAuth.getResponseCode() == HTTP_OK
		jsonp.getResponseCode() == HTTP_OK
		def after = listedSession(reader, handle)
		after['lastAccessTime'] == before['lastAccessTime']
		after['loginExpiration'] == before['loginExpiration']

		and: 'a request served as the user records its access'
		userOrStatusOf(cookie) == 'user'
		listedSession(reader, handle)['lastAccessTime'] != before['lastAccessTime']
	}

	def "ignoreAuth and JSONP GETs should not keep an idle session alive"() {
		given: 'a session with a 2-second idle timeout'
		def cookie = cookieOf(login('&auth_maxage=2'))

		when: 'only guest-served GETs carry the cookie, for longer than the idle timeout'
		['/auth/user?ignoreAuth', '/auth/user?callback=cb', '/auth/user?ignoreAuth', '/auth/user?callback=cb'].each {
			sleep(800)
			assert EntryStoreClient.getRequest(it, '', null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		}

		then: 'the session has expired'
		userOrStatusOf(cookie) == HTTP_UNAUTHORIZED
	}

	/** @return the Cookie header of a session of 'user' that is live, ended through another session, or idle-expired */
	private static String sessionCookie(String state) {
		def cookie = cookieOf(login(state == 'an idle-expired' ? '&auth_maxage=2' : ''))
		if (state == 'an ended') {
			endSession(cookie)
		} else if (state == 'an idle-expired') {
			sleep(3000)
		}
		return cookie
	}

	/** Ends a session through another session of its user, as a client that manages its logins does. */
	private static void endSession(String cookie) {
		def ending = EntryStoreClient.loginIsolated('user')
		def body = JsonOutput.toJson([token: EntryStoreClient.handleOf(cookie)])
		assert EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(ending.authCookie, ending.csrf)).getResponseCode() == HTTP_NO_CONTENT
	}

	private static HttpURLConnection login(String extraParams) {
		def body = 'auth_username=user&auth_password=' + EntryStoreClient.creds['user'] + extraParams
		def conn = EntryStoreClient.postRequest('/auth/cookie', body, '', 'application/x-www-form-urlencoded')
		assert conn.getResponseCode() == HTTP_OK
		return conn
	}

	private static String cookieOf(HttpURLConnection login) {
		return 'auth_token=' + EntryStoreClient.findCookieValue(login, 'auth_token')
	}

	/** @return the name of the user the session's cookie authenticates, or the status of a refused request */
	private static Object userOrStatusOf(String cookie) {
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookie])
		if (conn.getResponseCode() != HTTP_OK) {
			return conn.getResponseCode()
		}
		return JSON_PARSER.parseText(conn.inputStream.text)['user']
	}

	/** @return what /auth/tokens, requested with the reader's cookie, lists under the handle */
	private static Map listedSession(String reader, String handle) {
		def conn = EntryStoreClient.getRequest('/auth/tokens', '', 'application/json', [Cookie: reader])
		assert conn.getResponseCode() == HTTP_OK
		def listed = (JSON_PARSER.parseText(conn.inputStream.text) as Map)[handle] as Map
		assert listed != null: 'the session is not listed'
		return listed
	}

	/** @return the JSON of a response body, which a {@code callback=cb} request gets wrapped as {@code cb(...)} */
	private static Object payloadOf(String body, String parameter) {
		if (!parameter.startsWith('callback=')) {
			return JSON_PARSER.parseText(body)
		}
		assert body.startsWith('cb(') && body.endsWith(')'): 'not a cb(...) JSONP body: ' + body
		return JSON_PARSER.parseText(body.substring('cb('.length(), body.length() - 1))
	}
}
