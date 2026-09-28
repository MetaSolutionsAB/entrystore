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

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.apache.commons.text.StringEscapeUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.utility.MountableFile
import spock.lang.Shared

import static java.net.HttpURLConnection.HTTP_OK

// Child Spec classes of this base must have a Zzz* prefix so Failsafe's alphabetical runOrder
// schedules them after all shared-app ITs. The shared Spring Boot app runs for all shared-app
// ITs first; each lifecycle-owning IT then closes it and starts its own Spring Boot with
// SSO-specific args, reusing this single Keycloak container. See ENTRYSTORE-1019.
abstract class KeycloakBaseSpec extends BaseSpec {

	// Merged realm test-realm-keycloak.json contains SAML, CAS and OIDC clients; the CAS
	// protocol jar is mounted so all three flows are available from one container (Keycloak
	// speaks OIDC natively — no extra provider jar needed for it).
	@Shared
	static KeycloakContainer keycloakContainer = new KeycloakContainer()
		.withAdminUsername('admin')
		.withAdminPassword('admin')
		.withRealmImportFile('test-realm-keycloak.json')
		.withEnv('KC_DB', 'dev-file')
		.withCopyFileToContainer(
			MountableFile.forClasspathResource('libs/keycloak-protocol-cas-26.5.6.jar'),
			'/opt/keycloak/providers/keycloak-protocol-cas.jar'
		)

	static void startKeycloakIfNeeded() {
		if (keycloakContainer.isRunning()) {
			log.info('Reusing Keycloak container at: {}:{}',
				keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
			return
		}
		log.info('Starting Keycloak container')
		keycloakContainer.start()
		keycloakContainer.followOutput(new Slf4jLogConsumer(log))
		log.info('Started Keycloak container at: {}:{}',
			keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
	}

	static String getKeycloakSamlRealmUrl() {
		return keycloakContainer.getAuthServerUrl() + '/realms/test/protocol/saml'
	}

	static String getKeycloakCasRealmUrl() {
		return keycloakContainer.getAuthServerUrl() + '/realms/test/protocol/cas'
	}

	static String getKeycloakOidcIssuerUrl() {
		return keycloakContainer.getAuthServerUrl() + '/realms/test'
	}

	/** Extracts the (HTML-unescaped) form action URL from a Keycloak login page. */
	protected static String extractFormActionUrl(String loginPageHtml) {
		def formActionMatcher = loginPageHtml =~ /action="([^"]+)"/
		String formActionUrl = formActionMatcher ? formActionMatcher[0][1] : null
		assert formActionUrl: 'Form action URL not found in login page'
		// The regex takes the first form on the page, so pin it to the credential form — any
		// interstitial Keycloak renders ahead of it (locale switcher, required action, terms) would
		// otherwise be returned as the login endpoint.
		assert formActionUrl.contains('login-actions/authenticate'): 'not a credential-form action URL: ' + formActionUrl
		return StringEscapeUtils.unescapeHtml4(formActionUrl)
	}

	/**
	 * Plays the IdP side of a SAML login up to Keycloak's self-submitting SAMLResponse form: posts the
	 * SAMLRequest, opens the login page and submits the credentials. Returns the HTML of that form.
	 */
	protected static String samlResponsePageFromKeycloak(String samlRequest, String relayState,
														 String username, String password) {
		def postData = [SAMLRequest: samlRequest]
		if (relayState) {
			postData['RelayState'] = relayState
		}
		def samlRequestConn = EntryStoreClient.postRequest(getKeycloakSamlRealmUrl(), createFormBody(postData),
			null, 'application/x-www-form-urlencoded')
		assert samlRequestConn.getResponseCode() in [302, 303, 307]
		def cookieHeader = EntryStoreClient.toCookieHeader(samlRequestConn.getHeaderFields()['Set-Cookie'])
		def loginPageConn = EntryStoreClient.getRequest(samlRequestConn.getHeaderField('Location'), null, null,
			[Cookie: cookieHeader])
		assert loginPageConn.getResponseCode() == HTTP_OK
		def loginConn = EntryStoreClient.postRequest(extractFormActionUrl(loginPageConn.inputStream.text),
			createFormBody([username: username, password: password]), null, 'application/x-www-form-urlencoded',
			[Cookie: cookieHeader])
		assert loginConn.getResponseCode() == HTTP_OK
		return loginConn.inputStream.text
	}

	/** The (HTML-unescaped) value of the named hidden input of a SAML form page, or null. */
	protected static String hiddenInputValue(String html, String name) {
		def matcher = html =~ /name=['"]${name}['"][^>]*\bvalue=['"]([^'"]+)['"]/
		return matcher ? StringEscapeUtils.unescapeHtml4(matcher[0][1]) : null
	}

	/** The (HTML-unescaped) action URL of the first form of a SAML form page, or null. */
	protected static String formActionUrl(String html) {
		def matcher = html =~ /action=['"]([^'"]+)['"]/
		return matcher ? StringEscapeUtils.unescapeHtml4(matcher[0][1]) : null
	}

}
