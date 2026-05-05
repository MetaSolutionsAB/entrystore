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

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication
import spock.lang.Stepwise

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_OK

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzSignupRateLimiterIT extends BaseSpec {

	static final int HTTP_TOO_MANY_REQUESTS = 429

	static def newPassword = 'newPass12345'
	static def firstName = 'First'
	static def lastName = 'Last'
	static def grecaptcharesponse = 'anything'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		stopPreexistingAppIfRunning()
		greenMail.start()

		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.signup.rate.limit.max=2',
			'--entrystore.auth.signup.rate.limit.window=1h'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	def cleanupSpec() {
		greenMail.stop()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def signupBody(String email) {
		JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			email             : email,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
	}

	def signupFormBody(String email) {
		"firstname=${firstName}&lastname=${lastName}&email=${email}&password=${newPassword}&g-recaptcha-response=${grecaptcharesponse}"
	}

	def "POST /auth/signup — first request from IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('signupRateLimit1@test.com'))

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/signup — second request from same IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('signupRateLimit2@test.com'))

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/signup — third request from same IP is rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('signupRateLimit3@test.com'))

		then:
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
	}

	def "POST /auth/signup — form-based request is also rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup',
			signupFormBody('signupRateLimit4@test.com'), null, 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
	}
}
