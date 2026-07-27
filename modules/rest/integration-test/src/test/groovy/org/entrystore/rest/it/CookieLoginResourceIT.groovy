package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import java.time.Instant

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class CookieLoginResourceIT extends BaseSpec {

	static def password = 'newPass12345'

	static GreenMail greenMail = new GreenMail(SMTP)

	def setupSpec() {
		greenMail.start()
		EntryStoreClient.snapshotCreds()
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
		EntryStoreClient.creds.put('userForDisabledUntilOnEntry@test.com', password)
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		EntryStoreClient.restoreCreds()
		greenMail.stop()
	}

	def "GET /auth/user without login (no cookie), should return guest user"() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def jsonResp = JSON_PARSER.parseText(connection.inputStream.getText())
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
		def bodyParams = createFormBody([auth_username: username, auth_password: password])

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
		def bodyParams = createFormBody([auth_username: username, auth_password: password])

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should log in the user"() {
		given:
		def username = 'userForLogin@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_OK
		EntryStoreClient.findSetCookie(loginConnection, 'auth_token') != null
		loginConnection.getContentType().contains('text/html')
		loginConnection.inputStream.text.contains('Login successful.')
	}

	def "POST /auth/cookie should log in the user with cookie and then do 2 requests with the same cookie"() {
		given:
		def username = 'userForLoginWithCookie@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		infoRespJson['user'] == username.toLowerCase()
	}

	def "POST /auth/cookie should log in the user with cookie"() {
		given:
		def username = 'userForLoginWithCookieRepeated@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
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
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		infoRespJson['user'] == username.toLowerCase()
	}

	def "POST /auth/cookie with maxAge set, should de-authenticate user after maxAge time"() {
		given:
		def username = 'userForLoginExpired@test.com'
		UserUtil.createUserWithPassword(username, password)
		// 1s maxage: the first request below must land inside that window, the second after it
		def bodyParams = createFormBody([auth_username: username, auth_password: password, auth_maxage: '1'])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
		assert cookie != null
		assert cookie.contains('auth_token=')

		when:
		def firstReq = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: cookie])

		then:
		firstReq.getResponseCode() == HTTP_OK
		def firstJsonResp = JSON_PARSER.parseText(firstReq.inputStream.text)
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
		def user = UserUtil.createUserWithPassword(username, password)
		def resourceUri = user['resourceUri'].toString()
		def bodyParams = createFormBody([auth_username: username, auth_password: password, auth_maxage: '2'])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
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
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		infoRespJson['user'] == newUsername.toLowerCase()
	}

	def "POST /auth/cookie should not log in the user with existing cookie after an admin has changed that user's password"() {
		given:
		def username = 'userForLoginWithCookieChangedPassword@test.com'
		def user = UserUtil.createUserWithPassword(username, password)
		def resourceUri = user['resourceUri'].toString()
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
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
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
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
		// pwReset dispatches SMTP send + bcrypt asynchronously to avoid leaking account existence via
		// response timing; wait for the email to arrive before extracting the confirmation token.
		assert greenMail.waitForIncomingEmail(5000, 2)
		def token = extractConfirmationToken(greenMail, 1)
		assert EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token).getResponseCode() == HTTP_OK

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie should log in the user only with 1 existing cookie after that user has changed the password through API with that specific cookie"() {
		given:
		def username = 'userForLoginWithCookieChangedOwnPasswordSameCookie@test.com'
		def user = UserUtil.createUserWithPassword(username, password)
		def resourceUri = user['resourceUri'].toString()
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
		def csrf = EntryStoreClient.findCookieValue(loginConnection, 'XSRF-TOKEN')
		assert cookie != null
		assert cookie.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : newPassword,
			currentPassword: password
		])
		assert EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, null, null,
				EntryStoreClient.csrfHeaders(cookie, csrf)).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then:
		info.getResponseCode() == HTTP_OK
	}

	def "POST /auth/cookie should not log in the user with existing cookie1 after that user has changed the password through API with existing cookie2"() {
		given:
		def username = 'userForLoginWithCookieChangedOwnPasswordOldCookie@test.com'
		def user = UserUtil.createUserWithPassword(username, password)
		def resourceUri = user['resourceUri'].toString()
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, null, 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie1 = EntryStoreClient.findSetCookie(loginConnection, 'auth_token')
		assert cookie1 != null
		assert cookie1.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie1]).getResponseCode() == HTTP_OK

		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams, null, 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = EntryStoreClient.findSetCookie(loginConnection2, 'auth_token')
		def csrf2 = EntryStoreClient.findCookieValue(loginConnection2, 'XSRF-TOKEN')
		assert cookie2 != null
		assert cookie2.contains('auth_token=')
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie2]).getResponseCode() == HTTP_OK
		def newPassword = 'someNewPassword123'
		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : newPassword,
			currentPassword: password
		])
		assert EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, null, null,
				EntryStoreClient.csrfHeaders(cookie2, csrf2)).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie1])

		then:
		info.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /auth/cookie without Accept header should not log in the blacklisted user and respond with json"() {
		given:
		def username = 'userForLoginBlacklist@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Unauthorized')
	}

	def "POST /auth/cookie with Accept json header should not log in the blacklisted user and respond with json"() {
		given:
		def username = 'userForLoginBlacklistNoHtml@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def extraHeaders = [Accept: 'application/json']

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded', extraHeaders)

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Unauthorized')
	}

	def "POST /auth/cookie with Accept html header should not log in the blacklisted user and respond with json"() {
		given:
		def username = 'userForLoginBlacklistHtml@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def extraHeaders = [Accept: 'text/html']

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded', extraHeaders)

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Unauthorized')
	}

	def "POST /auth/cookie should not log in the disabled user and respond with the same 401 as wrong credentials"() {
		given:
		def username = 'userForLoginDisabled@test.com'
		def user = UserUtil.createUserWithPassword(username, password)
		def resourceUri = user['resourceUri'].toString()
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		def requestBody = JsonOutput.toJson([
			disabled: true
		])
		assert EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Unauthorized')
	}

	def "POST /auth/cookie should temporarily lockout user who entered wrong password too many times"() {
		given:
		def username = 'userForLoginTemporaryLockout@test.com'
		UserUtil.createUserWithPassword(username, password)
		def bodyParams = createFormBody([auth_username: username, auth_password: password])
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK
		bodyParams = createFormBody([auth_username: username, auth_password: 'badPass123'])
		// 3 attempts with bad password
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		bodyParams = createFormBody([auth_username: username, auth_password: password])

		when:
		// 4th login attempt does the lockout
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == 429
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Too many login attempts. Please try again later.')

		when:
		// wait for the temporary lockout period to pass; 2s outlasts the 1s configured duration
		// with the same comfortable slack the new disabledUntil IT below uses
		Thread.sleep(2000)
		def login2Connection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		login2Connection.getResponseCode() == HTTP_OK
	}

	def "POST /auth/cookie should also temporarily lockout nonexistent usernames"() {
		given:
		// Username that is never created — verifies that lockout tracking is not limited to known users,
		// which would otherwise leak account existence via the absence of 429 responses.
		def username = 'unknownUserForLockout@test.com'
		def bodyParams = createFormBody([auth_username: username, auth_password: 'anyBadPass'])
		// 3 attempts before the lockout threshold
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED

		when:
		// 4th attempt trips the lockout
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == 429
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Too many login attempts. Please try again later.')
	}

	def "POST /auth/cookie should temporarily lockout repeated attempts against a blacklisted username"() {
		given:
		// Verifies that the blacklist short-circuit also records failures, so the lockout 429
		// applies to blacklisted usernames too. Otherwise an attacker could count 401s without
		// ever seeing a 429 and conclude the username is blacklisted (enumeration oracle).
		def username = 'userForLoginBlacklistLockout@test.com'
		def bodyParams = createFormBody([auth_username: username, auth_password: 'anyBadPass'])
		// 3 attempts before the lockout threshold — each should return 401 from the blacklist branch
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded').getResponseCode() == HTTP_UNAUTHORIZED

		when:
		// 4th attempt trips the lockout — proves the blacklist branch records failures
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == 429
		loginConnection.getContentType().contains('application/json')
		loginConnection.getErrorStream().text.contains('Too many login attempts. Please try again later.')
	}

	def "POST /auth/cookie wrong-password and disabled-user responses are byte-identical"() {
		given:
		// Pins the normalization invariant against future regressions that might reintroduce
		// a disabled-account discriminator (different status, body, or content type).
		def enabledUsername = 'userForLoginEquality@test.com'
		UserUtil.createUserWithPassword(enabledUsername, password)

		def disabledUsername = 'userForLoginEqualityDisabled@test.com'
		def disabledUser = UserUtil.createUserWithPassword(disabledUsername, password)
		def disabledUri = disabledUser['resourceUri'].toString()
		assert EntryStoreClient.putRequest(disabledUri, JsonOutput.toJson([disabled: true])).getResponseCode() == HTTP_NO_CONTENT

		def wrongPasswordBody = createFormBody([auth_username: enabledUsername, auth_password: 'wrongPass123'])
		def disabledLoginBody = createFormBody([auth_username: disabledUsername, auth_password: password])

		when:
		def wrongPasswordConn = EntryStoreClient.postRequest('/auth/cookie', wrongPasswordBody, '', 'application/x-www-form-urlencoded')
		def disabledConn = EntryStoreClient.postRequest('/auth/cookie', disabledLoginBody, '', 'application/x-www-form-urlencoded')

		then:
		wrongPasswordConn.getResponseCode() == disabledConn.getResponseCode()
		wrongPasswordConn.getContentType() == disabledConn.getContentType()
		// The unified 401 envelope is byte-identical across branches: timestamp is nulled in
		// HttpUtil.writeUnauthorizedAsJson so it cannot leak the bcrypt/no-bcrypt latency
		// difference between branches, and the request URI (path) is the same for both calls.
		wrongPasswordConn.getErrorStream().text == disabledConn.getErrorStream().text
	}

	def "GET /_principals/entry/{id}?includeAll should expose disabledUntil while user is locked out"() {
		given:
		def username = 'userForDisabledUntilOnEntry@test.com'
		def user = UserUtil.createUserWithPassword(username, password)
		def entryId = user['entryId'].toString()
		def resourceUri = user['resourceUri'].toString()
		def entryPath = '/_principals/entry/' + entryId + '?includeAll'

		// sanity: no lockout yet -> field absent on both endpoints
		def beforeEntryJson = fetchJsonOkAsMap(EntryStoreClient.getRequest(entryPath))
		assert !(beforeEntryJson['resource'] as Map).containsKey('disabledUntil')
		def beforeResourceJson = fetchJsonOkAsMap(EntryStoreClient.getRequest(resourceUri))
		assert !beforeResourceJson.containsKey('disabledUntil')

		// trigger temporary lockout: 3 bad attempts (matches entrystore.auth.temp.lockout.max.attempts=3)
		def badBody = createFormBody([auth_username: username, auth_password: 'badPass123'])
		3.times {
			def badConn = EntryStoreClient.postRequest('/auth/cookie', badBody, '', 'application/x-www-form-urlencoded')
			try {
				assert badConn.getResponseCode() == HTTP_UNAUTHORIZED
			} finally {
				badConn.disconnect()
			}
		}
		def afterAttempts = Instant.now()

		when:
		def lockedEntryConn = EntryStoreClient.getRequest(entryPath)
		def lockedEntryContentType = lockedEntryConn.getContentType()
		def lockedEntryJson = fetchJsonOkAsMap(lockedEntryConn)
		def lockedResource = lockedEntryJson['resource'] as Map
		def disabledUntilFromEntry = Instant.parse(lockedResource['disabledUntil'].toString())

		then:
		lockedEntryContentType.contains('application/json')
		// disabledUntil = last failed attempt + the configured 1s duration
		// (entrystore.auth.temp.lockout.duration), so the lockout must still be active as of the
		// instant the attempts finished — a zero-length lockout fails here. Anchoring both bounds to
		// a fixed instant instead of re-reading the clock keeps a slow GET from failing spuriously.
		disabledUntilFromEntry.isAfter(afterAttempts)
		disabledUntilFromEntry.isBefore(afterAttempts.plusSeconds(2))

		when:
		def lockedResourceJson = fetchJsonOkAsMap(EntryStoreClient.getRequest(resourceUri))
		def disabledUntilFromResource = Instant.parse(lockedResourceJson['disabledUntil'].toString())

		then:
		// both endpoints must agree on the lockout instant
		disabledUntilFromResource == disabledUntilFromEntry

		when:
		// outlast the 1s lockout window with comfortable slack for slow CI / GC pauses
		Thread.sleep(2000)
		def afterEntryJson = fetchJsonOkAsMap(EntryStoreClient.getRequest(entryPath))
		def afterResourceJson = fetchJsonOkAsMap(EntryStoreClient.getRequest(resourceUri))

		then:
		!(afterEntryJson['resource'] as Map).containsKey('disabledUntil')
		!afterResourceJson.containsKey('disabledUntil')

		and:
		// confirm the lockout itself cleared, not just its JSON projection: a regression that
		// drops disabledUntil from the response while leaving the lockout active would otherwise pass
		def goodBody = createFormBody([auth_username: username, auth_password: password])
		def postLockoutLogin = EntryStoreClient.postRequest('/auth/cookie', goodBody, '', 'application/x-www-form-urlencoded')
		try {
			assert postLockoutLogin.getResponseCode() == HTTP_OK
		} finally {
			postLockoutLogin.disconnect()
		}
	}

	private static Map fetchJsonOkAsMap(HttpURLConnection conn) {
		def code = conn.getResponseCode()
		def body
		try {
			body = code == HTTP_OK ? conn.inputStream.text : (conn.errorStream?.text ?: '')
		} finally {
			conn.disconnect()
		}
		assert code == HTTP_OK: "Expected 200 OK but got ${code} — body: ${body}"
		return JSON_PARSER.parseText(body) as Map
	}

}
