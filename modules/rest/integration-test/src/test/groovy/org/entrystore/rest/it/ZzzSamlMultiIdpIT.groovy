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

/**
 * Auto-provisioning follows the policy of the IdP that authenticated the user: two IdPs, one with
 * user-auto-provisioning on and one off, and no default-idp to fall back to.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzSamlMultiIdpIT extends KeycloakBaseSpec {

	// below username and password must match the creds configured in Keycloak - "test-realm-keycloak.json"
	static def testUsername = 'testuserrr'
	static def testUserPassword = 'passworded'
	static def successLoginUrl = EntryStoreClient.origin + '/GREAT-SUCCESS/'
	static def failureLoginUrl = EntryStoreClient.origin + '/GREAT-FAILURE/'
	static def openIdp = 'keycloak'
	static def closedIdp = 'keycloak-closed'

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		def metadataUri = getKeycloakSamlRealmUrl() + '/descriptor'
		def openRegistration = '--spring.security.saml2.relyingparty.registration.' + openIdp
		def closedRegistration = '--spring.security.saml2.relyingparty.registration.' + closedIdp

		log.info('Starting EntryStoreApp with two SAML IdPs and no default IdP')
		// Both registrations use the realm's EntrystoreDev1 client, whose redirect URIs accept either ACS URL.
		startOwnedApp([
			'--entrystore.auth.saml.enabled=true',
			'--spring.profiles.active=saml',
			openRegistration + '.assertingparty.metadata-uri=' + metadataUri,
			closedRegistration + '.entity-id=EntrystoreDev1',
			closedRegistration + '.assertingparty.metadata-uri=' + metadataUri,
			closedRegistration + '.assertingparty.singlesignon.binding=post',
			closedRegistration + '.assertingparty.singlesignon.sign-request=false',
			'--entrystore.auth.saml.default-idp=',
			'--entrystore.auth.saml.idp.' + closedIdp + '.user-auto-provisioning=off'
		])
	}

	def '1. An unknown user is not provisioned via the IdP with user-auto-provisioning off'() {
		given: 'the SAMLResponse Keycloak returns after a login started for the closed IdP'
		def (spCallbackUrl, spPostBody) = samlCallbackVia(closedIdp)

		when: 'POST the SAMLResponse back to the Service Provider'
		def spCallbackConn = EntryStoreClient.postRequest(spCallbackUrl, spPostBody, null,
			'application/x-www-form-urlencoded')

		then: 'the login is denied to the per-request failure URL'
		// A response Spring rejects lands here too; step 3 shows this IdP's responses are accepted.
		spCallbackConn.getResponseCode() in [302, 303, 307]
		spCallbackConn.getHeaderField('Location') == failureLoginUrl
	}

	def '2. An unknown user is provisioned via the IdP with user-auto-provisioning on'() {
		given: 'the SAMLResponse Keycloak returns after a login started for the open IdP'
		def (spCallbackUrl, spPostBody) = samlCallbackVia(openIdp)

		when: 'POST the SAMLResponse back to the Service Provider'
		def spCallbackConn = EntryStoreClient.postRequest(spCallbackUrl, spPostBody, null,
			'application/x-www-form-urlencoded')

		then: 'the login succeeds'
		spCallbackConn.getResponseCode() in [302, 303, 307]
		spCallbackConn.getHeaderField('Location') == successLoginUrl
		def spCookies = spCallbackConn.getHeaderFields()['Set-Cookie']
		spCookies.any { it.contains('auth_token=') }

		when: 'Query EntryStore using the new session cookie'
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user', null, null,
			[Cookie: EntryStoreClient.toCookieHeader(spCookies)])

		then: 'it names the provisioned SAML user'
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(currentlyLoggedInUserConn.inputStream.text)['user'] == testUsername
	}

	def '3. The now existing user logs in via the IdP with user-auto-provisioning off'() {
		given: 'the SAMLResponse Keycloak returns after a login started for the closed IdP'
		def (spCallbackUrl, spPostBody) = samlCallbackVia(closedIdp)

		when: 'POST the SAMLResponse back to the Service Provider'
		def spCallbackConn = EntryStoreClient.postRequest(spCallbackUrl, spPostBody, null,
			'application/x-www-form-urlencoded')

		then: 'the login succeeds, so step 1 was denied by the provisioning policy alone'
		spCallbackConn.getResponseCode() in [302, 303, 307]
		spCallbackConn.getHeaderField('Location') == successLoginUrl
	}

	/**
	 * Starts a login at /auth/saml for the IdP, logs in at Keycloak, and returns the IdP's assertion consumer
	 * service URL and the form body that Keycloak's self-submitting SAMLResponse page posts to it.
	 */
	private static List samlCallbackVia(String idp) {
		def connection = EntryStoreClient.getRequest('/auth/saml' +
			convertMapToQueryParams([idp: idp, successurl: successLoginUrl, failureurl: failureLoginUrl]), null, null)
		// The client disables redirect-following; HttpURLConnection applies a re-enabled one on the first read.
		connection.setInstanceFollowRedirects(true)
		assert connection.getResponseCode() == HTTP_OK
		def samlRequestPage = connection.inputStream.text
		def samlResponsePage = samlResponsePageFromKeycloak(hiddenInputValue(samlRequestPage, 'SAMLRequest'),
			hiddenInputValue(samlRequestPage, 'RelayState'), testUsername, testUserPassword)
		def spCallbackUrl = formActionUrl(samlResponsePage)
		assert spCallbackUrl.endsWith('/login/saml2/sso/' + idp)
		def formData = [SAMLResponse: hiddenInputValue(samlResponsePage, 'SAMLResponse'),
						RelayState  : hiddenInputValue(samlResponsePage, 'RelayState')].findAll { it.value }
		return [spCallbackUrl, createFormBody(formData)]
	}
}
