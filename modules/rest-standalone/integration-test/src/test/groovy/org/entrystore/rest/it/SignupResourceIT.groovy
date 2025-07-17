package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient

import javax.mail.internet.InternetAddress

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.*

class SignupResourceIT extends BaseSpec {

	def newPassword = 'newPass12345'
	def grecaptcharesponse = 'anything'
	def firstName = 'First'
	def lastName = 'Last'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setup() { greenMail.start() }

	def cleanup() {
		greenMail.stop()
	}

	def "POST /auth/signup should fail if the data sent to server is said to be JSON but is not JSON"() {
		given:
		def requestBody = "foo"

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/signup should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def longString = RandomStringUtils.secure().nextAlphabetic(32769)
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : longString,
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/signup should send an email with generated token to a new user"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignup@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to usersignup@test.com.")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress("info@meta.se"))
		message.getSubject() == "User sign-up request"
		message.getAllRecipients().contains(new InternetAddress("usersignup@test.com"))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/signup should send an email with generated token to a new user when posted as an html form"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&email=userSignupForm@test.com&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to usersignupform@test.com.")
		def messages = greenMail.getReceivedMessages()
		messages.size() == 1
		def message = messages[0]
		message.getFrom().contains(new InternetAddress("info@meta.se"))
		message.getSubject() == "User sign-up request"
		message.getAllRecipients().contains(new InternetAddress("usersignupform@test.com"))
		def messageContent = message.getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		token.length() == 16
	}

	def "POST /auth/signup should not send an email when the password does not meet requirements"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userResetBadPassword@test.com',
				password          : 'badPass',
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("The password must conform to the configured rules.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email when the firstname does not meet requirements"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : "http://ab",
				lastname          : lastName,
				email             : 'userResetBadFirstName@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("Invalid name.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email when the lastname does not meet requirements"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : "http://ab",
				email             : 'userResetBadLastName@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("Invalid name.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token to an user with invalid email"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userResetBadEmail@',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("Invalid email address: userresetbademail@.")
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
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - email"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - password"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userResetNoPassword@test.com',
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - password"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&email=userResetNoPasswordForm@test.com&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - grecaptcharesponse"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname: firstName,
				lastname : lastName,
				email    : 'userResetNoRecaptcha@test.com',
				password : newPassword,
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("reCaptcha information missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - g-captcha-response"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&lastname=' + lastName + '&email=userResetNoRecaptchaForm@test.com&password=' + newPassword

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("reCaptcha information missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - firstname"() {
		given:
		def requestBody = JsonOutput.toJson([
				lastname          : lastName,
				email             : 'userResetNoFirstname@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - firstname"() {
		given:
		def bodyParams = 'lastname=' + lastName + '&email=userResetNoFirstname@test.com&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters are missing - lastname"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				email             : 'userResetNoLastname@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token when required parameters in form are missing - lastname"() {
		given:
		def bodyParams = 'firstname=' + firstName + '&email=userResetNoLastname@test.com&password=' + newPassword + '&g-recaptcha-response=' + grecaptcharesponse

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', bodyParams, null, 'application/x-www-form-urlencoded')

		then:
		resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("One or more parameters are missing.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should not send an email with generated token to a new user outside of whitelisted domains"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignup@notwhitelisted.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == 417 // Status.CLIENT_ERROR_EXPECTATION_FAILED
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getErrorStream().text.contains("The email domain is not allowed for sign-up: notwhitelisted.com.")
		greenMail.getReceivedMessages().size() == 0
	}

	def "POST /auth/signup should send an email also if the user already exists"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userSignupExisting@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK

		def requestBody = JsonOutput.toJson([
			firstname         : firstName,
			lastname          : lastName,
			email             : 'userSignupExisting@test.com',
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])

		when:
		def resetPasswordConn = EntryStoreClient.postRequest('/auth/signup', requestBody)

		then:
		resetPasswordConn.getResponseCode() == HTTP_OK
		resetPasswordConn.getContentType().contains('text/html')
		resetPasswordConn.getInputStream().text.contains("A confirmation message was sent to usersignupexisting@test.com.")
		greenMail.getReceivedMessages().size() == 1
	}

	def "GET /auth/signup should confirm creating new user after signing up with a valid token"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignupConfirm@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CREATED
		confirmConn.getContentType().contains('text/html')
		confirmConn.getInputStream().text.contains("Sign-up successful.")
		greenMail.getReceivedMessages().size() == 1
	}

	def "GET /auth/signup should not confirm creating new user after signing up with an invalid token"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignupConfirmBadToken@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def token = "something123"

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		confirmConn.getErrorStream().text.contains("Invalid confirmation link.")
	}

	def "GET /auth/signup should not confirm creating new user after signing up with already used token"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignupConfirmUsedToken@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_BAD_REQUEST
		confirmConn.getContentType().contains('text/html')
		confirmConn.getErrorStream().text.contains("Invalid confirmation link.")
	}

	def "GET /auth/signup should not confirm if the user already exists"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userSignupConfirmExisting@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody).getResponseCode() == HTTP_OK

		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email             : 'userSignupConfirmExisting@test.com',
				password          : newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CONFLICT
		confirmConn.getContentType().contains('text/html')
		confirmConn.getErrorStream().text.contains("User with submitted email address exists already.")
	}

	def "GET /auth/signup should not confirm user signup for another token that was generated before another user signup was successful"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email   : 'userConfirmOldToken@test.com',
				password: newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def oldMessageContent = greenMail.getReceivedMessages()[0].getContent()
		def oldStartIndex = oldMessageContent.toString().indexOf("?confirm") + 9
		def oldToken = oldMessageContent.toString().substring(oldStartIndex, oldStartIndex + 16)
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def newMessageContent = greenMail.getReceivedMessages()[1].getContent()
		def newStartIndex = newMessageContent.toString().indexOf("?confirm") + 9
		def newToken = newMessageContent.toString().substring(newStartIndex, newStartIndex + 16)
		EntryStoreClient.getRequest('/auth/signup?confirm=' + newToken).getResponseCode() == HTTP_OK

		when:
		def oldConfirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + oldToken)

		then:
		oldConfirmConn.getResponseCode() == HTTP_CONFLICT
		oldConfirmConn.getContentType().contains('text/html')
		oldConfirmConn.getErrorStream().text.contains("User with submitted email address exists already.")
	}

	def "GET /auth/signup should not remove tokens of another user"() {
		given:
		def request1Body = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email   : 'user1SignupOldToken@test.com',
				password: newPassword,
				grecaptcharesponse: grecaptcharesponse
		])
		// create user2
		def request2Body = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email   : 'user2SignupOldToken@test.com',
				password: 'newPass22345',
				grecaptcharesponse: grecaptcharesponse
		])

		EntryStoreClient.postRequest('/auth/signup', request1Body).getResponseCode() == HTTP_OK
		def user1MessageContent = greenMail.getReceivedMessages()[0].getContent()
		def user1StartIndex = user1MessageContent.toString().indexOf("?confirm") + 9
		def user1Token = user1MessageContent.toString().substring(user1StartIndex, user1StartIndex + 16)

		EntryStoreClient.postRequest('/auth/signup', request2Body).getResponseCode() == HTTP_OK
		def user2MessageContent = greenMail.getReceivedMessages()[1].getContent()
		def user2StartIndex = user2MessageContent.toString().indexOf("?confirm") + 9
		def user2Token = user2MessageContent.toString().substring(user2StartIndex, user2StartIndex + 16)

		EntryStoreClient.getRequest('/auth/signup?confirm=' + user1Token).getResponseCode() == HTTP_OK

		when:
		def user2ConfirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + user2Token)

		then:
		user2ConfirmConn.getResponseCode() == HTTP_CREATED
		user2ConfirmConn.getContentType().contains('text/html')
		user2ConfirmConn.getInputStream().text.contains("Sign-up successful.")
	}

	def "GET /auth/signup should confirm user signup and redirect to provided permitted url"() {
		given:
		// create user
		def urlSuccess = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email     : 'userSignupSuccessUrlPermitted@test.com',
				password  : newPassword,
				urlsuccess: urlSuccess,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getHeaderField("Location") == null
		confirmConn.getURL().toString() == urlSuccess
	}

	def "GET /auth/signup should confirm user signup and not redirect to provided not permitted url"() {
		given:
		def urlSuccess = "http://example.org/store/blabla/999"
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email     : 'userSignupSuccessUrlNotPermitted@test.com',
				password  : newPassword,
				urlsuccess: urlSuccess,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CREATED
		confirmConn.getURL().toString() == 'http://localhost:8181/auth/signup?confirm=' + token
	}

	/* not sure how to invoke 500 server error
	def "GET /auth/signup should not confirm user signup and redirect to failure url"() {
		given:
		def urlfailure = "http://localhost:8181/123"
		def requestBody = JsonOutput.toJson([
				firstname         : firstName,
				lastname          : lastName,
				email   : 'userSignupFailureUrl@test.com',
				password: newPassword,
				urlfailure: urlfailure,
				grecaptcharesponse: grecaptcharesponse
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getHeaderField("Location") == null
		confirmConn.getURL().toString() == urlfailure
	} */

}
