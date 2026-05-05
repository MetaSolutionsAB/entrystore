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
import org.entrystore.rest.it.util.UserUtil
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication
import spock.lang.Stepwise

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_OK

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzPasswordResetRateLimiterIT extends BaseSpec {

	static final int HTTP_TOO_MANY_REQUESTS = 429

	static def newPassword = 'newPass12345'
	static def grecaptcharesponse = 'anything'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		stopPreexistingAppIfRunning()
		greenMail.start()

		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.auth.password-reset.rate.limit.max=2',
			'--entrystore.auth.password-reset.rate.limit.window=1h',
			'--entrystore.trust.x-forwarded-for=true'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true

		// pwReset only sends an email when the user exists; create the targets up-front.
		(1..5).each { i -> UserUtil.createUser("pwResetRateLimit${i}@test.com") }
	}

	def cleanupSpec() {
		try {
			appInstance?.close()
		} finally {
			appInstance = null
			appStarted = false
			EntryStoreClient.cleanCookies()
			greenMail.stop()
		}
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def pwResetBody(String email) {
		JsonOutput.toJson([
			email             : email,
			password          : newPassword,
			urlsuccess        : '',
			urlfailure        : '',
			grecaptcharesponse: grecaptcharesponse
		])
	}

	def "POST /auth/pwreset — first request from IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody('pwResetRateLimit1@test.com'))

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/pwreset — second request from same IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody('pwResetRateLimit2@test.com'))

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/pwreset — third request from same IP is rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody('pwResetRateLimit3@test.com'))

		then:
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /auth/pwreset — request from a different X-Forwarded-For IP is allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset',
			pwResetBody('pwResetRateLimit4@test.com'), 'admin', 'application/json',
			['X-Forwarded-For': '203.0.113.99'])

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}

	def "POST /auth/pwreset — request from yet another X-Forwarded-For IP is also allowed"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset',
			pwResetBody('pwResetRateLimit5@test.com'), 'admin', 'application/json',
			['X-Forwarded-For': '203.0.113.100'])

		then:
		conn.getResponseCode() == HTTP_OK
		greenMail.getReceivedMessages().length == 1
	}
}
