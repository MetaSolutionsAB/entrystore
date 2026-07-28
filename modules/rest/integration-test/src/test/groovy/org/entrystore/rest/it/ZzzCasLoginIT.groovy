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

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static org.entrystore.rest.springboot.configuration.CacheControlFilter.CACHE_CONTROL_AUTHENTICATED

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzCasLoginIT extends KeycloakBaseSpec {

	static def testUsername = 'testcasuser'
	static def testUserPassword = 'caspassword'
	static def successLoginUrl = EntryStoreClient.origin + '/GREAT-SUCCESS-2/'

	static def keycloakCasUrl = ''

	@Shared
	def loginPageHtmlSaved = ''
	@Shared
	def idpCookieHeaderSaved = ''
	@Shared
	def ticketRedirectUrlSaved = ''

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		keycloakCasUrl = getKeycloakCasRealmUrl()

		log.info('Starting EntryStoreApp with CAS')
		startOwnedApp([
			'--entrystore.auth.cas.enabled=true',
			'--entrystore.auth.cas.version=cas2',
			'--entrystore.auth.cas.server.url=' + keycloakCasUrl,
			'--entrystore.auth.cas.user-auto-provisioning=true',
			'--entrystore.auth.cas.redirect-success.url=' + successLoginUrl,
			'--entrystore.auth.cas.redirect-failure.url=' + EntryStoreClient.origin + '/auth/login',
			// Override the IT default (httponly=off) so the auth_token HttpOnly assertion is meaningful
			// for CAS sessions. Production default is HttpOnly=on.
			'--entrystore.auth.cookie.httponly=on'
		])
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
		def cookieHeader = EntryStoreClient.toCookieHeader(idpCookies)

		cleanup: 'store data for next step'
		this.loginPageHtmlSaved = loginPageHtml
		this.idpCookieHeaderSaved = cookieHeader
	}

	def '2. Submit credentials to Keycloak — should redirect back to EntryStore with CAS ticket'() {
		given:
		assert this.loginPageHtmlSaved: 'Login page HTML not available from previous step'
		assert this.idpCookieHeaderSaved: 'IDP cookies not available from previous step'

		def formActionUrl = extractFormActionUrl(this.loginPageHtmlSaved)

		def loginFormData = createFormBody([username: testUsername, password: testUserPassword])

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
		spCookies.any { it.contains('auth_token=') && it.contains('HttpOnly') }

		and: 'The 302 carrying the session Set-Cookie must ship with Cache-Control: private, no-store'
		// Regression sentinel for ENTRYSTORE-945 PR #283 round-1 review: a shared cache keying
		// on URL alone must not cache the redirect and replay the session cookie to another
		// client. CacheAwareRedirectStrategy stamps the header before sendRedirect commits the
		// response — see CacheControlFilter Javadoc.
		casCallbackConn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED

		when: 'Query EntryStore using the new cookie'
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user',
			'', null, [Cookie: EntryStoreClient.toCookieHeader(spCookies)])

		then: 'Should return info about the CAS-authenticated user'
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		currentlyLoggedInUserConn.getContentType().contains('application/json')
		def userJson = JSON_PARSER.parseText(currentlyLoggedInUserConn.inputStream.text)
		userJson['id'] != null
		userJson['user'] == testUsername
		(userJson['uri'] as String).startsWith(EntryStoreClient.baseUrl + '/_principals/entry/')
	}

	def '4. Reserved admin username is blocked and does not leak authenticated session'() {
		// Regression test for the auth-bypass fix. Previously, CasAuthenticationFilter persisted
		// the CasAuthenticationToken to the session before CasLoginSuccessHandler ran the reserved-
		// username check — so the rejected "admin" login would still authenticate as the EntryStore
		// admin on subsequent requests. The handler must now clear SecurityContext and invalidate
		// the session before redirecting to the failure URL.

		given: 'A CAS flow starting from /auth/cas with fresh (no) cookies'
		def connection = EntryStoreClient.getRequest('/auth/cas', '')

		when: 'Follow the redirect chain to Keycloak login'
		def casLoginConn = EntryStoreClient.getRequest(connection.getHeaderField('Location'), '', '')
		def adminIdpCookies = casLoginConn.getHeaderFields()['Set-Cookie']
		def adminCookieHeader = EntryStoreClient.toCookieHeader(adminIdpCookies)
		def adminLoginPageHtml = casLoginConn.inputStream.text
		def adminFormActionUrl = extractFormActionUrl(adminLoginPageHtml)

		and: 'Submit admin credentials'
		def adminLoginFormData = createFormBody([username: 'admin', password: 'adminpassword'])
		def adminSubmitConn = EntryStoreClient.postRequest(adminFormActionUrl, adminLoginFormData, '',
			'application/x-www-form-urlencoded', [Cookie: adminCookieHeader])
		def adminTicketUrl = adminSubmitConn.getHeaderField('Location')

		and: 'Keycloak issued a CAS ticket for admin (guards against realm drift — otherwise step 4 can pass vacuously without exercising EntryStore\'s reject path)'
		assert adminSubmitConn.getResponseCode() in [302, 303, 307]:
			'Keycloak did not redirect — realm config may have changed (admin disabled? required actions added?)'
		assert adminTicketUrl != null: 'No Location header in Keycloak response'
		assert adminTicketUrl.contains('/auth/cas'): 'Keycloak redirect is not to EntryStore CAS callback'
		assert adminTicketUrl.contains('ticket='): 'Keycloak did not issue a CAS ticket — check test-realm-keycloak.json'

		and: 'Follow redirect to EntryStore with the admin CAS ticket'
		def adminCallbackConn = EntryStoreClient.getRequest(adminTicketUrl, '')

		then: 'EntryStore redirects to the failure URL, not the success URL'
		adminCallbackConn.getResponseCode() in [302, 303, 307]
		adminCallbackConn.getHeaderField('Location') != successLoginUrl
		adminCallbackConn.getHeaderField('Location').contains('/auth/login')

		when: 'Using any cookies set by the rejected response, query /auth/user'
		def leakedCookies = adminCallbackConn.getHeaderFields()['Set-Cookie']
		def userConn
		if (leakedCookies != null && !leakedCookies.isEmpty()) {
			userConn = EntryStoreClient.getRequest('/auth/user', '', null,
				[Cookie: EntryStoreClient.toCookieHeader(leakedCookies)])
		} else {
			userConn = EntryStoreClient.getRequest('/auth/user', '')
		}

		then: 'Caller is not authenticated as admin (401, or 200 resolving to any non-admin user)'
		// Note: a Set-Cookie for auth_token may still appear on the response (Jetty issues it when
		// SessionFixationProtectionStrategy changes the session ID, before our success handler
		// rejects admin). That cookie points to an invalidated session server-side, so reusing it
		// yields 401/guest (see CookieLoginResourceIT). The bypass signal is the session contents,
		// not the presence of the cookie header.
		def responseCode = userConn.getResponseCode()
		def responseUser = responseCode == HTTP_OK ? JSON_PARSER.parseText(userConn.inputStream.text)['user'] : null
		responseCode == HTTP_UNAUTHORIZED ||
			(responseCode == HTTP_OK && responseUser != null && !responseUser.toString().equalsIgnoreCase('admin'))
	}
}
