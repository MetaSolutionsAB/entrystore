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
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

/**
 * With {@code entrystore.auth.cookie.invalid-token-error=false} an unknown or expired auth_token is expired and
 * the request is served as guest instead of answering 401, as in 5.x. {@link AuthTokenCookieIT} covers the
 * default (401) against the shared app.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzInvalidTokenGuestIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()

		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.cookie.invalid-token-error=false',
			'--entrystore.csrf.enabled=true'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
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

	private static HttpURLConnection login() {
		def body = 'auth_username=admin&auth_password=' + EntryStoreClient.creds['admin']
		def conn = EntryStoreClient.postRequest('/auth/cookie', body, '', 'application/x-www-form-urlencoded')
		assert conn.getResponseCode() == HTTP_OK
		return conn
	}
}
