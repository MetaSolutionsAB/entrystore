package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient

import javax.mail.internet.InternetAddress

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class PasswordResetResourceIT extends BaseSpec {

	def newPassword = 'newPass12345'
	def grecaptcharesponse = 'anything'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setup() { greenMail.start() }

	def cleanup() {
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
		def longString = RandomStringUtils.secure().nextAlphabetic(32769)
		def requestBody = JsonOutput.toJson([
			email             : longString,
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
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'user@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def requestBody = JsonOutput.toJson([
			email             : 'user@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to user@test.com, if the user exists.")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress("info@meta.se"))
		message.getSubject() == "Password reset request"
		message.getAllRecipients().contains(new InternetAddress("user@test.com"))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/pwreset should send an email with generated token to an existing user when posted as an html form"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForm@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def bodyParams = 'email=userForm@test.com&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to userform@test.com, if the user exists.")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress("info@meta.se"))
		message.getSubject() == "Password reset request"
		message.getAllRecipients().contains(new InternetAddress("userform@test.com"))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/pwreset should not send an email to a non-existing user"() {
		given:
		def requestBody = JsonOutput.toJson([
			email             : 'userDoesNotExist@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to userdoesnotexist@test.com, if the user exists.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when the password does not meet requirements"() {
		given:
		def requestBody = JsonOutput.toJson([
			email             : 'userResetBadPassword@test.com',
			password          : 'badPass',
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("The password has to consist of at least 8 characters.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token to an user with invalid email"() {
		given:
		def requestBody = JsonOutput.toJson([
			email             : 'userResetBadEmail@',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("Invalid email address: userresetbademail@.")
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
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
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
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - password"() {
		given:
		def requestBody = JsonOutput.toJson([
			email             : 'userResetNoPassword@test.com',
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters in form are missing - password"() {
		given:
		def bodyParams = 'email=userResetNoPasswordForm@test.com&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - grecaptcharesponse"() {
		given:
		def requestBody = JsonOutput.toJson([
			email   : 'userResetNoRecaptcha@test.com',
			password: newPassword,
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("reCaptcha information missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters in form are missing - g-captcha-response"() {
		given:
		def bodyParams = 'email=userResetNoRecaptchaForm@test.com&password=' + newPassword

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("reCaptcha information missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token for a disabled user"() {
		given:
		// create a user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetDisabled@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def editRequestBody = JsonOutput.toJson([
			disabled          : 'true'
		])
		EntryStoreClient.putRequest(resourceUri, editRequestBody).getResponseCode() == HTTP_NO_CONTENT
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(resourceConn2.getInputStream().text)['disabled'] == true

		def requestBody = JsonOutput.toJson([
			email             : 'userResetDisabled@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_FORBIDDEN
		greenMail.getReceivedMessages().size() == 0
	}

	def "GET /store/auth/pwreset should not confirm password reset without providing a token"() {
		given:

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset')

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.getInputStream().text.contains("<input type=\"submit\" value=\"Reset password\" />")
	}

	def "GET /store/auth/pwreset should confirm password reset for a valid token"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetConfirm@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def requestBody = JsonOutput.toJson([
			email             : 'userResetConfirm@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.getInputStream().text.contains("Password reset was successful.")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 2
		def message = messages[1]
		message.getFrom().contains(new InternetAddress("info@meta.se"))
		message.getSubject() == "Your password has been changed"
		message.getAllRecipients().contains(new InternetAddress("userresetconfirm@test.com"))
	}

	def "GET /store/auth/pwreset should not confirm password reset for an invalid token"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetInvalidToken@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def requestBody = JsonOutput.toJson([
			email   : 'userResetInvalidToken@test.com',
			password: newPassword
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def token = "something123"

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		confirmConn.getErrorStream().text.contains("The confirmation token is invalid or has been used already.")
	}

	def "GET /store/auth/pwreset should not confirm password reset for a non-existing user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNotExisting@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		userConnection.getResponseCode() == HTTP_OK
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
		def requestBody = JsonOutput.toJson([
			email   : 'userResetNotExisting@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		EntryStoreClient.deleteRequest('/_principals/entry/' + userEntryId).getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/_principals/entry/' + userEntryId).getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_NOT_FOUND
		confirmConn.getContentType().contains('text/html')
		confirmConn.getErrorStream().text.contains("User with provided email address does not exist.")
	}

	def "GET /store/auth/pwreset should not confirm password reset for already used token"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetAlreadyUsedToken@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def requestBody = JsonOutput.toJson([
			email   : 'userResetAlreadyUsedToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token).getResponseCode() == HTTP_OK

		when:
		def confirmAgainConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmAgainConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmAgainConn.getContentType().contains('text/html')
		confirmAgainConn.getErrorStream().text.contains("The confirmation token is invalid or has been used already.")
	}

	/* Mockito will not set Instant.now() across threads, will write Unit tests
	def "GET /store/auth/pwreset should not confirm password reset for an expired token"() {

		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetExpiredToken@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def body = JsonOutput.toJson([
			password: oldPassword
		])
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
		def requestBody = JsonOutput.toJson([
			email   : 'userResetExpiredToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		System.setProperty("mockito.now", "")

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
	}
	*/

	def "GET /store/auth/pwreset should not confirm password reset for another token that was generated before a password change was successful"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetOldToken@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def requestBody = JsonOutput.toJson([
			email   : 'userResetOldToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def oldMessageContent = greenMail.getReceivedMessages()[0].getContent()
		def oldStartIndex = oldMessageContent.toString().indexOf("?confirm") + 9
		def oldToken = oldMessageContent.toString().substring(oldStartIndex, oldStartIndex + 16)
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def newMessageContent = greenMail.getReceivedMessages()[1].getContent()
		def newStartIndex = newMessageContent.toString().indexOf("?confirm") + 9
		def newToken = newMessageContent.toString().substring(newStartIndex, newStartIndex + 16)
		EntryStoreClient.getRequest('/auth/pwreset?confirm=' + newToken).getResponseCode() == HTTP_OK

		when:
		def oldConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + oldToken)

		then:
		oldConfirmConn.getResponseCode() == HTTP_BAD_REQUEST
		oldConfirmConn.getContentType().contains('text/html')
		oldConfirmConn.getErrorStream().text.contains("The confirmation token is invalid or has been used already.")
	}

	def "GET /store/auth/pwreset should not remove tokens of another user"() {
		given:
		// create user1
		def user1Params = [graphtype: 'user']
		def user1RequestResourceName = [name: 'user1ResetOldToken@test.com']
		def user1Body = JsonOutput.toJson([resource: user1RequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(user1Params), user1Body).getResponseCode() == HTTP_OK
		def request1Body = JsonOutput.toJson([
			email   : 'user1ResetOldToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		// create user2
		def user2Params = [graphtype: 'user']
		def user2RequestResourceName = [name: 'user2ResetOldToken@test.com']
		def user2Body = JsonOutput.toJson([resource: user2RequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(user2Params), user2Body).getResponseCode() == HTTP_OK
		def request2Body = JsonOutput.toJson([
			email   : 'user2ResetOldToken@test.com',
			password: 'newPass22345',
			grecaptcharesponse: grecaptcharesponse
		])

		EntryStoreClient.postRequest('/auth/pwreset', request1Body).getResponseCode() == HTTP_OK
		def user1MessageContent = greenMail.getReceivedMessages()[0].getContent()
		def user1StartIndex = user1MessageContent.toString().indexOf("?confirm") + 9
		def user1Token = user1MessageContent.toString().substring(user1StartIndex, user1StartIndex + 16)

		EntryStoreClient.postRequest('/auth/pwreset', request2Body).getResponseCode() == HTTP_OK
		def user2MessageContent = greenMail.getReceivedMessages()[1].getContent()
		def user2StartIndex = user2MessageContent.toString().indexOf("?confirm") + 9
		def user2Token = user2MessageContent.toString().substring(user2StartIndex, user2StartIndex + 16)

		EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user1Token).getResponseCode() == HTTP_OK

		when:
		def user2ConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user2Token)

		then:
		user2ConfirmConn.getResponseCode() == HTTP_OK
		user2ConfirmConn.getContentType().contains('text/html')
		user2ConfirmConn.getInputStream().text.contains("Password reset was successful.")
	}

	def "GET /store/auth/pwreset should confirm password reset and redirect to provided permitted url"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetSuccessUrlPermitted@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def urlSuccess = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email     : 'userResetSuccessUrlPermitted@test.com',
			password  : newPassword,
			urlsuccess: urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getHeaderField("Location") == null
		confirmConn.getURL().toString() == urlSuccess
	}

	def "GET /store/auth/pwreset should confirm password reset and not redirect to provided not permitted url"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetSuccessUrlNotPermitted@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK
		def urlSuccess = "http://example.org/store/blabla/999"
		def requestBody = JsonOutput.toJson([
			email     : 'userResetSuccessUrlNotPermitted@test.com',
			password  : newPassword,
			urlsuccess: urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getURL().toString() == 'http://localhost:8181/auth/pwreset?confirm=' + token
	}

	def "GET /store/auth/pwreset should not confirm password reset for a non-existing user and redirect to failure url"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNotExistingFailureUrl@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
		def urlfailure = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email   : 'userResetNotExistingFailureUrl@test.com',
			password: newPassword,
			urlfailure: urlfailure,
			grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		EntryStoreClient.deleteRequest('/_principals/entry/' + userEntryId).getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/_principals/entry/' + userEntryId).getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getHeaderField("Location") == null
		confirmConn.getURL().toString() == urlfailure
	}
}
