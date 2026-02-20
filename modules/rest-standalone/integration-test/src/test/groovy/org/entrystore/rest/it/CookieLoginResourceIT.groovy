package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil
import spock.lang.PendingFeature

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class CookieLoginResourceIT extends BaseSpec {

	static def password = 'newPass12345'
	static def genericCredsClone = [:]

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		greenMail.start()
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForLogin@test.com', password)
		EntryStoreClient.creds.put('userForLoginExpired@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookie@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieRepeated@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedUsername@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedUsername2@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedPassword@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedOwnPassword@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedOwnPasswordSameCookie@test.com', password)
		EntryStoreClient.creds.put('userForLoginWithCookieChangedOwnPasswordOldCookie@test.com', password)
		EntryStoreClient.creds.put('userForLoginTemporaryLockout@test.com', password)
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		greenMail.stop()
		EntryStoreClient.creds = genericCredsClone
	}

	def "GET /auth/user without login (no cookie), should return guest user"() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(connection.getInputStream().text)
		jsonResp['id'] == '_guest'
		jsonResp['user'] == 'guest'
		jsonResp['uri'] != null
	}

	def "GET /auth/user with invalid auth_token should respond with Unauthorized 401 "() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: 'auth_token=invalidRandomString'])

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def username = RandomStringUtils.secure().nextAlphabetic(32769)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/cookie should fail when required parameters are missing - username and password"() {
		given:
		def bodyParams = ''

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		// TODO: fix should be Bad Request
		//loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail when required parameters are missing - username"() {
		given:
		def bodyParams = 'auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail when required parameters are missing - password"() {
		given:
		def username = 'anyone@test.com'
		def bodyParams = 'auth_username=' + username

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail if the password is too long"() {
		given:
		def username = 'anyone@test.com'
		def password = RandomStringUtils.secure().nextAlphabetic(2049)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should log in the user"() {
		given:
		def username = 'userForLogin@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_OK
		loginConnection.getHeaderField('Set-Cookie') != null
		loginConnection.getHeaderField('Set-Cookie').contains('auth_token=')
		loginConnection.getContentType().contains('text/html')
		loginConnection.getInputStream().text.contains('Login successful.')
	}

	def "POST /auth/cookie should log in the user with cookie and then do 2 requests with the same cookie"() {
		given:
		def username = 'userForLoginWithCookie@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['user'] == username.toLowerCase()
	}

	def "POST /auth/cookie should log in the user with cookie"() {
		given:
		def username = 'userForLoginWithCookieRepeated@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert cookie.contains('Secure')
		assert !cookie.contains('HttpOnly')
		def sameSitePart = cookie.substring(cookie.indexOf('SameSite=') + 9)
		if (sameSitePart.contains(';')) {
			sameSitePart = sameSitePart.substring(0, sameSitePart.indexOf(';'))
		}
		assert sameSitePart == 'None'
		def maxAgePart = cookie.substring(cookie.indexOf('Max-Age=') + 8)
		if (maxAgePart.contains(';')) {
			maxAgePart = maxAgePart.substring(0, maxAgePart.indexOf(';'))
		}
		assert maxAgePart == '3700'

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['user'] == username.toLowerCase()
	}

	def "POST /auth/cookie with maxAge set, should de-authenticate user after maxAge time"() {
		given:
		def username = 'userForLoginExpired@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password + '&auth_maxage=1'
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')

		when:
		def firstReq = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		firstReq.getResponseCode() == HTTP_OK
		def firstJsonResp = JSON_PARSER.parseText(firstReq.getInputStream().text)
		firstJsonResp['id'] != null
		firstJsonResp['user'] == username.toLowerCase()

		when:
		Thread.sleep(1100)
		def secondReq = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		secondReq.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should log in the user after an admin has changed that user's username"() {
		given:
		def username = 'userForLoginWithCookieChangedUsername@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password + '&auth_maxage=2'
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK

		def newUsername = 'userForLoginWithCookieChangedUsername2@test.com'
		def requestBody = JsonOutput.toJson([
			name: newUsername
		])
		assert EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['user'] == newUsername.toLowerCase()
	}

	def "POST /auth/cookie should not log in the user with existing cookie after an admin has changed that user's password"() {
		given:
		def username = 'userForLoginWithCookieChangedPassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		UserUtil.setUserPassword(resourceUri, newPassword)

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should not log in the user with existing cookie after that user has changed the password through email token"() {
		given:
		def username = 'userForLoginWithCookieChangedOwnPassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		def grecaptcharesponse = 'anything'
		def requestBody = JsonOutput.toJson([
			email             : username,
			password          : newPassword,
			grecaptcharesponse: grecaptcharesponse
		])
		assert EntryStoreClient.postRequest('/auth/pwreset', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[1].getContent()
		def startIndex = messageContent.toString().indexOf('?confirm') + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		assert EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token).getResponseCode() == HTTP_OK

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should log in the user only with 1 existing cookie after that user has changed the password through API with that specific cookie"() {
		given:
		def username = 'userForLoginWithCookieChangedOwnPasswordSameCookie@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : newPassword,
			currentPassword: password
		])
		assert EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, null, null, [Cookie: cookie]).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
	}

	def "POST /auth/cookie should not log in the user with existing cookie1 after that user has changed the password through API with existing cookie2"() {
		given:
		def username = 'userForLoginWithCookieChangedOwnPasswordOldCookie@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, null, 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie1 = loginConnection.getHeaderField('Set-Cookie')
		assert cookie1 != null
		assert cookie1.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie1]).getResponseCode() == HTTP_OK

		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams, null, 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = loginConnection2.getHeaderField('Set-Cookie')
		assert cookie2 != null
		assert cookie2.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie2]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : newPassword,
			currentPassword: password
		])
		assert EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, null, null, [Cookie: cookie2]).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie1])

		then:
		info.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should not log in the blacklisted user"() {
		given:
		def username = 'userForLoginBlacklist@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('text/html')
		loginConnection.getErrorStream().text.contains('Login failed.')
	}

	def "POST /auth/cookie should not log in the blacklisted user and not respond with 'Login failed.' html"() {
		given:
		def username = 'userForLoginBlacklistNoHtml@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def extraHeaders = [Accept: 'application/json']

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded', extraHeaders)

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('text/html')
		!loginConnection.getErrorStream().text.contains('Login failed.')
	}

	def "POST /auth/cookie should not log in the disabled user"() {
		given:
		def username = 'userForLoginDisabled@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		def requestBody = JsonOutput.toJson([
			disabled: true
		])
		assert EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_FORBIDDEN
		loginConnection.getContentType().contains('text/html')
		loginConnection.getErrorStream().text.contains('Login failed. The account is disabled.')
	}

	@PendingFeature
	def "POST /auth/cookie should temporarily lockout user who entered wrong password too many times"() {
		given:
		def username = 'userForLoginTemporaryLockout@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK
		bodyParams = 'auth_username=' + username + '&auth_password=badPass123'
		// 3 attempts with bad password
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		// 4th login attempt does the lockout
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == 429
		loginConnection.getContentType().contains('text/html')
		loginConnection.getErrorStream().text.contains('User account is temporarily disabled. Too many failed logins.')

		when:
		// wait for the temporary lockout period to pass
		Thread.sleep(300)
		def login2Connection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		login2Connection.getResponseCode() == HTTP_OK
	}

}
