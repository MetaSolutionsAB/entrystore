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
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.utility.MountableFile
import spock.lang.Shared
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP
import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8

@Stepwise
class CasLoginIT extends BaseSpec {

	static def testUsername = 'testcasuser'
	static def testUserPassword = 'caspassword'
	static def successLoginUrl = 'http://localhost:8181/GREAT-SUCCESS-2/'

	static def keycloakCasUrl = ''

	@Shared
	def loginPageHtmlSaved = ''
	@Shared
	def idpCookieHeaderSaved = ''
	@Shared
	def ticketRedirectUrlSaved = ''

	@Shared
	static KeycloakContainer keycloakContainer

	def setupSpec() {
		if (appInstance != null) {
			log.info('Stopping pre-existing ES instance')
			appInstance.close()
			EntryStoreClient.cleanCookies()
		}

		log.info('Starting Keycloak container with CAS protocol provider')
		// KeycloakContainer() from com.github.dasniko:testcontainers-keycloak:4.1.1 pulls "quay.io/keycloak/keycloak" version 26.5.6
		keycloakContainer = new KeycloakContainer()
			.withAdminUsername('admin')
			.withAdminPassword('admin')
			.withRealmImportFile('test-realm-cas.json')
			.withEnv('KC_DB', 'dev-file')
			.withCopyFileToContainer(
				MountableFile.forClasspathResource('libs/keycloak-protocol-cas-26.5.6.jar'),
				'/opt/keycloak/providers/keycloak-protocol-cas.jar'
			)

		keycloakContainer.start()
		log.info('Started Keycloak container at: {}:{}', keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
		keycloakCasUrl = keycloakContainer.getAuthServerUrl() + '/realms/test/protocol/cas'

		def logConsumer = new Slf4jLogConsumer(log)
		keycloakContainer.followOutput(logConsumer)

		log.info('Starting EntryStoreApp with CAS')
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.cas.enabled=true',
			'--entrystore.auth.cas.version=cas2',
			'--entrystore.auth.cas.server.url=' + keycloakCasUrl,
			'--entrystore.auth.cas.user-auto-provisioning=true',
			'--entrystore.auth.cas.redirect-success.url=' + successLoginUrl,
			'--entrystore.auth.cas.redirect-failure.url=http://localhost:8181/auth/login'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	def cleanupSpec() {
		if (appInstance != null) {
			log.info('Stopping EntryStoreApp instance with CAS')
			appInstance.close()
		}
		appStarted = false
	}

	def '1. GET /auth/cas should redirect to Keycloak CAS login page'() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/cas', '')

		then: 'EntryStore should redirect to Keycloak CAS login page'
		connection.getResponseCode() == HTTP_MOVED_TEMP
		def locationHeader = connection.getHeaderField('Location')
		locationHeader != null
		locationHeader.contains(keycloakCasUrl + '/login')
		locationHeader.contains('service=')

		when: 'Follow redirect to Keycloak CAS login'
		def casLoginConn = EntryStoreClient.getRequest(locationHeader, '', '')

		then: 'Should get the Keycloak login page'
		casLoginConn.getResponseCode() == HTTP_OK
		casLoginConn.getContentType().contains('text/html')
		def loginPageHtml = casLoginConn.inputStream.text
		loginPageHtml != null
		loginPageHtml.contains('<form id="kc-form-login" ')
		loginPageHtml.contains(' id="username" ')
		loginPageHtml.contains(' id="password" ')
		def idpCookies = casLoginConn.getHeaderFields()['Set-Cookie']
		idpCookies != null
		idpCookies.any { it.contains('AUTH_SESSION_ID=') }
		def cookieHeader = idpCookies.collect { it.split(';')[0] }.join('; ')

		cleanup: 'store data for next step'
		this.loginPageHtmlSaved = loginPageHtml
		this.idpCookieHeaderSaved = cookieHeader
	}

	def '2. Submit credentials to Keycloak — should redirect back to EntryStore with CAS ticket'() {
		given:
		assert this.loginPageHtmlSaved: 'Login page HTML not available from previous step'
		assert this.idpCookieHeaderSaved: 'IDP cookies not available from previous step'

		def formActionMatcher = this.loginPageHtmlSaved =~ /action="([^"]+)"/
		String formActionUrl = formActionMatcher ? formActionMatcher[0][1] : null
		assert formActionUrl: 'Form action URL not found in login page'
		formActionUrl = StringEscapeUtils.unescapeHtml4(formActionUrl)

		def loginFormData = "username=${URLEncoder.encode(testUsername, UTF_8)}&password=${URLEncoder.encode(testUserPassword, UTF_8)}"

		when: 'Submit login credentials to Keycloak'
		def submitLoginConn = EntryStoreClient.postRequest(formActionUrl, loginFormData, '',
			'application/x-www-form-urlencoded', [Cookie: this.idpCookieHeaderSaved])

		then: 'Keycloak should redirect back to EntryStore /auth/cas with a CAS ticket'
		submitLoginConn.getResponseCode() in [302, 303, 307]
		def redirectUrl = submitLoginConn.getHeaderField('Location')
		redirectUrl != null
		redirectUrl.contains('/auth/cas')
		redirectUrl.contains('ticket=')

		cleanup:
		this.ticketRedirectUrlSaved = redirectUrl
	}

	def '3. Follow redirect with CAS ticket — EntryStore validates and establishes session'() {
		given:
		assert this.ticketRedirectUrlSaved: 'Ticket redirect URL not available from previous step'

		when: 'Follow the redirect to EntryStore with the CAS ticket'
		def casCallbackConn = EntryStoreClient.getRequest(this.ticketRedirectUrlSaved, '')

		then: 'EntryStore should authenticate the user and redirect to success URL'
		casCallbackConn.getResponseCode() in [302, 303, 307]
		def successRedirectUrl = casCallbackConn.getHeaderField('Location')
		successRedirectUrl != null
		successRedirectUrl == successLoginUrl
		def spCookies = casCallbackConn.getHeaderFields()['Set-Cookie']
		spCookies != null
		spCookies.any { it.contains('auth_token=') }

		when: 'Query EntryStore using the new cookie'
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user',
			'', null, [Cookie: spCookies.join('; ')])

		then: 'Should return info about the CAS-authenticated user'
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		currentlyLoggedInUserConn.getContentType().contains('application/json')
		def userJson = JSON_PARSER.parseText(currentlyLoggedInUserConn.inputStream.text)
		userJson['id'] != null
		userJson['user'] == testUsername
		(userJson['uri'] as String).startsWith(EntryStoreClient.baseUrl + '/_principals/entry/')
	}
}
