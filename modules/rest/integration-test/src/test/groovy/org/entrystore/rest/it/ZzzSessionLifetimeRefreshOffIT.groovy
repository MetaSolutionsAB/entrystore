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
 * whatever the activity, as in 5.x. Runs with a 5-second max-age.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSessionLifetimeRefreshOffIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.cookie.max-age=5',
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
		EntryStoreClient.findSetCookie(login, 'auth_token').contains('Max-Age=5')
	}

	def "a login should end max-age after it was made, even while it is used"() {
		given:
		def cookie = cookieOf(login(''))

		when: 'the client keeps making requests'
		sleep(2000)
		def afterTwoSeconds = userInfo(cookie)
		sleep(2000)
		def afterFourSeconds = userInfo(cookie)
		sleep(2000)
		def afterSixSeconds = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then: 'the expiry stays fixed and the login ends anyway'
		afterTwoSeconds['authTokenExpires'] == afterFourSeconds['authTokenExpires']
		afterSixSeconds.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "a shorter auth_maxage should shorten the fixed lifetime"() {
		given:
		def cookie = cookieOf(login('&auth_maxage=2'))
		assert userInfo(cookie)['user'] == 'admin'

		when:
		sleep(3000)
		def request = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		request.getResponseCode() == HTTP_UNAUTHORIZED
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
