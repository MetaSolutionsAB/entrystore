package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient

import javax.mail.internet.InternetAddress

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class PasswordResetResourceIT extends BaseSpec {

	def oldPassword = 'oldPass1234'
	def newPassword = 'newPass12345'
	def grecaptcharesponse = 'anything'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setup() { greenMail.start() }

	def cleanup() {
		greenMail.stop()
	}

	def "POST /auth/pwreset should send an email with generated token to an existing user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'user@test.com']
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
			email             : 'user@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
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

	def "POST /auth/pwreset should not send an email with generated token to a user who has no password set yet"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userHasNoPassword@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			email             : 'userHasNoPassword@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
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
		resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when the password does not meet requirements"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetBadPassword@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			email             : 'userResetBadPassword@test.com',
			password          : 'badPass',
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token to an user with invalid email"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetBadEmail@']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			email             : 'userResetBadEmail@',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - email"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNoEmail@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - password"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNoPassword@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			email             : 'userResetNoPassword@test.com',
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
	}

	def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - grecaptcharesponse"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNoRecaptcha@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def requestBody = JsonOutput.toJson([
			email   : 'userResetNoRecaptcha@test.com',
			password: newPassword,
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		def messages = greenMail.getReceivedMessages()
		messages.size() == 0
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
			password          : 'newPass1234',
			disabled          : 'true'
		])
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, editRequestBody)
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(resourceConn2.getInputStream().text)['disabled'] == true
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getSubject() == "Your password has been changed"

		def requestBody = JsonOutput.toJson([
			email             : 'userResetDisabled@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_FORBIDDEN

		def newMessages = greenMail.getReceivedMessages()
		messages.size() == 1
	}

	def "GET /store/auth/pwreset should confirm password reset for a valid token"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetConfirm@test.com']
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
			email             : 'userResetConfirm@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK // needed for the required timeout to receive the email I guess
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_OK
		confirmConn.getContentType().contains('text/html')
		confirmConn.getInputStream().text.contains("<title>Password reset</title>")
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
			email   : 'userResetInvalidToken@test.com',
			password: newPassword
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		def token = "something123"

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /store/auth/pwreset should not confirm password reset for a non-existing user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetNotExisting@test.com']
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
			email   : 'userResetNotExisting@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/' + userEntryId)
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def deletedEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
		deletedEntryConn.getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /store/auth/pwreset should not confirm password reset for already used token"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetAlreadyUsedToken@test.com']
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
			email   : 'userResetAlreadyUsedToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)
		confirmConn.getResponseCode() == HTTP_OK

		when:
		def confirmAgainConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmAgainConn.getResponseCode() == HTTP_BAD_REQUEST
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
			email   : 'userResetOldToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		def oldResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		oldResetPasswordConn.getResponseCode() == HTTP_OK
		def oldMessageContent = greenMail.getReceivedMessages()[0].getContent()
		def oldStartIndex = oldMessageContent.toString().indexOf("?confirm") + 9
		def oldToken = oldMessageContent.toString().substring(oldStartIndex, oldStartIndex + 16)
		def newResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		newResetPasswordConn.getResponseCode() == HTTP_OK
		def newMessageContent = greenMail.getReceivedMessages()[1].getContent()
		def newStartIndex = newMessageContent.toString().indexOf("?confirm") + 9
		def newToken = newMessageContent.toString().substring(newStartIndex, newStartIndex + 16)
		def newConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + newToken)
		newConfirmConn.getResponseCode() == HTTP_OK

		when:
		def oldConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + oldToken)

		then:
		oldConfirmConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /store/auth/pwreset should not remove tokens of another user"() {
		given:
		// create user1
		def user1Params = [graphtype: 'user']
		def user1RequestResourceName = [name: 'user1ResetOldToken@test.com']
		def user1Body = JsonOutput.toJson([resource: user1RequestResourceName])
		def user1Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(user1Params), user1Body)
		def user1EntryId = JSON_PARSER.parseText(user1Connection.getInputStream().text)['entryId'].toString()
		def entry1Conn = EntryStoreClient.getRequest('/_principals/entry/' + user1EntryId)
		def entry1RespJson = JSON_PARSER.parseText(entry1Conn.getInputStream().text)
		def entry1RespJsonKeys = (entry1RespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resource1Uri = entry1RespJsonKeys.find { it -> it.contains('resource') }
		def body1 = JsonOutput.toJson([
			password: oldPassword
		])
		def editResource1Conn = EntryStoreClient.putRequest(resource1Uri, body1)
		def request1Body = JsonOutput.toJson([
			email   : 'user1ResetOldToken@test.com',
			password: newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		// create user2
		def user2Params = [graphtype: 'user']
		def user2RequestResourceName = [name: 'user2ResetOldToken@test.com']
		def user2Body = JsonOutput.toJson([resource: user2RequestResourceName])
		def user2Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(user2Params), user2Body)
		def user2EntryId = JSON_PARSER.parseText(user2Connection.getInputStream().text)['entryId'].toString()
		def entry2Conn = EntryStoreClient.getRequest('/_principals/entry/' + user2EntryId)
		def entry2RespJson = JSON_PARSER.parseText(entry2Conn.getInputStream().text)
		def entry2RespJsonKeys = (entry2RespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resource2Uri = entry2RespJsonKeys.find { it -> it.contains('resource') }
		def body2 = JsonOutput.toJson([
			password: 'oldPass223'
		])
		def editResource2Conn = EntryStoreClient.putRequest(resource2Uri, body2)
		def request2Body = JsonOutput.toJson([
			email   : 'user2ResetOldToken@test.com',
			password: 'newPass22345',
			grecaptcharesponse: grecaptcharesponse
		])

		def user1ResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', request1Body)
		user1ResetPasswordConn.getResponseCode() == HTTP_OK
		def user1MessageContent = greenMail.getReceivedMessages()[0].getContent()
		def user1StartIndex = user1MessageContent.toString().indexOf("?confirm") + 9
		def user1Token = user1MessageContent.toString().substring(user1StartIndex, user1StartIndex + 16)

		def user2ResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', request2Body)
		user2ResetPasswordConn.getResponseCode() == HTTP_OK
		def user2MessageContent = greenMail.getReceivedMessages()[1].getContent()
		def user2StartIndex = user2MessageContent.toString().indexOf("?confirm") + 9
		def user2Token = user2MessageContent.toString().substring(user2StartIndex, user2StartIndex + 16)

		def user1ConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user1Token)
		user1ConfirmConn.getResponseCode() == HTTP_OK

		when:
		def user2ConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + user2Token)

		then:
		user2ConfirmConn.getResponseCode() == HTTP_OK
	}

	def "GET /store/auth/pwreset should confirm password reset and redirect to provided permitted url"() {

		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userResetSuccessUrlPermitted@test.com']
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
		def urlSuccess = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email     : 'userResetSuccessUrlPermitted@test.com',
			password  : newPassword,
			urlsuccess: urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
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
		def urlSuccess = "http://example.org/store/blabla/999"
		def requestBody = JsonOutput.toJson([
			email     : 'userResetSuccessUrlNotPermitted@test.com',
			password  : newPassword,
			urlsuccess: urlSuccess,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
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
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def body = JsonOutput.toJson([
			password: oldPassword
		])
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
		def urlfailure = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
			email   : 'userResetNotExistingFailureUrl@test.com',
			password: newPassword,
			urlfailure: urlfailure,
			grecaptcharesponse: grecaptcharesponse
		])
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
		resetPasswordConn.getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/' + userEntryId)
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def deletedEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
		deletedEntryConn.getResponseCode() == HTTP_NOT_FOUND

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then:
		confirmConn.getHeaderField("Location") == null
		confirmConn.getURL().toString() == urlfailure
	}
}
