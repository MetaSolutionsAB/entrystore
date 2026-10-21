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

import static org.entrystore.rest.springboot.filter.CacheControlFilter.CACHE_CONTROL_CREDENTIALS

/**
 * OIDC login start is limited per client address before Spring writes the request caches. The app allows two starts
 * per hour; the steps share that budget, so they run in order.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzOidcLoginRateLimitIT extends KeycloakBaseSpec {

	static def failureLoginUrl = EntryStoreClient.origin + '/OIDC-RATE-LIMIT-FAILURE/'

	static def keycloakIssuerUrl = ''

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		keycloakIssuerUrl = getKeycloakOidcIssuerUrl()

		startOwnedApp([
			'--entrystore.auth.oidc.enabled=true',
			'--spring.profiles.active=oidc',
			'--spring.security.oauth2.client.provider.keycloak.issuer-uri=' + keycloakIssuerUrl,
			'--spring.security.oauth2.client.provider.keycloak2.issuer-uri=' + keycloakIssuerUrl,
			'--entrystore.auth.oidc.rate.limit.max=2',
			'--entrystore.auth.oidc.rate.limit.window=1h'
		])
	}

	def '1. GET /oauth2/authorization/{id} within the limit redirects to the provider'() {
		when:
		def connection = EntryStoreClient.getRequest('/oauth2/authorization/keycloak', '')

		then:
		connection.getResponseCode() in [302, 303]
		connection.getHeaderField('Location').startsWith(keycloakIssuerUrl + '/protocol/openid-connect/auth')
	}

	def '2. GET /auth/oidc within the limit reaches the provider through its redirect'() {
		given:
		def start = EntryStoreClient.getRequest('/auth/oidc', '')
		assert start.getResponseCode() in [302, 303]

		when:
		def connection = EntryStoreClient.getRequest(start.getHeaderField('Location'), '')

		then:
		connection.getResponseCode() in [302, 303]
		connection.getHeaderField('Location').startsWith(keycloakIssuerUrl + '/protocol/openid-connect/auth')
	}

	def '3. A third login start through GET /auth/oidc is redirected to its failureurl instead of the provider'() {
		given: '/auth/oidc itself is not counted; it redirects to /oauth2/authorization, which is'
		def start = EntryStoreClient.getRequest('/auth/oidc' + convertMapToQueryParams([failureurl: failureLoginUrl]),
			'')
		assert start.getResponseCode() in [302, 303]

		when:
		def connection = EntryStoreClient.getRequest(start.getHeaderField('Location'), '')

		then:
		connection.getResponseCode() == 302
		connection.getHeaderField('Location') == failureLoginUrl
		connection.getHeaderField('Cache-Control') == CACHE_CONTROL_CREDENTIALS
	}

	def '4. A login start from another client address still reaches the provider'() {
		when:
		def connection = EntryStoreClient.getRequest('/oauth2/authorization/keycloak', '', 'application/json',
			['X-Forwarded-For': '203.0.113.121'])

		then:
		connection.getResponseCode() in [302, 303]
		connection.getHeaderField('Location').startsWith(keycloakIssuerUrl + '/protocol/openid-connect/auth')
	}
}
