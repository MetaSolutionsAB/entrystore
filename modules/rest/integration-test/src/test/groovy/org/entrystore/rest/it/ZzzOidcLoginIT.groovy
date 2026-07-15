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
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication
import spock.lang.Shared
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static org.entrystore.rest.springboot.configuration.CacheControlFilter.CACHE_CONTROL_AUTHENTICATED

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzOidcLoginIT extends KeycloakBaseSpec {

	// below username and password must match the creds configured in Keycloak - "test-realm-keycloak.json"
	static def testUsername = 'testoidcuser'
	static def testUserPassword = 'oidcpassword'
	// The default username-claim is `email`, so the JIT-provisioned EntryStore username is the email.
	static def testUserEmail = 'testoidcuser@test.com'
	static def successLoginUrl = 'http://localhost:8181/GREAT-OIDC-SUCCESS/'
	static def failureLoginUrl = 'http://localhost:8181/OIDC-FAILURE/'

	static def keycloakIssuerUrl = ''

	@Shared
	def providerAuthUrlSaved = ''
	@Shared
	def loginPageHtmlSaved = ''
	@Shared
	def idpCookieHeaderSaved = ''
	@Shared
	def callbackUrlSaved = ''

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startKeycloakIfNeeded()
		keycloakIssuerUrl = getKeycloakOidcIssuerUrl()

		log.info('Starting EntryStoreApp with OIDC')
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.oidc.enabled=true',
			'--entrystore.auth.oidc.redirect-failure.url=' + failureLoginUrl,
			'--spring.profiles.active=oidc',
			'--spring.security.oauth2.client.provider.keycloak.issuer-uri=' + keycloakIssuerUrl
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	def '1. GET /auth/oidc should redirect via /oauth2/authorization/keycloak to the provider authorization endpoint'() {
		when: 'OIDC login is initiated with a custom success URL'
		def initConn = EntryStoreClient.getRequest('/auth/oidc' + convertMapToQueryParams([successurl: successLoginUrl]), '')

		then: 'AuthController redirects to the Spring Security authorization endpoint, carrying the successurl'
		initConn.getResponseCode() in [302, 303]
		def authorizationLocation = initConn.getHeaderField('Location')
		authorizationLocation != null
		authorizationLocation.contains('/oauth2/authorization/keycloak')
		authorizationLocation.contains('successurl=')

		when: 'following the redirect to the authorization endpoint'
		def authorizationConn = EntryStoreClient.getRequest(authorizationLocation, '')

		then: 'Spring Security redirects to the Keycloak authorization endpoint with code-flow parameters'
		authorizationConn.getResponseCode() in [302, 303]
		def providerAuthUrl = authorizationConn.getHeaderField('Location')
		providerAuthUrl != null
		providerAuthUrl.startsWith(keycloakIssuerUrl + '/protocol/openid-connect/auth')
		providerAuthUrl.contains('client_id=entrystore-oidc')
		providerAuthUrl.contains('response_type=code')
		providerAuthUrl.contains('scope=openid')
		providerAuthUrl.contains('state=')

		cleanup: 'store the provider authorization URL for the next step'
		this.providerAuthUrlSaved = providerAuthUrl
	}

	def '2. GET the provider authorization URL and receive the Keycloak login page'() {
		given:
		assert this.providerAuthUrlSaved: 'provider authorization URL not available, did the previous test step execute correctly?'

		when: 'requesting the authorization URL'
		def loginPageConn = EntryStoreClient.getRequest(this.providerAuthUrlSaved, '', '')

		then: 'Keycloak serves its login page and establishes an auth session'
		loginPageConn.getResponseCode() == HTTP_OK
		loginPageConn.getContentType().contains('text/html')
		def loginPageHtml = loginPageConn.inputStream.text
		loginPageHtml != null
		loginPageHtml.contains('<form id="kc-form-login" ')
		loginPageHtml.contains(' id="username" ')
		loginPageHtml.contains(' id="password" ')
		def idpCookies = loginPageConn.getHeaderFields()['Set-Cookie']
		idpCookies != null
		idpCookies.any { it.contains('AUTH_SESSION_ID=') }

		cleanup: 'store the data for the next step'
		this.loginPageHtmlSaved = loginPageHtml
		this.idpCookieHeaderSaved = idpCookies.collect { it.split(';')[0] }.join('; ')
	}

	def '3. Submit credentials to Keycloak — should redirect back to the EntryStore callback with code and state'() {
		given: 'Login form data from previous test'
		assert this.loginPageHtmlSaved: 'Login page HTML not available, did the previous test step execute correctly?'
		assert this.idpCookieHeaderSaved: 'IDP cookies not available, did the previous test step execute correctly?'

		def formActionMatcher = this.loginPageHtmlSaved =~ /action="([^"]+)"/
		String formActionUrl = formActionMatcher ? formActionMatcher[0][1] : null
		assert formActionUrl: 'Form action URL not found in login page'
		// Unescape HTML entities in the URL (Keycloak escapes &amp;)
		formActionUrl = StringEscapeUtils.unescapeHtml4(formActionUrl)

		def loginFormData = createFormBody([username: testUsername, password: testUserPassword])

		when: 'Submit login credentials to Keycloak'
		def submitLoginConn = EntryStoreClient.postRequest(formActionUrl, loginFormData, '',
			'application/x-www-form-urlencoded', [Cookie: this.idpCookieHeaderSaved])

		then: 'Keycloak redirects back to the EntryStore OIDC callback with an authorization code'
		submitLoginConn.getResponseCode() in [302, 303, 307]
		def callbackUrl = submitLoginConn.getHeaderField('Location')
		callbackUrl != null
		callbackUrl.contains('/login/oauth2/code/keycloak')
		callbackUrl.contains('code=')
		callbackUrl.contains('state=')

		cleanup:
		this.callbackUrlSaved = callbackUrl
	}

	def '4. Follow the callback — EntryStore exchanges the code, provisions the user and establishes a session'() {
		given:
		assert this.callbackUrlSaved: 'Callback URL not available, did the previous test step execute correctly?'

		when: 'following the callback redirect (browser carries no EntryStore session cookie here)'
		def callbackConn = EntryStoreClient.getRequest(this.callbackUrlSaved, '')

		then: 'EntryStore authenticates the user and redirects to the whitelist-validated custom success URL'
		callbackConn.getResponseCode() in [302, 303, 307]
		def successRedirectUrl = callbackConn.getHeaderField('Location')
		successRedirectUrl != null
		successRedirectUrl == successLoginUrl
		// Check if we got an auth cookie from EntryStore
		def spCookies = callbackConn.getHeaderFields()['Set-Cookie']
		spCookies != null
		spCookies.any { it.contains('auth_token=') }

		and: 'The 302 carrying the session Set-Cookie must ship with Cache-Control: private, no-store'
		// Regression sentinel for ENTRYSTORE-945: a shared cache keying on URL alone must not cache
		// the redirect and replay the session cookie to another client. CacheAwareRedirectStrategy
		// stamps the header before sendRedirect commits the response — see CacheControlFilter Javadoc.
		callbackConn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED

		// Query EntryStore using the new cookie - should return info about the auto-provisioned user,
		// named after the email claim (the default username-claim)
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user',
			null, null, [Cookie: spCookies.join('; ')])
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		currentlyLoggedInUserConn.getContentType().contains('application/json')
		def userJson = JSON_PARSER.parseText(currentlyLoggedInUserConn.inputStream.text)
		userJson['id'] != null
		userJson['user'] == testUserEmail
		(userJson['uri'] as String).startsWith(EntryStoreClient.baseUrl + '/_principals/entry/')

		and: 'the OIDC session carries ROLE_USER (parity with SAML/CAS) — /auth/tokens must not be denied'
		// SecurityConfig gates /auth/tokens on hasAnyRole(USER, ADMIN); Spring's OIDC login only
		// grants OIDC_USER/SCOPE_* by itself, so this locks the ROLE_USER remapping in
		// UsernameClaimOidcUserService.
		def tokensConn = EntryStoreClient.getRequest('/auth/tokens', null, null, [Cookie: spCookies.join('; ')])
		tokensConn.getResponseCode() == HTTP_OK
	}

	def '5. Replaying the consumed callback must not establish an authenticated session'() {
		given:
		assert this.callbackUrlSaved: 'Callback URL not available, did the previous test step execute correctly?'

		when: 'the identical callback URL is requested again'
		def replayConn = EntryStoreClient.getRequest(this.callbackUrlSaved, '')

		then: 'the state was consumed in step 4 (cache invalidated), so authentication fails to the failure URL'
		replayConn.getResponseCode() in [302, 303, 307]
		replayConn.getHeaderField('Location') == failureLoginUrl

		when: 'Using any cookies set by the rejected response, query /auth/user'
		def leakedCookies = replayConn.getHeaderFields()['Set-Cookie']
		def userConn
		if (leakedCookies != null && !leakedCookies.isEmpty()) {
			userConn = EntryStoreClient.getRequest('/auth/user', '', null,
				[Cookie: leakedCookies.collect { it.split(';')[0] }.join('; ')])
		} else {
			userConn = EntryStoreClient.getRequest('/auth/user', '')
		}

		then: 'Caller is not authenticated as the OIDC user (401, or 200 resolving to a different user)'
		// A Set-Cookie for auth_token may appear on the rejected response — the failure handler's
		// saveException() creates a session for the error attribute. That session carries no
		// authentication, so the replay signal is the session contents, not the presence of the
		// cookie header (same rationale as ZzzCasLoginIT step 4).
		def responseCode = userConn.getResponseCode()
		def responseUser = responseCode == HTTP_OK ? JSON_PARSER.parseText(userConn.inputStream.text)['user'] : null
		responseCode == HTTP_UNAUTHORIZED || (responseCode == HTTP_OK && responseUser != testUserEmail)
	}

	def '6. Non-whitelisted successurl must be dropped at initiation (open-redirect guard)'() {
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
