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

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST

/**
 * Stateless OIDC guard cases (unknown-provider 400, open-redirect drop), deliberately kept out of
 * the {@code @Stepwise} {@link ZzzOidcLoginIT}: there a failing guard would skip the whole login
 * flow (and a flow failure would mask the guards as skipped), so guards and flow must fail
 * independently. Each case needs only a running OIDC-enabled app — no login round-trip — but the
 * app boot itself needs Keycloak up, since the client registrations resolve their issuer-uri via
 * OIDC discovery at startup.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzOidcGuardsIT extends KeycloakBaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		def keycloakIssuerUrl = getKeycloakOidcIssuerUrl()

		log.info('Starting EntryStoreApp with OIDC (guard cases)')
		startOwnedApp([
			'--entrystore.auth.oidc.enabled=true',
			'--spring.profiles.active=oidc',
			'--spring.security.oauth2.client.provider.keycloak.issuer-uri=' + keycloakIssuerUrl,
			'--spring.security.oauth2.client.provider.keycloak2.issuer-uri=' + keycloakIssuerUrl
		])
	}

	def 'Unknown ?provider= parameter must fail with 400, not a filter-level HTML 500'() {
		when: 'OIDC login is initiated with a provider id that has no client registration'
		def initConn = EntryStoreClient.getRequest('/auth/oidc'
			+ convertMapToQueryParams([provider: 'no-such-registration']), '')

		then: 'AuthController rejects it with the JSON error envelope, not a filter-level HTML 500'
		initConn.getResponseCode() == HTTP_BAD_REQUEST
		initConn.getContentType().contains('application/json')
	}

	def 'Non-whitelisted successurl must be dropped at initiation (open-redirect guard)'() {
		when: 'OIDC login is initiated with a successurl pointing at a non-whitelisted host'
		def initConn = EntryStoreClient.getRequest('/auth/oidc'
			+ convertMapToQueryParams([successurl: 'http://evil.example.org/phishing']), '')

		then: 'the redirect to the authorization endpoint does not carry the successurl'
		initConn.getResponseCode() in [302, 303]
		def authorizationLocation = initConn.getHeaderField('Location')
		authorizationLocation != null
		authorizationLocation.contains('/oauth2/authorization/keycloak')
		!authorizationLocation.contains('successurl')
		!authorizationLocation.contains('evil.example.org')
	}
}
