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

		startOwnedApp([
			'--entrystore.auth.recaptcha.url=' + getRecaptchaStubUrl(),
			'--entrystore.auth.signup.rate.limit.max=2',
			'--entrystore.auth.signup.rate.limit.window=1h',
			'--entrystore.trust.x-forwarded-for=true'
		])
	}

	def cleanupSpec() {
		// Only release the SMTP port. Closing appInstance or resetting appStarted/appInstance
		// would violate BaseSpec invariant #2 (see BaseSpec.groovy:64-72) — the next lifecycle-
		// owning IT's stopPreexistingAppIfRunning() is what closes our appInstance, and an extra
		// shared-app re-init between Zzz ITs makes the Jetty rebind to port 8181 racy with the
		// OS socket-release timer.
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
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/signup — form-based request from same IP is allowed and bumps the counter"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup',
			signupFormBody('signupRateLimit2@test.com'), 'admin', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/signup — third request from same IP is rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('signupRateLimit3@test.com'))

		then:
		// If the previous form-based step had not invoked acquirePermit, the bucket would still
		// have budget here and the response would be 200 — so this 429 is also evidence that the
		// form path goes through the limiter.
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /auth/signup — form-based request after limit is exhausted is rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup',
			signupFormBody('signupRateLimit4@test.com'), 'admin', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /auth/signup — request from a different X-Forwarded-For IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup',
			signupBody('signupRateLimit5@test.com'), 'admin', 'application/json',
			['X-Forwarded-For': '203.0.113.99'])

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/signup — request from yet another X-Forwarded-For IP is also allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup',
			signupBody('signupRateLimit6@test.com'), 'admin', 'application/json',
			['X-Forwarded-For': '203.0.113.100'])

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}
}
