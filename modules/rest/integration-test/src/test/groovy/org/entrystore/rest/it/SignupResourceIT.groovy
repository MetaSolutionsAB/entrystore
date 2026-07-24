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
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import jakarta.mail.internet.InternetAddress

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class SignupResourceIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def grecaptcharesponse = 'anything'
	static def firstName = 'First'
	static def lastName = 'Last'

	static GreenMail greenMail = new GreenMail(SMTP)

	private static String signupBody(String email, Map overrides = [:]) {
		JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			email             : email,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		] + overrides)
	}

	def setupSpec() {
		EntryStoreClient.snapshotCreds()
		EntryStoreClient.creds.put('userSignupNoConfirm@test.com', newPassword)
		EntryStoreClient.creds.put('userSignupCustomPropsConfirm@test.com', newPassword)
		EntryStoreClient.creds.put('userSignupCustomPropsFormConfirm@test.com', newPassword)
		greenMail.start()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		EntryStoreClient.restoreCreds()
		greenMail.stop()
	}

	// TODO: should we not allow to signup if you are logged in, or if you are a non-admin user?

	def "POST /auth/signup should fail if the data sent to server is said to be JSON but is not JSON"() {
		given:
		def requestBody = 'foo'

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/signup should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def username = RandomStringUtils.secure().nextAlphabetic(32769)
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/signup should send an email with generated token to a new user"() {
		given:
		def username = 'userSignup@test.com'
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_OK
		signupConn.getContentType().contains('text/html')
		signupConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase())
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress('info@meta.se'))
		message.getSubject() == 'User sign-up request'
		message.getAllRecipients().contains(new InternetAddress(username.toLowerCase()))
		def token = extractConfirmationToken(greenMail)
		token ==~ /[a-zA-Z0-9]{16}/
	}

	def "POST /auth/signup should send an email with generated token to a new user when posted as an html form"() {
		given:
		def username = 'userSignupForm@test.com'
		def bodyParams = createFormBody([firstname: firstName, lastname: lastName, email: username, password: newPassword, 'g-recaptcha-response': grecaptcharesponse])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_OK
		signupConn.getContentType().contains('text/html')
		signupConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase())
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress('info@meta.se'))
		message.getSubject() == 'User sign-up request'
		message.getAllRecipients().contains(new InternetAddress(username))
		def token = extractConfirmationToken(greenMail)
		token ==~ /[a-zA-Z0-9]{16}/
	}

	def "POST /auth/signup should not send an email when the password does not meet requirements"() {
		given:
		def username = 'userResetBadPassword@test.com'
		def requestBody = signupBody(username, [password: 'badPass'])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('The password must conform to the configured rules.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email when the firstname does not meet requirements"() {
		given:
		def username = 'userResetBadFirstName@test.com'
		def requestBody = signupBody(username, [firstname: 'http://ab'])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('Invalid name.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email when the lastname does not meet requirements"() {
		given:
		def username = 'userResetBadLastName@test.com'
		def requestBody = signupBody(username, [lastname: 'http://ab'])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('Invalid name.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token to an user with invalid email"() {
		given:
		def username = 'userResetBadEmail@'
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('Invalid email address: ' + username.toLowerCase())
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - email"() {
		given:
		def requestBody = JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - email"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - password"() {
		given:
		def username = 'userResetNoPassword@test.com'
		def requestBody = JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			email             : username,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - password"() {
		given:
		def username = 'userResetNoPasswordForm@test.com'
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&email=' + username + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - grecaptcharesponse"() {
		given:
		def username = 'userResetNoRecaptcha@test.com'
		def requestBody = JsonOutput.toJson([
			firstname: firstName,
			lastname : lastName,
			email    : username,
			password : newPassword,
		])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('reCaptcha information missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - g-captcha-response"() {
		given:
		def username = 'userResetNoRecaptchaForm@test.com'
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&email=' + username + '&password=' + newPassword

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('reCaptcha information missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - firstname"() {
		given:
		def username = 'userResetNoFirstname@test.com'
		def requestBody = JsonOutput.toJson([
			lastname          : lastName,
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - firstname"() {
		given:
		def username = 'userResetNoFirstnameForm@test.com'
		def bodyParams = 'lastname=' + lastName + '&email=' + username + '&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - lastname"() {
		given:
		def username = 'userResetNoLastname@test.com'
		def requestBody = JsonOutput.toJson([
			firstname         : firstName,
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - lastname"() {
		given:
		def username = 'userResetNoLastnameForm@test.com'
		def bodyParams = 'firstname=' + firstName + '&email=' + username + '&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_BAD_REQUEST
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token to a new user outside of whitelisted domains"() {
		given:
		def domain = 'notwhitelisted.com'
		def username = 'userSignup@' + domain
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == 417 // 417 Expectation Failed
		signupConn.getContentType().contains('text/html')
		signupConn.errorStream.text.contains('The email domain is not allowed for sign-up: ' + domain)
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should send an email also if the user already exists"() {
		given:
		def username = 'userSignupExisting@test.com'
		UserUtil.createUser(username)
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_OK
		signupConn.getContentType().contains('text/html')
		signupConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase())
		greenMail.getReceivedMessages().size() == 1
	}

	def "GET /auth/signup should respond with a HTML login page"() {
		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup')

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.inputStream.text.contains('<input type=\"submit\" value=\"Sign-up\" />')
	}

	def "GET /auth/signup should confirm creating new user after signing up with a valid token"() {
		given:
		def username = 'userSignupConfirm@test.com'
		def requestBody = signupBody(username)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CREATED
		confirmConn.getContentType().contains('text/html')
		confirmConn.inputStream.text.contains('Sign-up successful.')
		greenMail.getReceivedMessages().size() == 1
	}

	def "GET /auth/signup should not confirm creating new user after signing up with an invalid token"() {
		given:
		def username = 'userSignupConfirmBadToken@test.com'
		def requestBody = signupBody(username)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = 'something123'

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		def body = confirmConn.errorStream.text
		body.contains('Invalid confirmation link.')
		body.contains('<a href="' + EntryStoreClient.origin + '"')
	}

	def "GET /auth/signup should not confirm creating new user after signing up with already used token"() {
		given:
		def username = 'userSignupConfirmUsedToken@test.com'
		def requestBody = signupBody(username)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)
		assert EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		confirmConn.errorStream.text.contains('Invalid confirmation link.')
	}

	def "GET /auth/signup should not confirm if the user already exists"() {
		given:
		def username = 'userSignupConfirmExisting@test.com'
		UserUtil.createUser(username)

		def requestBody = signupBody(username)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CONFLICT
		confirmConn.getContentType().contains('text/html')
		confirmConn.errorStream.text.contains('User with submitted email address exists already.')
	}

	def "GET /auth/signup should not confirm user signup for another token that was generated before another user signup was successful"() {
		given:
		def username = 'userConfirmOldToken@test.com'
		def requestBody = signupBody(username)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def oldToken = extractConfirmationToken(greenMail)
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def newToken = extractConfirmationToken(greenMail, 1)
		assert EntryStoreClient.getRequest('/auth/signup?confirm=' + newToken).getResponseCode() == HTTP_CREATED

		when:
		def oldConfirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + oldToken)

		then:
		oldConfirmConn.getResponseCode() == HTTP_CONFLICT
		oldConfirmConn.getContentType().contains('text/html')
		oldConfirmConn.errorStream.text.contains('User with submitted email address exists already.')
	}

	def "GET /auth/signup should not remove tokens of another user"() {
		given:
		def username1 = 'user1SignupOldToken@test.com'
		def request1Body = signupBody(username1)
		def username2 = 'user2SignupOldToken@test.com'
		def request2Body = signupBody(username2)

		assert EntryStoreClient.postRequest('/auth/signup', request1Body).getResponseCode() == HTTP_OK
		def user1Token = extractConfirmationToken(greenMail)
		assert EntryStoreClient.postRequest('/auth/signup', request2Body).getResponseCode() == HTTP_OK
		def user2Token = extractConfirmationToken(greenMail, 1)
		assert EntryStoreClient.getRequest('/auth/signup?confirm=' + user1Token).getResponseCode() == HTTP_CREATED

		when:
		def user2ConfirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + user2Token)

		then:
		user2ConfirmConn.getResponseCode() == HTTP_CREATED
		user2ConfirmConn.getContentType().contains('text/html')
		user2ConfirmConn.inputStream.text.contains('Sign-up successful.')
	}

	def "GET /auth/signup should confirm user signup and redirect to provided permitted url"() {
		given:
		def urlSuccess = EntryStoreClient.origin + '/123'
		def username = 'userSignupSuccessUrlPermitted@test.com'
		def requestBody = signupBody(username, [urlsuccess: urlSuccess])
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getHeaderField('Location') == urlSuccess
	}

	def "GET /auth/signup should confirm user signup and not redirect to provided not permitted url"() {
		given:
		def urlSuccess = 'https://example.org/store/blabla/999'
		def username = 'userSignupSuccessUrlNotPermitted@test.com'
		def requestBody = signupBody(username, [urlsuccess: urlSuccess])
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CREATED
		confirmConn.getHeaderField('Location') == null
	}

	def "GET /auth/signup should not confirm user signup and redirect to failure url"() {
		given:
		def username = 'userSignupFailureUrl@test.com'
		UserUtil.createUser(username)
		def urlfailure = EntryStoreClient.origin + '/123'
		def requestBody = signupBody(username, [urlfailure: urlfailure])
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getHeaderField('Location') == urlfailure
	}

	def "POST /auth/signup should confirm creating new user with custom properties and new homecontext after signing up with a valid token"() {
		given:
		def username = 'userSignupCustomPropsConfirm@test.com'
		def requestBody = signupBody(username, [custom_foo: 'foo', custom_boo: 'boo'])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)
		EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		def info = EntryStoreClient.getRequest('/auth/user', username)
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		def entryId = infoRespJson['id']

		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId)
		resourceConn.getResponseCode() == HTTP_OK
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson != null
		resourceRespJson['homecontext'] != null
		resourceRespJson['customProperties']['foo'] == 'foo'
		resourceRespJson['customProperties']['boo'] == 'boo'
		resourceRespJson['name'] == username.toLowerCase()
	}

	def "POST /auth/signup should confirm creating new user with custom properties after signing up with a valid token posted as an html form"() {
		given:
		def username = 'userSignupCustomPropsFormConfirm@test.com'
		def bodyParams = createFormBody([firstname: firstName, lastname: lastName, email: username, password: newPassword, 'g-recaptcha-response': grecaptcharesponse, custom_foo: 'foo', custom_boo: 'boo'])

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		signupConn.getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)
		EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		def info = EntryStoreClient.getRequest('/auth/user', username)
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		def entryId = infoRespJson['id']

		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId)
		resourceConn.getResponseCode() == HTTP_OK
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson != null
		resourceRespJson['customProperties']['foo'] == 'foo'
		resourceRespJson['customProperties']['boo'] == 'boo'
		resourceRespJson['name'] == username.toLowerCase()
	}

	def "POST /auth/signup should not allow to login for the user before confirming the signup"() {
		given:
		def username = 'userSignupNoConfirm@test.com'
		def bodyParams = createFormBody([firstname: firstName, lastname: lastName, email: username, password: newPassword, 'g-recaptcha-response': grecaptcharesponse])

		when: "User signs up"
		assert EntryStoreClient.postRequest('/auth/signup', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK

		then: "User should not be able to login before signup is confirmed"
		def loginBodyParams = createFormBody([auth_username: username, auth_password: newPassword])
		EntryStoreClient.postRequest('/auth/cookie', loginBodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED

		when: "signup is confirmed"
		def token = extractConfirmationToken(greenMail)
		assert EntryStoreClient.getRequest('/auth/signup?confirm=' + token, '').getResponseCode() == HTTP_CREATED

		then: "User should be able to login"
		def info = EntryStoreClient.getRequest('/auth/user', username)
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		infoRespJson['id'] != null
		infoRespJson['user'] == username.toLowerCase()
	}

	def "POST /auth/signup should escape HTML in error messages to prevent injection"() {
		given:
		def maliciousEmail = '<script>alert(1)</script>'
		def requestBody = signupBody(maliciousEmail)

		when:
		def conn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('text/html')
		def body = conn.errorStream.text
		!body.contains('<script>alert(1)</script>')
		body.contains('&lt;script&gt;alert(1)&lt;/script&gt;')
	}

	def "POST /auth/signup should return 503 when the reCaptcha verifier returns an upstream 5xx"() {
		given:
		def failStub = registerRecaptchaFailStub(502)

		def username = 'userSignupVerifierDown@test.com'
		def requestBody = signupBody(username)

		when:
		def signupConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		signupConn.getResponseCode() == HTTP_UNAVAILABLE
		signupConn.getContentType().contains('application/json')
		def body = JSON_PARSER.parseText(signupConn.errorStream.text)
		body['status'] == HTTP_UNAVAILABLE
		body['error'].toLowerCase().contains('unavailable')
		greenMail.getReceivedMessages().size() == 0
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo('/recaptcha/api/siteverify')))

		cleanup:
		wireMockServer.removeStub(failStub)
	}
}
