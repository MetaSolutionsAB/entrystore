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

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * With {@code entrystore.auth.cookie.refresh-expiration-on-access} off, a login ends max-age after it was made,
 * whatever the activity, as in 5.x. Runs with a 10-second max-age; each probe keeps a margin of at least 2 seconds.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSessionLifetimeRefreshOffIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.cookie.max-age=10',
			'--entrystore.auth.cookie.refresh-expiration-on-access=off'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	// Intentionally no cleanupSpec — see ZzzCsrfDisabledIT.

	def "login should issue a cookie that lives max-age"() {
		when:
		def login = login('')

		then:
		login.getResponseCode() == HTTP_OK
		EntryStoreClient.findSetCookie(login, 'auth_token').contains('Max-Age=10')
	}

	def "a login should end max-age after it was made, even while it is used"() {
		given:
		def cookie = cookieOf(login(''))

		when: 'the client keeps making requests'
		sleep(3000)
		def afterThreeSeconds = userInfo(cookie)
		sleep(3000)
		def afterSixSeconds = userInfo(cookie)
		// 6 seconds after the last request: within the idle timeout, so only the fixed lifetime ends the login
		sleep(6000)
		def afterTwelveSeconds = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then: 'the expiry stays fixed and the login ends anyway'
		afterThreeSeconds['authTokenExpires'] == afterSixSeconds['authTokenExpires']
		afterTwelveSeconds.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "a shorter auth_maxage should shorten the fixed lifetime"() {
		given:
		def cookie = cookieOf(login('&auth_maxage=6'))

		when:
		sleep(4000)
		def afterFourSeconds = userInfo(cookie)
		// 4 seconds after the last request: within the idle timeout, so only the fixed lifetime ends the login
		sleep(4000)
		def afterEightSeconds = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		afterFourSeconds['user'] == 'admin'
		afterEightSeconds.getResponseCode() == HTTP_UNAUTHORIZED
	}

	private static HttpURLConnection login(String extraParams) {
		return EntryStoreClient.postRequest('/auth/cookie', 'auth_username=admin&auth_password=adminpass' + extraParams,
			'', 'application/x-www-form-urlencoded')
	}

	private static String cookieOf(HttpURLConnection login) {
		return 'auth_token=' + EntryStoreClient.findCookieValue(login, 'auth_token')
	}

	private static Map userInfo(String cookie) {
		def connection = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])
		assert connection.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(connection.inputStream.text) as Map
	}
}
