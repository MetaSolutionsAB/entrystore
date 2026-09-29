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
import spock.lang.Shared
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_OK

/**
 * A SAML login with an unchanged EntryStore 5.x multi-IdP configuration: the 5.x keys are translated at
 * startup (LegacyPropertyTranslator), and the IdP posts its response to the 5.x assertion consumer service
 * URL (/auth/saml?idp=keycloak) rather than to Spring's /login/saml2/sso/{registrationId}.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzSamlLegacyConfigIT extends KeycloakBaseSpec {

	// below username and password must match the creds configured in Keycloak - "test-realm-keycloak.json"
	static def testUsername = 'testuserrr'
	static def testUserPassword = 'passworded'
	static def successLoginUrl = EntryStoreClient.origin + '/GREAT-SUCCESS/'
	static def legacyAcsUrl = EntryStoreClient.baseUrl + '/auth/saml'

	@Shared
	def samlRequestSaved = ''
	@Shared
	def relayStateSaved = ''

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()

		log.info('Starting EntryStoreApp with a 5.x SAML configuration')
		// No 'saml' profile and no spring.security.saml2.* keys: the registration comes from the 5.x keys only.
		startOwnedApp([
			'--entrystore.auth.saml=new',
			'--entrystore.auth.saml.idps.1=keycloak',
			'--entrystore.auth.saml.idp.keycloak.relying-party-id=EntrystoreDev1',
			'--entrystore.auth.saml.idp.keycloak.metadata.url=' + getKeycloakSamlRealmUrl() + '/descriptor',
			'--entrystore.auth.saml.idp.keycloak.redirect-method=post',
			'--entrystore.auth.saml.assertion-consumer-service.url=' + legacyAcsUrl,
			'--entrystore.csrf.enabled=true'
		])
	}

	def '1. GET /auth/saml should send an AuthnRequest naming the 5.x assertion consumer service URL'() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/saml' +
			convertMapToQueryParams([idp: 'keycloak', successurl: successLoginUrl]), null, null)
		// The client disables redirect-following; re-enabling it here still works because
		// HttpURLConnection applies redirects lazily, on the first response read below.
		connection.setInstanceFollowRedirects(true)

		then: 'following the SP redirect chain ends on the self-submitting SAMLRequest form page'
		connection.getResponseCode() == HTTP_OK
		def response = connection.inputStream.text
		response.contains('action="' + getKeycloakSamlRealmUrl() + '"')
		def samlRequest = hiddenInputValue(response, 'SAMLRequest')
		samlRequest != null
		new String(Base64.decoder.decode(samlRequest), 'UTF-8')
			.contains('AssertionConsumerServiceURL="' + legacyAcsUrl + '?idp=keycloak"')

		cleanup: 'store the request for the next step'
		this.samlRequestSaved = samlRequest
		this.relayStateSaved = hiddenInputValue(response, 'RelayState') ?: ''
	}

	def '2. POST the SAMLResponse to the 5.x assertion consumer service URL and complete authentication'() {
		given: 'the SAMLResponse form Keycloak returns after the login'
		assert this.samlRequestSaved: 'samlRequestSaved is empty, did the previous test step execute correctly?'
		def samlResponsePage = samlResponsePageFromKeycloak(this.samlRequestSaved, this.relayStateSaved,
			testUsername, testUserPassword)
		def spCallbackUrl = formActionUrl(samlResponsePage)
		assert spCallbackUrl == legacyAcsUrl + '?idp=keycloak': 'the IdP must post to the 5.x assertion consumer service URL'
		def spPostData = [SAMLResponse: hiddenInputValue(samlResponsePage, 'SAMLResponse')]
		def samlRelayState = hiddenInputValue(samlResponsePage, 'RelayState')
		if (samlRelayState) {
			spPostData['RelayState'] = samlRelayState
		}

		and: 'a session cookie without an X-XSRF-TOKEN header, so only the CSRF exemption lets the POST through'
		def adminSessionCookie = EntryStoreClient.toCookieHeader([EntryStoreClient.loginIsolated('admin').authCookie as String])

		when: 'POST SAMLResponse back to Service Provider'
		def spCallbackConn = EntryStoreClient.postRequest(spCallbackUrl, createFormBody(spPostData),
			null, 'application/x-www-form-urlencoded', [Cookie: adminSessionCookie])

		then: 'Service Provider should authenticate the user, redirecting to success URL'
		spCallbackConn.getResponseCode() in [302, 303, 307]
		spCallbackConn.getHeaderField('Location') == successLoginUrl
		def spCookies = spCallbackConn.getHeaderFields()['Set-Cookie']
		spCookies.any { it.contains('auth_token=') }

		when: 'Query EntryStore using the new session cookie'
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user', null, null,
			[Cookie: EntryStoreClient.toCookieHeader(spCookies)])

		then: 'it names the SAML user'
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(currentlyLoggedInUserConn.inputStream.text)['user'] == testUsername
	}
}
