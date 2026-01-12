package org.entrystore.rest.it

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.apache.commons.text.StringEscapeUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.springboot.EntryStoreApplicationStandaloneSpringBoot
import org.springframework.boot.SpringApplication
import org.testcontainers.containers.output.Slf4jLogConsumer
import spock.lang.Shared
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8

// Stepwise makes sure the feature methods run in the order they are declared
@Stepwise
class SamlLoginIT extends BaseSpec {

	// below username and password must match the creds configured in Keycloak - "test-realm.json"
	static def testUsername = 'testuserrr'
	static def testUserPassword = 'passworded'
	static def successLoginUrl = 'http://localhost:8181/GREAT-SUCCESS/'

	static def keycloakTestRealmUrl = ''

	@Shared
	def backendCookieHeaderSaved = ''
	@Shared
	def samlRequestSaved = ''
	@Shared
	def relayStateSaved = ''
	@Shared
	def loginPageHtmlSaved = ''
	@Shared
	def idpCookieHeaderSaved = ''
	@Shared
	def samlResponsePageSaved = ''

	@Shared
	static def keycloakContainer = new KeycloakContainer()
	// you can access Keycloak admin console by visiting URL printed with the log 'Started Keycloak container at: localhost:xxxx'
	// need to add a debug-point somewhere after keycloakContainer is started, to stop the test from finishing
	// use below admin credentials to login to the Keycloak admin console
		.withAdminUsername('admin')
		.withAdminPassword('admin')
		.withRealmImportFile('test-realm.json')
	// use a simple file-based H2 database. The data is temporary and will be lost when the container stops.
		.withEnv('KC_DB', 'dev-file')

	def setupSpec() {
		if (appInstance != null) {
			log.info('Stopping pre-existing ES instance')
			appInstance.close()
			EntryStoreClient.cleanCookies()
		}
		log.info('Starting Keycloak container')
		keycloakContainer.start()
		log.info('Started Keycloak container at: {}:{}', keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
		keycloakTestRealmUrl = keycloakContainer.getAuthServerUrl() + 'realms/test/protocol/saml'

		// below 2 lines allow to stream Keycloak logs to this Spec execution console
		def logConsumer = new Slf4jLogConsumer(log)
		keycloakContainer.followOutput(logConsumer)

		log.info('Starting EntryStoreApp with SAML')
		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.saml.enabled=true',
			'--spring.profiles.active=saml',
			'--spring.security.saml2.relyingparty.registration.keycloak.assertingparty.metadata-uri=' + keycloakTestRealmUrl + '/descriptor'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationStandaloneSpringBoot.class, args)
		appStarted = true
	}

	def cleanupSpec() {
		if (appInstance != null) {
			log.info('Stopping EntryStoreApp instance with SAML')
			appInstance.close()
		}
		appStarted = false
	}

	def '1. GET /auth/saml should start SAML authentication flow - redirect to IDP with SAMLRequest'() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/saml?successurl=' + URLEncoder.encode(successLoginUrl, UTF_8),
			null, null)
		connection.setInstanceFollowRedirects(true)

		then: 'SP should redirect to IDP: 302 response code and location header set'
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		def backendCookies = connection.getHeaderFields()['Set-Cookie']
		backendCookies != null
		backendCookies.any { it.contains('auth_token=') }
		// remove additional attributes of cookies
		def backendCookieHeader = backendCookies.collect { it.split(';')[0] }.join('; ')
		def response = connection.getInputStream().text
		response.contains('action="' + keycloakTestRealmUrl + '"')
		response.contains('<input type="hidden" name="SAMLRequest" value="')

		// Extract SAMLRequest value from the HTML form
		def samlRequestMatcher = response =~ /name="SAMLRequest" value="([^"]+)"/
		String samlRequestValue = samlRequestMatcher ? samlRequestMatcher[0][1] : null
		samlRequestValue != null

		response.contains('<input type="hidden" name="RelayState" value="')
		// Extract RelayState value
		def relayStateMatcher = response =~ /name="RelayState" value="([^"]+)"/
		String relayStateValue = relayStateMatcher ? relayStateMatcher[0][1] : null

		cleanup: 'store the URL to IDP for next step'
		this.samlRequestSaved = StringEscapeUtils.unescapeHtml4(samlRequestValue)
		this.relayStateSaved = relayStateValue ? StringEscapeUtils.unescapeHtml4(relayStateValue) : ''
		this.backendCookieHeaderSaved = backendCookieHeader
	}

	def '2. Send SAMLRequest to IDP and get login page'() {
		given:
		assert this.samlRequestSaved: 'idlSamlRequestSaved is null or empty, did the previous test step execute correctly?'
		def postData = 'SAMLRequest=' + URLEncoder.encode(this.samlRequestSaved, UTF_8)
		if (this.relayStateSaved) {
			postData += '&RelayState=' + URLEncoder.encode(this.relayStateSaved, UTF_8)
		}

		when: 'SamlRequest is send to IDP'
		def sendSamlReqToIdpConn = EntryStoreClient.postRequest(keycloakTestRealmUrl, postData, null,
			'application/x-www-form-urlencoded')

		then: 'IDP should redirect to login page, after successful verification of the client'
		sendSamlReqToIdpConn.getResponseCode() in [302, 303, 307]
		def loginPageUrl = sendSamlReqToIdpConn.getHeaderField('Location')
		loginPageUrl != null
		loginPageUrl.contains('login-actions/authenticate')
		def idpCookies = sendSamlReqToIdpConn.getHeaderFields()['Set-Cookie']
		idpCookies != null
		idpCookies.any { it.contains('AUTH_SESSION_ID=') }

		when: 'Follow redirect to get login page'
		// remove additional attributes of cookies
		def cookieHeader = idpCookies.collect { it.split(';')[0] }.join('; ')
		def loginPageConn = EntryStoreClient.getRequest(loginPageUrl, null, null,
			[Cookie: cookieHeader])

		then: 'Should get the login page'
		loginPageConn.getResponseCode() == HTTP_OK
		loginPageConn.getContentType().contains('text/html')
		def loginPageHtml = loginPageConn.getInputStream().text
		loginPageHtml != null
		loginPageHtml.contains('<form id="kc-form-login" ')
		loginPageHtml.contains(' id="username" ')
		loginPageHtml.contains(' id="password" ')

		cleanup: 'store the data for next step'
		this.loginPageHtmlSaved = loginPageHtml
		this.idpCookieHeaderSaved = cookieHeader
	}

	def '3. After successful authentication with IDP, it should reply with a SAMLResponse'() {
		given: 'Login form data from previous test'
		assert this.loginPageHtmlSaved: 'Login page HTML not available, did the previous test step execute correctly?'
		assert this.idpCookieHeaderSaved: 'IDP cookies not available, did the previous test step execute correctly?'

		// Extract form action URL
		def formActionMatcher = this.loginPageHtmlSaved =~ /action="([^"]+)"/
		String formActionUrl = formActionMatcher ? formActionMatcher[0][1] : null
		assert formActionUrl: 'Form action URL not found in login page'
		// Unescape HTML entities in the URL (Keycloak escapes &amp;)
		formActionUrl = StringEscapeUtils.unescapeHtml4(formActionUrl)

		def loginFormData = "username=${URLEncoder.encode(testUsername, UTF_8)}&password=${URLEncoder.encode(testUserPassword, UTF_8)}"

		when: 'Submit login credentials to IDP'
		def submitLoginConn = EntryStoreClient.postRequest(formActionUrl, loginFormData, null,
			'application/x-www-form-urlencoded', [Cookie: this.idpCookieHeaderSaved])

		then: 'IDP (Keycloak) should return a form with SAMLResponse'
		submitLoginConn.getResponseCode() == HTTP_OK
		submitLoginConn.getContentType().contains('text/html')
		def samlResponsePage = submitLoginConn.getInputStream().text
		samlResponsePage != null
		samlResponsePage.contains('name="SAMLResponse" value="')

		cleanup:
		this.samlResponsePageSaved = samlResponsePage
	}

	def '4. POST SAMLResponse back to Service Provider and complete authentication'() {
		given: 'SAMLResponse page from previous test'
		assert this.samlResponsePageSaved: 'SAMLResponse page not available, did the previous test step execute correctly?'

		// Extract SAMLResponse
		def samlResponseMatcher = this.samlResponsePageSaved =~ /name=['"]SAMLResponse['"] value=['"]([^'"]+)['"]/
		String samlResponseValue = samlResponseMatcher ? samlResponseMatcher[0][1] : null
		assert samlResponseValue: 'SAMLResponse value not found'
		// Unescape HTML entities in SAMLResponse
		samlResponseValue = StringEscapeUtils.unescapeHtml4(samlResponseValue)

		// Extract the form action (should be the SP callback URL - Entrystore url)
		def samlFormActionMatcher = this.samlResponsePageSaved =~ /action=['"]([^'"]+)['"]/
		String spCallbackUrl = samlFormActionMatcher ? samlFormActionMatcher[0][1] : null
		assert spCallbackUrl: 'SP callback URL not found'
		spCallbackUrl = StringEscapeUtils.unescapeHtml4(spCallbackUrl)
		assert spCallbackUrl.contains('/login/saml2/sso/keycloak'): 'SP callback URL does not point to /login/saml2/sso/keycloak'

		// Extract RelayState if present
		def samlRelayStateMatcher = this.samlResponsePageSaved =~ /name=['"]RelayState['"] value=['"]([^'"]+)['"]/
		String samlRelayState = samlRelayStateMatcher ? StringEscapeUtils.unescapeHtml4(samlRelayStateMatcher[0][1]) : ''

		when: 'POST SAMLResponse back to Service Provider'
		def spPostData = "SAMLResponse=${URLEncoder.encode(samlResponseValue, UTF_8)}"
		if (samlRelayState) {
			spPostData += "&RelayState=${URLEncoder.encode(samlRelayState, UTF_8)}"
		}

		def spCallbackConn = EntryStoreClient.postRequest(spCallbackUrl, spPostData, null,
			'application/x-www-form-urlencoded', [Cookie: this.backendCookieHeaderSaved])

		then: 'Service Provider should authenticate the user, redirecting to success URL'
		spCallbackConn.getResponseCode() in [302, 303, 307]
		def successloginRedirUrl = spCallbackConn.getHeaderField('Location')
		successloginRedirUrl != null
		successloginRedirUrl == 'http://localhost:8181/GREAT-SUCCESS/'
		// Check if we got an auth cookie from EntryStore
		def spCookies = spCallbackConn.getHeaderFields()['Set-Cookie']
		spCookies != null
		spCookies.any { it.contains('auth_token=') }

		// Query Entrystore using the new cookie - should return info about the new testuser
		def currentlyLoggedInUserConn = EntryStoreClient.getRequest('/auth/user',
			null, null, [Cookie: spCookies.join('; ')])
		currentlyLoggedInUserConn.getResponseCode() == HTTP_OK
		currentlyLoggedInUserConn.getContentType().contains('application/json')
		def userJson = JSON_PARSER.parseText(currentlyLoggedInUserConn.getInputStream().text)
		userJson['id'] != null
		userJson['user'] == testUsername
		(userJson['uri'] as String).startsWith(EntryStoreClient.baseUrl + '/_principals/entry/')
//		userJson['authTokenExpires'] != null
	}
}
