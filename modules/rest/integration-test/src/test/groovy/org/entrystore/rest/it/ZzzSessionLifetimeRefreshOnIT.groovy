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
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication

import java.time.Duration
import java.time.LocalDateTime

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * With {@code entrystore.auth.cookie.refresh-expiration-on-access} on (the default), a login lasts max-age after the
 * last request, as in 5.x: active users stay logged in, idle ones are logged out. Runs with a 5-second max-age.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSessionLifetimeRefreshOnIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.cookie.max-age=5'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	// Intentionally no cleanupSpec — see ZzzCsrfDisabledIT.

	def "login should issue a 365-day cookie for a login that lasts max-age"() {
		when:
		def login = login()

		then:
		login.getResponseCode() == HTTP_OK
		EntryStoreClient.findSetCookie(login, 'auth_token').contains('Max-Age=31536000')
		secondsUntil(authTokenExpires(cookieOf(login))) <= 5
	}

	def "an active login should outlive max-age and its expiry should move forward"() {
		given:
		def cookie = cookieOf(login())
		def firstExpiry = authTokenExpires(cookie)

		when: 'the client keeps making requests for longer than max-age'
		def statuses = (1..4).collect {
			sleep(2000)
			EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie]).getResponseCode()
		}

		then:
		statuses.every { it == HTTP_OK }
		authTokenExpires(cookie).isAfter(firstExpiry.plusSeconds(6))
	}

	def "an idle login should end after max-age"() {
		given:
		def cookie = cookieOf(login())

		when:
		sleep(6000)
		def request = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		request.getResponseCode() == HTTP_UNAUTHORIZED
	}

	private static HttpURLConnection login() {
		return EntryStoreClient.postRequest('/auth/cookie', 'auth_username=admin&auth_password=adminpass', '',
			'application/x-www-form-urlencoded')
	}

	private static String cookieOf(HttpURLConnection login) {
		return 'auth_token=' + EntryStoreClient.findCookieValue(login, 'auth_token')
	}

	private static LocalDateTime authTokenExpires(String cookie) {
		def connection = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])
		assert connection.getResponseCode() == HTTP_OK
		return LocalDateTime.parse(JSON_PARSER.parseText(connection.inputStream.text)['authTokenExpires'] as String)
	}

	private static long secondsUntil(LocalDateTime time) {
		return Duration.between(LocalDateTime.now(), time).toSeconds()
	}
}
