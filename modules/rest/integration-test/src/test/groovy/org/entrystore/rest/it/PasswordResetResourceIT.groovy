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
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE

class PasswordResetResourceIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def grecaptcharesponse = 'anything'
	static def greenMail = new GreenMail(SMTP)

	def setupSpec() {
		greenMail.start()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		greenMail.stop()
	}

	def "POST /auth/pwreset should fail if the data sent to server is said to be JSON but is not JSON"() {
		given:
		def requestBody = "foo"

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/pwreset should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def username = RandomStringUtils.secure().nextAlphabetic(32769)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/pwreset should send an email with generated token to an existing user"() {
		given:
		def username = 'user@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase() + ', if the user exists.')
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress('info@meta.se'))
		message.getSubject() == 'Password reset request'
		message.getAllRecipients().contains(new InternetAddress(username))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/pwreset should send an email with generated token to an existing user when posted as an html form"() {
		given:
		def username = 'userForm@test.com'
		UserUtil.createUser(username)
		def bodyParams = 'email=' + username + '&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase() + ', if the user exists.')
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress('info@meta.se'))
		message.getSubject() == 'Password reset request'
		message.getAllRecipients().contains(new InternetAddress(username.toLowerCase()))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/pwreset should not send an email to a non-existing user"() {
		given:
		def username = 'userDoesNotExist@test.com'
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.inputStream.text.contains('A confirmation message was sent to ' + username.toLowerCase() + ', if the user exists.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when the password does not meet requirements"() {
		given:
		def username = 'userResetBadPassword@test.com'
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : 'badPass',
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('The password has to consist of at least 8 characters.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token to an user with invalid email"() {
		given:
		def username = 'userResetBadEmail@'
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('Invalid email address: ' + username.toLowerCase())
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - email"() {
		given:
		def requestBody = JsonOutput.toJson([
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters in form are missing - email"() {
		given:
		def bodyParams = 'password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - password"() {
		given:
		def username = 'userResetNoPassword@test.com'
		def requestBody = JsonOutput.toJson([
			email             : username,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters in form are missing - password"() {
		given:
		def username = 'userResetNoPasswordForm@test.com'
		def bodyParams = 'email=' + username + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('One or more parameters are missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - grecaptcharesponse"() {
		given:
		def username = 'userResetNoRecaptcha@test.com'
		def requestBody = JsonOutput.toJson([
			email   : username,
			password: newPassword,
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('reCaptcha information missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters in form are missing - g-captcha-response"() {
		given:
		def username = 'userResetNoRecaptchaForm@test.com'
		def bodyParams = 'email=' + username + '&password=' + newPassword

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.errorStream.text.contains('reCaptcha information missing.')
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token for a disabled user"() {
		given:
		def username = 'userResetDisabled@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		def editRequestBody = JsonOutput.toJson([
			disabled: 'true'
		])
		assert EntryStoreClient.putRequest(resourceUri, editRequestBody).getResponseCode() == HTTP_NO_CONTENT
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(resourceConn2.inputStream.text)['disabled'] == true

		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_FORBIDDEN
		resetPasswordConn.getContentType().contains('application/json')
		def responseBody = resetPasswordConn.errorStream.text
		responseBody.contains('Failed to send confirmation request to ' + username.toLowerCase())
		!responseBody.contains('{}')

		greenMail.getReceivedMessages().size() == 0
	}

	def "GET /auth/pwreset should not confirm password reset without providing a token"() {
		given:

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset')

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.inputStream.text.contains('<input type=\"submit\" value=\"Reset password\" />')
	}

	def "GET /auth/pwreset should confirm password reset for a valid token"() {
		given:
		def username = 'userResetConfirm@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.inputStream.text.contains('Password reset was successful.')
		def messages = greenMail.getReceivedMessages()
		messages.size() == 2
		def message = messages[1]
		message.getFrom().contains(new InternetAddress('info@meta.se'))
		message.getSubject() == "Your password has been changed"
		message.getAllRecipients().contains(new InternetAddress(username.toLowerCase()))
	}

	def "GET /auth/pwreset should not confirm password reset for an invalid token"() {
		given:
		def username = 'userResetInvalidToken@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def token = "something123"

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		confirmConn.errorStream.text.contains('The confirmation token is invalid or has been used already.')
	}

	def "GET /auth/pwreset should not confirm password reset for a non-existing user"() {
		given:
		def username = 'userResetNotExisting@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		assert EntryStoreClient.deleteRequest('/_principals/entry/' + entryId).getResponseCode() == HTTP_NO_CONTENT
		assert EntryStoreClient.getRequest('/_principals/entry/' + entryId).getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_NOT_FOUND
		confirmConn.getContentType().contains('text/html')
		confirmConn.errorStream.text.contains('User with provided email address does not exist.')
	}

	def "GET /auth/pwreset should not confirm password reset for already used token"() {
		given:
		def username = 'userResetAlreadyUsedToken@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		assert EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token).getResponseCode() == HTTP_OK

		when:
		def confirmAgainConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmAgainConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmAgainConn.getContentType().contains('text/html')
		confirmAgainConn.errorStream.text.contains('The confirmation token is invalid or has been used already.')
	}

	def "GET /auth/pwreset should not confirm password reset for another token that was generated before a password change was successful"() {
		given:
		def username = 'userResetOldToken@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def oldMessageContent = greenMail.getReceivedMessages()[0].getContent()
		def oldStartIndex = oldMessageContent.toString().indexOf('?confirm') + 9
		def oldToken = oldMessageContent.toString().substring(oldStartIndex, oldStartIndex + 16)
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def newMessageContent = greenMail.getReceivedMessages()[1].getContent()
		def newStartIndex = newMessageContent.toString().indexOf('?confirm') + 9
		def newToken = newMessageContent.toString().substring(newStartIndex, newStartIndex + 16)
		assert EntryStoreClient.getRequest('/auth/pwreset?confirm=' + newToken).getResponseCode() == HTTP_OK

		when:
		def oldConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + oldToken)

		then:
		oldConfirmConn.getResponseCode() == HTTP_BAD_REQUEST
		oldConfirmConn.getContentType().contains('text/html')
		oldConfirmConn.errorStream.text.contains('The confirmation token is invalid or has been used already.')
	}

	def "GET /auth/pwreset should not remove tokens of another user"() {
		given:
		def username1 = 'user1ResetOldToken@test.com'
		UserUtil.createUser(username1)
		def request1Body = JsonOutput.toJson([
			email             : username1,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		def username2 = 'user2ResetOldToken@test.com'
		UserUtil.createUser(username2)
		def request2Body = JsonOutput.toJson([
			email             : username2,
			password          : 'newPass22345',
			grecaptcharesponse: grecaptcharesponse
		])

		assert EntryStoreClient.postRequest('/auth/pwreset', request1Body).getResponseCode() == HTTP_OK
		def user1MessageContent = greenMail.getReceivedMessages()[0].getContent()
		def user1StartIndex = user1MessageContent.toString().indexOf('?confirm') + 9
		def user1Token = user1MessageContent.toString().substring(user1StartIndex, user1StartIndex + 16)
		assert EntryStoreClient.postRequest('/auth/pwreset', request2Body).getResponseCode() == HTTP_OK
		def user2MessageContent = greenMail.getReceivedMessages()[1].getContent()
		def user2StartIndex = user2MessageContent.toString().indexOf('?confirm') + 9
		def user2Token = user2MessageContent.toString().substring(user2StartIndex, user2StartIndex + 16)
		assert EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user1Token).getResponseCode() == HTTP_OK

		when:
		def user2ConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user2Token)

		then:
		user2ConfirmConn.getResponseCode() == HTTP_OK
		user2ConfirmConn.getContentType().contains('text/html')
		user2ConfirmConn.inputStream.text.contains('Password reset was successful.')
	}

	def "GET /auth/pwreset should confirm password reset and redirect to provided permitted url"() {
		given:
		def username = 'userResetSuccessUrlPermitted@test.com'
		UserUtil.createUser(username)
		def urlSuccess = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			urlsuccess        : urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getHeaderField('Location') == urlSuccess
	}

	def "GET /auth/pwreset should confirm password reset and not redirect to provided not permitted url"() {
		given:
		def username = 'userResetSuccessUrlNotPermitted@test.com'
		UserUtil.createUser(username)
		def urlSuccess = "https://example.org/store/blabla/999"
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			urlsuccess        : urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getURL().toString() == 'http://localhost:8181/auth/pwreset?confirm=' + token
	}

	def "GET /auth/pwreset should not confirm password reset for a non-existing user and redirect to failure url"() {
		given:
		def username = 'userResetNotExistingFailureUrl@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId']
		def urlfailure = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email             : 'userResetNotExistingFailureUrl@test.com',
			password          : newPassword,
			urlfailure        : urlfailure,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		assert EntryStoreClient.deleteRequest('/_principals/entry/' + entryId).getResponseCode() == HTTP_NO_CONTENT
		assert EntryStoreClient.getRequest('/_principals/entry/' + entryId).getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getHeaderField('Location') == urlfailure
	}

	def "POST /auth/pwreset should escape HTML in error messages to prevent injection"() {
		given:
		def maliciousEmail = '<script>alert(1)</script>'
		def requestBody = JsonOutput.toJson([
			email             : maliciousEmail,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def conn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('text/html')
		def body = conn.errorStream.text
		!body.contains('<script>alert(1)</script>')
		body.contains('&lt;script&gt;alert(1)&lt;/script&gt;')
	}

	def "POST /auth/pwreset should return 503 when the reCaptcha verifier returns an upstream 5xx"() {
		given:
		def failStub = registerRecaptchaFailStub(502)

		def username = 'userPwResetVerifierDown@test.com'
		UserUtil.createUser(username)
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_UNAVAILABLE
		resetPasswordConn.getContentType().contains('application/json')
		def body = JSON_PARSER.parseText(resetPasswordConn.errorStream.text)
		body['status'] == HTTP_UNAVAILABLE
		body['error'].toLowerCase().contains('unavailable')
		greenMail.getReceivedMessages().size() == 0
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo('/recaptcha/api/siteverify')))

		cleanup:
		wireMockServer.removeStub(failStub)
	}
}
