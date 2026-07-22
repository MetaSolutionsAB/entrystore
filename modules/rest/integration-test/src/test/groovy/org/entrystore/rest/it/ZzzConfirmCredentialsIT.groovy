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

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

// Exercises the new credential-confirmation flow (ENTRYSTORE-529). It boots its own app instance with
// entrystore.auth.confirmation.legacy=false, because the shared BaseSpec app runs in the default
// (legacy) mode. The Zzz prefix sorts this class after all shared-app ITs under Failsafe's runOrder.
class ZzzConfirmCredentialsIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def firstName = 'First'
	static def lastName = 'Last'
	static def grecaptcharesponse = 'anything'
	static def formUrlEncoded = 'application/x-www-form-urlencoded'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		stopPreexistingAppIfRunning()
		greenMail.start()

		startOwnedApp([
			'--entrystore.auth.recaptcha.url=' + getRecaptchaStubUrl(),
			'--entrystore.auth.confirmation.legacy=false',
			'--entrystore.auth.confirmation.max-attempts=3',
			// Keep rate limits well above this class's request volume so confirmation behaviour, not
			// throttling, is what the assertions observe.
			'--entrystore.auth.signup.rate.limit.max=100',
			'--entrystore.auth.password-reset.rate.limit.max=100'
		])
	}

	def cleanupSpec() {
		// Only release the SMTP port — the next lifecycle-owning IT's stopPreexistingAppIfRunning()
		// closes our appInstance (see ZzzSignupRateLimiterIT and BaseSpec invariants).
		greenMail.stop()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	private static String extractToken(int index = 0) {
		def content = greenMail.getReceivedMessages()[index].getContent().toString()
		def start = content.indexOf('?confirm') + 9
		return content.substring(start, start + 16)
	}

	// Setup helper: performs the sign-up request that mints a confirmation token and returns it.
	// Convention-consistent with the sibling ITs' given:-block setup (see CLAUDE.md testing guidelines).
	private static String startSignup(String email) {
		def body = JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			email             : email,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/signup', body).getResponseCode() == HTTP_OK
		return extractToken()
	}

	// Setup helper: starts a password-reset flow for an existing user and returns the token.
	private static String startPwreset(String email) {
		def body = JsonOutput.toJson([email: email, grecaptcharesponse: grecaptcharesponse])
		assert EntryStoreClient.postRequest('/auth/pwreset', body).getResponseCode() == HTTP_OK
		assert greenMail.waitForIncomingEmail(5000, 1)
		return extractToken()
	}

	// ---------- sign-up ----------

	def "GET /auth/signup with a valid token renders the confirmation form"() {
		given:
		def token = startSignup('newSignupForm@test.com')

		when:
		def conn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then: "the form is shown but no account is created yet"
		conn.getResponseCode() == HTTP_OK
		def body = conn.inputStream.text
		body.contains('name="email"')
		body.contains('name="password"')
		body.contains('name="confirm"')
		body.contains(token)
	}

	def "POST /auth/signup/confirm creates the user with a valid token and matching credentials"() {
		given:
		def username = 'newSignupOk@test.com'
		def token = startSignup(username)

		when:
		def body = 'confirm=' + token + '&email=' + username + '&password=' + newPassword
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then:
		conn.getResponseCode() == HTTP_CREATED
		conn.inputStream.text.contains('Sign-up successful.')

		and: "the user can now log in"
		def loginBody = 'auth_username=' + username + '&auth_password=' + newPassword
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', formUrlEncoded)
		loginConn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/signup/confirm with a wrong password does not create the user"() {
		given:
		def username = 'newSignupWrongPass@test.com'
		def token = startSignup(username)

		when: "the link is opened by someone who does not know the chosen password"
		def body = 'confirm=' + token + '&email=' + username + '&password=totallyWrong123'
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('2 attempt(s) remaining')
		def loginBody = 'auth_username=' + username + '&auth_password=' + newPassword
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', formUrlEncoded)
		loginConn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/signup/confirm invalidates the token after three failed attempts"() {
		given:
		def username = 'newSignupThreeStrikes@test.com'
		def token = startSignup(username)

		when:
		def firstBody = 'confirm=' + token + '&email=' + username + '&password=wrongPass001'
		def first = EntryStoreClient.postRequest('/auth/signup/confirm', firstBody, null, formUrlEncoded)
		def secondBody = 'confirm=' + token + '&email=' + username + '&password=wrongPass002'
		def second = EntryStoreClient.postRequest('/auth/signup/confirm', secondBody, null, formUrlEncoded)
		def thirdBody = 'confirm=' + token + '&email=' + username + '&password=wrongPass003'
		def third = EntryStoreClient.postRequest('/auth/signup/confirm', thirdBody, null, formUrlEncoded)
		def fourthBody = 'confirm=' + token + '&email=' + username + '&password=' + newPassword
		def fourth = EntryStoreClient.postRequest('/auth/signup/confirm', fourthBody, null, formUrlEncoded)

		then: "the third failure invalidates the token; a later correct attempt cannot succeed"
		first.getResponseCode() == HTTP_UNAUTHORIZED
		second.getResponseCode() == HTTP_UNAUTHORIZED
		third.getResponseCode() == HTTP_BAD_REQUEST
		third.errorStream.text.contains('invalidated')
		fourth.getResponseCode() == HTTP_BAD_REQUEST
		def loginBody = 'auth_username=' + username + '&auth_password=' + newPassword
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', formUrlEncoded)
		loginConn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/signup/confirm accepts a JSON body"() {
		given:
		def username = 'newSignupJson@test.com'
		def token = startSignup(username)

		when:
		def jsonBody = JsonOutput.toJson([confirm: token, email: username, password: newPassword])
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', jsonBody, null, 'application/json')

		then:
		conn.getResponseCode() == HTTP_CREATED
		conn.inputStream.text.contains('Sign-up successful.')
	}

	def "POST /auth/signup/confirm with an unknown token is rejected"() {
		when:
		def body = 'confirm=bogusToken123456&email=whoever@test.com&password=' + newPassword
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.errorStream.text.contains('Invalid confirmation link.')
	}

	def "GET /auth/signup with an unknown token does not render the form"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/signup?confirm=bogusToken123456')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		!conn.errorStream.text.contains('name="password"')
	}

	def "POST /auth/signup/confirm matches the email case-insensitively"() {
		given:
		def username = 'newSignupCase@test.com'
		def token = startSignup(username)

		when: "the email is re-entered in a different case"
		def body = 'confirm=' + token + '&email=' + username.toUpperCase() + '&password=' + newPassword
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then:
		conn.getResponseCode() == HTTP_CREATED
		conn.inputStream.text.contains('Sign-up successful.')
	}

	def "POST /auth/signup/confirm with a wrong email but the right password does not create the user"() {
		given:
		def username = 'newSignupWrongEmail@test.com'
		def token = startSignup(username)

		when: "the link is opened by someone who re-enters a different email (with the correct password)"
		def body = 'confirm=' + token + '&email=someoneElse@test.com&password=' + newPassword
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then: "the email-mismatch branch rejects it and consumes an attempt"
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('2 attempt(s) remaining')

		and: "the token survives, so the genuine requester can still confirm with the correct email"
		def retryBody = 'confirm=' + token + '&email=' + username + '&password=' + newPassword
		def retryConn = EntryStoreClient.postRequest('/auth/signup/confirm', retryBody, null, formUrlEncoded)
		retryConn.getResponseCode() == HTTP_CREATED
		def loginBody = 'auth_username=' + username + '&auth_password=' + newPassword
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', formUrlEncoded)
		loginConn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/signup/confirm with a too-short password consumes an attempt (sign-up does not pre-validate format, unlike reset)"() {
		given:
		def username = 'newSignupShortPass@test.com'
		def token = startSignup(username)

		when: "a too-short password is submitted at confirm time"
		def body = 'confirm=' + token + '&email=' + username + '&password=short'
		def conn = EntryStoreClient.postRequest('/auth/signup/confirm', body, null, formUrlEncoded)

		then: "it is treated as a failed credential attempt (not a format error), so it consumes an attempt"
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('2 attempt(s) remaining')

		and: "the token survives, so the correct password still works"
		def retryBody = 'confirm=' + token + '&email=' + username + '&password=' + newPassword
		def retryConn = EntryStoreClient.postRequest('/auth/signup/confirm', retryBody, null, formUrlEncoded)
		retryConn.getResponseCode() == HTTP_CREATED
	}

	// ---------- password reset ----------

	def "POST /auth/pwreset starts the flow with the email only and sends a link"() {
		given:
		def username = 'newResetStart@test.com'
		UserUtil.createUser(username)

		when: "no password is supplied at request time"
		def conn = EntryStoreClient.postRequest('/auth/pwreset',
			JsonOutput.toJson([email: username, grecaptcharesponse: grecaptcharesponse]))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase())
		greenMail.waitForIncomingEmail(5000, 1)
		greenMail.getReceivedMessages().size() == 1
	}

	def "GET /auth/pwreset with a valid token renders the confirmation form"() {
		given:
		def username = 'newResetForm@test.com'
		UserUtil.createUser(username)
		def token = startPwreset(username)

		when:
		def conn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		conn.getResponseCode() == HTTP_OK
		def body = conn.inputStream.text
		body.contains('name="email"')
		body.contains('name="password"')
		body.contains(token)
	}

	def "GET /auth/pwreset with an unknown token does not render the form"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/pwreset?confirm=bogusToken123456')

		then: "assertPasswordResetTokenValid rejects it before the form is rendered"
		conn.getResponseCode() == HTTP_BAD_REQUEST
		!conn.errorStream.text.contains('name="password"')
	}

	def "POST /auth/pwreset/confirm sets the new password chosen at confirm time when the username matches"() {
		given:
		def username = 'newResetOk@test.com'
		UserUtil.createUser(username)
		def chosenNewPassword = 'freshPass4567'
		def token = startPwreset(username)

		when:
		def body = 'confirm=' + token + '&email=' + username + '&password=' + chosenNewPassword
		def conn = EntryStoreClient.postRequest('/auth/pwreset/confirm', body, null, formUrlEncoded)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.text.contains('Password reset was successful.')

		and: "the password chosen on the confirmation form works"
		def loginBody = 'auth_username=' + username + '&auth_password=' + chosenNewPassword
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', formUrlEncoded)
		loginConn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/pwreset/confirm with a wrong username does not change the password"() {
		given:
		def username = 'newResetWrongUser@test.com'
		UserUtil.createUser(username)
		def token = startPwreset(username)

		when: "a clicker who does not know the account's email tries to set a password"
		def body = 'confirm=' + token + '&email=someoneElse@test.com&password=freshPass4567'
		def conn = EntryStoreClient.postRequest('/auth/pwreset/confirm', body, null, formUrlEncoded)

		then: "the attempt is rejected and no password-change confirmation email is sent"
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('2 attempt(s) remaining')
		!greenMail.waitForIncomingEmail(500, 2)

		and: "the token survives, and the password is applied only once the correct username is supplied"
		def retryBody = 'confirm=' + token + '&email=' + username + '&password=freshPass4567'
		def retryConn = EntryStoreClient.postRequest('/auth/pwreset/confirm', retryBody, null, formUrlEncoded)
		retryConn.getResponseCode() == HTTP_OK
	}

	def "POST /auth/pwreset/confirm invalidates the token after three failed username attempts"() {
		given:
		def username = 'newResetThreeStrikes@test.com'
		UserUtil.createUser(username)
		def token = startPwreset(username)

		when:
		def firstBody = 'confirm=' + token + '&email=wrong1@test.com&password=freshPass4567'
		def first = EntryStoreClient.postRequest('/auth/pwreset/confirm', firstBody, null, formUrlEncoded)
		def secondBody = 'confirm=' + token + '&email=wrong2@test.com&password=freshPass4567'
		def second = EntryStoreClient.postRequest('/auth/pwreset/confirm', secondBody, null, formUrlEncoded)
		def thirdBody = 'confirm=' + token + '&email=wrong3@test.com&password=freshPass4567'
		def third = EntryStoreClient.postRequest('/auth/pwreset/confirm', thirdBody, null, formUrlEncoded)
		def fourthBody = 'confirm=' + token + '&email=' + username + '&password=freshPass4567'
		def fourth = EntryStoreClient.postRequest('/auth/pwreset/confirm', fourthBody, null, formUrlEncoded)

		then:
		first.getResponseCode() == HTTP_UNAUTHORIZED
		second.getResponseCode() == HTTP_UNAUTHORIZED
		third.getResponseCode() == HTTP_BAD_REQUEST
		third.errorStream.text.contains('invalidated')
		fourth.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/pwreset/confirm rejects a too-short new password without consuming the token"() {
		given:
		def username = 'newResetShortPass@test.com'
		UserUtil.createUser(username)
		def token = startPwreset(username)

		when: "a too-short password is submitted"
		def body = 'confirm=' + token + '&email=' + username + '&password=short'
		def conn = EntryStoreClient.postRequest('/auth/pwreset/confirm', body, null, formUrlEncoded)

		then: "it is rejected and the token survives so a valid retry still works"
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.errorStream.text.contains('at least 8 characters')
		def retryBody = 'confirm=' + token + '&email=' + username + '&password=freshPass4567'
		def retryConn = EntryStoreClient.postRequest('/auth/pwreset/confirm', retryBody, null, formUrlEncoded)
		retryConn.getResponseCode() == HTTP_OK
	}
}
