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

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8

/**
 * Switched-off authentication features answer 404 on all their routes, as 5.x left them unrouted. The
 * shared app runs with every feature on, so one owned app start covers the whole disabled matrix.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzAuthFeaturesOffIT extends BaseSpec {

	static final String FORM = 'application/x-www-form-urlencoded'
	static final String JSON = 'application/json'

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startOwnedApp([
			'--entrystore.auth.signup=off',
			'--entrystore.auth.password-reset=off'
		])
	}

	// Intentionally no cleanupSpec — see ZzzCsrfDisabledIT.

	def "#method #path (#contentType) should answer 404 when its feature is off"() {
		when:
		def conn = method == 'GET'
			? EntryStoreClient.getRequest(path, '', null)
			: EntryStoreClient.postRequest(path, body, '', contentType)

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains(JSON)
		JSON_PARSER.parseText(conn.errorStream.text)['status'] == 404

		where:
		method | path                     | contentType | body
		'POST' | '/auth/signup'           | JSON        | signupJson()
		'POST' | '/auth/signup'           | FORM        | 'firstname=A&lastname=B&email=off%40test.com&password=x'
		'GET'  | '/auth/signup'           | null        | null
		'GET'  | '/auth/signup?confirm=x' | null        | null
		'POST' | '/auth/signup/confirm'   | JSON        | confirmJson()
		'POST' | '/auth/signup/confirm'   | FORM        | 'confirm=x&email=off%40test.com&password=Passw0rd12345'
		'POST' | '/auth/pwreset'          | JSON        | JsonOutput.toJson([email: 'admin@test.com'])
		'POST' | '/auth/pwreset'          | FORM        | 'email=admin%40test.com'
		'GET'  | '/auth/pwreset'          | null        | null
		'GET'  | '/auth/pwreset?confirm=x'| null        | null
		'POST' | '/auth/pwreset/confirm'  | JSON        | confirmJson()
		'POST' | '/auth/pwreset/confirm'  | FORM        | 'confirm=x&email=off%40test.com&password=Passw0rd12345'
		// Answered before handler mapping and body parsing, so neither 400 nor 415
		'POST' | '/auth/signup'           | JSON        | '{not json'
		'POST' | '/auth/signup'           | 'text/plain'| 'hello'
	}

	def "a disabled route should answer 404 before the session cookie is checked"() {
		when: 'the request carries an unknown auth_token, which the security chain would answer with 401'
		def conn = EntryStoreClient.postRequest('/auth/signup', signupJson(), '', JSON,
			[Cookie: 'auth_token=unknown'])

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "a disabled route should answer 404 before HTTP Basic credentials are checked"() {
		when: 'the credentials are invalid, which the security chain would answer with 401'
		def conn = EntryStoreClient.postRequest('/auth/pwreset', JsonOutput.toJson([email: 'admin@test.com']), '',
			JSON, [Authorization: basic('nobody', 'wrong')])

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "a cross-origin request to a disabled route should get the CORS headers with its 404"() {
		when: 'EntryScape probes a confirmation token with credentials from an allowed origin'
		def conn = EntryStoreClient.getRequest('/auth/pwreset?confirm=x', '', null, [Origin: 'http://localhost:3000'])

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getHeaderField('Access-Control-Allow-Origin') == 'http://localhost:3000'
		conn.getHeaderField('Access-Control-Allow-Credentials') == 'true'
	}

	def "a preflight to a disabled route should be answered as one to any other route"() {
		given:
		def preflightHeaders = [Origin: 'http://example.com', 'Access-Control-Request-Method': 'POST']
		def unknownRoute = EntryStoreClient.sendRequestAsStream(HttpMethod.OPTIONS, '/auth/no-such-route', null, '',
			null, preflightHeaders)

		when:
		def conn = EntryStoreClient.sendRequestAsStream(HttpMethod.OPTIONS, '/auth/signup', null, '', null,
			preflightHeaders)

		then:
		conn.getResponseCode() == unknownRoute.getResponseCode()
		conn.getHeaderField('Access-Control-Allow-Origin') == 'http://example.com'
		conn.getHeaderField('Access-Control-Allow-Methods') ==
			unknownRoute.getHeaderField('Access-Control-Allow-Methods')
	}

	def "the extended status should report sign-up and password reset as off"() {
		when:
		def conn = EntryStoreClient.getRequest('/management/status/extended', '', JSON,
			[Authorization: basic('admin', 'adminpass')])

		then:
		conn.getResponseCode() == HTTP_OK
		def auth = JSON_PARSER.parseText(conn.inputStream.text)['auth']
		auth['signup'] == false
		auth['passwordReset'] == false
	}

	static String basic(String username, String password) {
		'Basic ' + Base64.getEncoder().encodeToString((username + ':' + password).getBytes(UTF_8))
	}

	static String signupJson() {
		JsonOutput.toJson([firstname: 'A', lastname: 'B', email: 'off@test.com', password: 'Passw0rd12345',
						   grecaptcharesponse: 'anything'])
	}

	static String confirmJson() {
		JsonOutput.toJson([confirm: 'x', email: 'off@test.com', password: 'Passw0rd12345'])
	}
}
