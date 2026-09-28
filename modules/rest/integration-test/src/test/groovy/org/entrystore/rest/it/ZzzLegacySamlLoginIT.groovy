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

import org.apache.commons.text.StringEscapeUtils
import org.entrystore.rest.it.util.EntryStoreClient
import spock.lang.Shared
import spock.lang.TempDir

import static java.net.HttpURLConnection.HTTP_OK

/** Keeps the IdP's registered entity ID and ACS fixed while starting EntryStore with only 5.x SAML keys. */
class ZzzLegacySamlLoginIT extends KeycloakBaseSpec {

	@Shared
	@TempDir
	File configDirectory

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		def properties = new Properties()
		getClass().classLoader.getResourceAsStream('entrystore-it.properties').withCloseable { properties.load(it) }
		properties.keySet().removeIf { it.toString().startsWith('entrystore.auth.saml') }
		getClass().classLoader.getResourceAsStream('entrystore-legacy-saml.properties').withCloseable {
			properties.load(it)
		}
		def configFile = new File(configDirectory, 'entrystore.properties')
		configFile.withOutputStream { properties.store(it, 'Legacy SAML regression fixture') }
		startOwnedApp([
			'--entrystore-test.config-uri=' + configFile.toURI(),
			'--legacy-test.metadata-url=' + getKeycloakSamlRealmUrl() + '/descriptor',
			'--entrystore.csrf.enabled=true',
			'--entrystore.auth.cookie.invalid-token-error=false'
		])
	}

	def 'unchanged legacy SAML configuration completes login through the registered legacy ACS'() {
		given:
		def successUrl = EntryStoreClient.origin + '/legacy-success/'
		def start = EntryStoreClient.getRequest('/auth/saml?successurl=' + URLEncoder.encode(successUrl, 'UTF-8'),
			null, null)
		start.setInstanceFollowRedirects(true)
		assert start.responseCode == HTTP_OK
		def authnForm = start.inputStream.text
		def samlRequest = formValue(authnForm, 'SAMLRequest')
		def relayState = formValue(authnForm, 'RelayState')

		when:
		def idp = EntryStoreClient.postRequest(getKeycloakSamlRealmUrl(),
			createFormBody([SAMLRequest: samlRequest, RelayState: relayState]),
			null, 'application/x-www-form-urlencoded')
		assert idp.responseCode in [302, 303, 307]
		def cookie = EntryStoreClient.toCookieHeader(idp.headerFields['Set-Cookie'])
		def page = EntryStoreClient.getRequest(idp.getHeaderField('Location'), null, null, [Cookie: cookie])
		assert page.responseCode == HTTP_OK
		def login = EntryStoreClient.postRequest(extractFormActionUrl(page.inputStream.text),
			createFormBody([username: 'testuserrr', password: 'passworded']),
			null, 'application/x-www-form-urlencoded', [Cookie: cookie])
		assert login.responseCode == HTTP_OK
		def responseForm = login.inputStream.text
		def callback = extractCallback(responseForm)

		then:
		callback == 'http://localhost:8181/store/auth/saml?tenant=legacy&idp=legacy-keycloak'

		when:
		def completed = EntryStoreClient.postRequest(callback,
			createFormBody([SAMLResponse: formValue(responseForm, 'SAMLResponse'),
				RelayState: formValue(responseForm, 'RelayState')]),
			null, 'application/x-www-form-urlencoded', [Cookie: 'auth_token=stale-session'])

		then:
		completed.responseCode in [302, 303, 307]
		completed.getHeaderField('Location') == successUrl
		completed.getHeaderField('Cache-Control') == 'private, no-store'
		def session = EntryStoreClient.findSetCookie(completed, 'auth_token')
		session != null
		def user = EntryStoreClient.getRequest('/auth/user', null, null,
			[Cookie: EntryStoreClient.toCookieHeader([session])])
		user.responseCode == HTTP_OK
		JSON_PARSER.parseText(user.inputStream.text).user == 'testuserrr'
	}

	def 'invalid cookie can fall back to guest when the legacy setting is false'() {
		when:
		def response = EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: 'auth_token=stale-session'])

		then:
		response.responseCode == HTTP_OK
		JSON_PARSER.parseText(response.inputStream.text).id == '_guest'
		def removed = EntryStoreClient.findSetCookie(response, 'auth_token')
		removed != null
		def expiredCookie = HttpCookie.parse(removed).first()
		expiredCookie.value == ''
		expiredCookie.path == '/store'
		expiredCookie.hasExpired()
	}

	private static String formValue(String html, String name) {
		def match = html =~ /name=["']${name}["'][^>]*\bvalue=["']([^"']*)["']/
		assert match.find(): 'Missing ' + name
		return StringEscapeUtils.unescapeHtml4(match.group(1))
	}

	private static String extractCallback(String html) {
		def match = html =~ /action=["']([^"']+)["']/
		assert match.find()
		return StringEscapeUtils.unescapeHtml4(match.group(1))
	}
}
