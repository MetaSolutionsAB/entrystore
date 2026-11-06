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

import static com.github.tomakehurst.wiremock.client.WireMock.containing
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_OK

/**
 * reCAPTCHA on with only the private key, the shape of every 5.x reCAPTCHA config: EntryStore must start,
 * render its own forms without the widget and still verify the tokens that clients such as EntryScape post.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzRecaptchaWithoutSiteKeyIT extends BaseSpec {

	// entrystore.auth.recaptcha.private-key in entrystore-it.properties
	static final String PRIVATE_KEY = '6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		stopPreexistingAppIfRunning()
		greenMail.start()

		startOwnedApp([
			'--entrystore.auth.recaptcha.url=' + getRecaptchaStubUrl(),
			'--entrystore.auth.recaptcha.public-key='
		])
	}

	def cleanupSpec() {
		// Only release the SMTP port; the next lifecycle-owning IT closes this app (BaseSpec invariant #2).
		greenMail.stop()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	static String signupBody(String email, String token) {
		JsonOutput.toJson([firstname: 'First', lastname: 'Last', email: email, password: 'newPass12345',
						   grecaptcharesponse: token])
	}

	def "GET /auth/signup renders the form without the reCAPTCHA widget"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/signup')

		then:
		conn.getResponseCode() == HTTP_OK
		def html = conn.inputStream.text
		html.contains('<form')
		!html.contains('data-sitekey')
		!html.contains('recaptcha/api.js')
	}

	def "POST /auth/signup with a token verified by the private key sends the confirmation email"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('recaptchaNoSiteKey@test.com', 'anything'))

		then:
		conn.getResponseCode() == HTTP_OK
		// The sign-up email is sent in the background (ENTRYSTORE-1261).
		greenMail.waitForIncomingEmail(5000, 1)
		greenMail.getReceivedMessages().length == 1
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo('/recaptcha/api/siteverify'))
			.withRequestBody(containing('secret=' + PRIVATE_KEY)))
	}

	def "POST /auth/signup without a token is still rejected"() {
		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', signupBody('recaptchaNoToken@test.com', ''))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		greenMail.getReceivedMessages().length == 0
	}
}
