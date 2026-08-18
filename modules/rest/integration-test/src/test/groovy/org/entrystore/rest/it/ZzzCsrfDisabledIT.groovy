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

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_OK

/**
 * Verifies CSRF-disabled behaviour — which is the DEFAULT ({@code entrystore.csrf.enabled=false},
 * ENTRYSTORE-1096): cookie-authenticated mutations succeed without an X-XSRF-TOKEN header, and no
 * XSRF-TOKEN cookie is issued. The app is deliberately started WITHOUT the property so this spec
 * pins the default; a future default flip to enabled will fail here and must consciously update
 * this spec. The enabled-mode contract (rejection without a token, exemption list) is covered by
 * {@link CsrfIT} against the shared app, which runs with {@code --entrystore.csrf.enabled=true}.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzCsrfDisabledIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()

		// No --entrystore.csrf.enabled arg on purpose — this spec verifies the default (off).
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	// Intentionally no cleanupSpec — matches the canonical pattern of ZzzCasLoginIT and
	// ZzzSamlLoginIT. The next lifecycle-owning IT's stopPreexistingAppIfRunning() closes
	// our appInstance; resetting appInstance=null or appStarted=false here would violate
	// BaseSpec invariant #2 (see BaseSpec.groovy:58-66).

	// Logins below bypass EntryStoreClient.authorize on purpose: authorize asserts that the
	// XSRF-TOKEN cookie is present on the login response, which a CSRF-disabled app never emits.

	def "POST /auth/cookie should not emit an XSRF-TOKEN cookie when CSRF protection is disabled"() {
		when:
		def login = EntryStoreClient.postRequest('/auth/cookie',
			'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')

		then:
		login.getResponseCode() == HTTP_OK
		EntryStoreClient.findSetCookie(login, 'auth_token') != null
		EntryStoreClient.findSetCookie(login, 'XSRF-TOKEN') == null
	}

	def "POST as cookie-authenticated user without CSRF token should succeed when CSRF protection is disabled"() {
		given: 'a fresh admin session'
		def login = EntryStoreClient.postRequest('/auth/cookie',
			'auth_username=admin&auth_password=adminpass', '', 'application/x-www-form-urlencoded')
		assert login.getResponseCode() == HTTP_OK
		def authToken = EntryStoreClient.findCookieValue(login, 'auth_token')
		assert authToken != null

		when: 'a mutation carrying only the session cookie — the exact request shape CsrfIT proves is rejected when protection is on'
		def conn = EntryStoreClient.postRequest('/_principals/groups?name=csrfDisabledGroup', '{}', '',
			'application/json', [Cookie: 'auth_token=' + authToken])

		then:
		conn.getResponseCode() == HTTP_CREATED
	}
}
