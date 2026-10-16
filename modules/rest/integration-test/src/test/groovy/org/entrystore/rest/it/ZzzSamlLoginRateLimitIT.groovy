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
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_OK
import static org.entrystore.rest.springboot.filter.CacheControlFilter.CACHE_CONTROL_CREDENTIALS

/**
 * SAML login start is limited per client address before Spring writes the request caches. The app allows two starts
 * per hour; the steps share that budget, so they run in order.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzSamlLoginRateLimitIT extends KeycloakBaseSpec {

	static def failureLoginUrl = EntryStoreClient.origin + '/GREAT-FAILURE/'

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()

		startOwnedApp([
			'--entrystore.auth.saml.enabled=true',
			'--spring.profiles.active=saml',
			'--spring.security.saml2.relyingparty.registration.keycloak.assertingparty.metadata-uri=' + getKeycloakSamlRealmUrl() + '/descriptor',
			'--entrystore.auth.saml.rate.limit.max=2',
			'--entrystore.auth.saml.rate.limit.window=1h'
		])
	}

	def '1. GET /saml2/authenticate/{id} within the limit renders the form that posts the SAMLRequest to the IdP'() {
		when:
		def connection = EntryStoreClient.getRequest('/saml2/authenticate/keycloak', null, null)

		then:
		connection.getResponseCode() == HTTP_OK
		hiddenInputValue(connection.inputStream.text, 'SAMLRequest') != null
	}

	def '2. GET /auth/saml within the limit reaches the IdP form through its redirect'() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/saml', null, null)
		// HttpURLConnection applies redirects lazily, on the first response read below.
		connection.setInstanceFollowRedirects(true)

		then:
		connection.getResponseCode() == HTTP_OK
		hiddenInputValue(connection.inputStream.text, 'SAMLRequest') != null
	}

	def '3. A third login start through GET /auth/saml is redirected to its failureurl instead of the IdP'() {
		given: '/auth/saml itself is not counted; it redirects to /saml2/authenticate, which is'
		def start = EntryStoreClient.getRequest('/auth/saml' + convertMapToQueryParams([failureurl: failureLoginUrl]),
			null, null)
		assert start.getResponseCode() in [302, 303]

		when:
		def connection = EntryStoreClient.getRequest(start.getHeaderField('Location'), null, null)

		then:
		connection.getResponseCode() == 302
		connection.getHeaderField('Location') == failureLoginUrl
		connection.getHeaderField('Cache-Control') == CACHE_CONTROL_CREDENTIALS
	}

	def '4. A login start from another client address still reaches the IdP'() {
		when:
		def connection = EntryStoreClient.getRequest('/saml2/authenticate/keycloak', null, null,
			['X-Forwarded-For': '203.0.113.120'])

		then:
		connection.getResponseCode() == HTTP_OK
		hiddenInputValue(connection.inputStream.text, 'SAMLRequest') != null
	}
}
