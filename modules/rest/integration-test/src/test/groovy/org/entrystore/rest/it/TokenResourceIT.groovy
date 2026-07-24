package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class TokenResourceIT extends BaseSpec {

	static def dtf = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
		.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
		.toFormatter()
	static def password = 'newPass12345'

	def setupSpec() {
		EntryStoreClient.snapshotCreds()
		EntryStoreClient.creds.put('userForTokenManagement@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDelete@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDeleteCurrent@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementUpdate@test.com', password)
	}

	def cleanupSpec() {
		EntryStoreClient.restoreCreds()
	}

	/** Token value from an auth_token Set-Cookie line, up to but excluding the '.node' suffix. */
	private static String tokenPart(String authCookieLine) {
		authCookieLine.substring('auth_token='.length()).split(/\.node/)[0]
	}

	def "GET /auth/tokens should get unauthorized for a non-authenticated user"() {
		when:
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '')

		then:
		tokensConnection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /auth/tokens should get a list of all currently active logins for of an authenticated user"() {
		given:
		def username = 'userForTokenManagement@test.com'
		UserUtil.createUserWithPassword(username, password)
		// raw login ritual kept: loginIsolated does not support auth_maxage
		def bodyParams1 = createFormBody([auth_username: username, auth_password: password, auth_maxage: '100'])
		def loginConnection1 = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection1.getResponseCode() == HTTP_OK
		def cookie1 = EntryStoreClient.findSetCookie(loginConnection1, 'auth_token')
		def tokenPart1 = tokenPart(cookie1)
		def bodyParams2 = createFormBody([auth_username: username, auth_password: password, auth_maxage: '50'])
		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams2, '', 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = EntryStoreClient.findSetCookie(loginConnection2, 'auth_token')
		def tokenPart2 = tokenPart(cookie2)

		when:
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie1])

		then:
		tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text)
		(tokensRespJson as Map).keySet().size() == 2
		tokensRespJson[tokenPart1] != null
		tokensRespJson[tokenPart1]['userName'] == 'userfortokenmanagement@test.com'
		tokensRespJson[tokenPart1]['lastAccessTime'] != null
		tokensRespJson[tokenPart1]['loginExpiration'] != null
		tokensRespJson[tokenPart1]['loginTime'] != null
		tokensRespJson[tokenPart1]['lastUsedIpAddress'] != null
		tokensRespJson[tokenPart1]['loginTokenMaxAge'] == 100
		tokensRespJson[tokenPart1]['lastUsedUserAgent'] != null
		tokensRespJson[tokenPart2] != null
		tokensRespJson[tokenPart2]['userName'] == 'userfortokenmanagement@test.com'
		tokensRespJson[tokenPart2]['loginTokenMaxAge'] == 50
	}

	def "GET /auth/tokens should get a list of all currently active logins of an authenticated user with updated timestamps"() {
		given:
		def username = 'userForTokenManagementUpdate@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login = EntryStoreClient.loginIsolated(username)
		def token = tokenPart(login.authCookie)
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])
		assert tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text)
		def oldLastAccessTime = LocalDateTime.parse(tokensRespJson[token]['lastAccessTime'].toString(), dtf)
		def oldLoginExpiration = LocalDateTime.parse(tokensRespJson[token]['loginExpiration'].toString(), dtf)
		def oldLoginTime = tokensRespJson[token]['loginTime']

		when:
		// timestamps have millisecond precision; make sure the second request lands in a strictly
		// later millisecond so the > 0 assertions below stay meaningful
		Thread.sleep(5)
		def tokensNewConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: login.authCookie])

		then:
		tokensNewConnection.getResponseCode() == HTTP_OK
		def tokensNewRespJson = JSON_PARSER.parseText(tokensNewConnection.inputStream.text)
		def newLastAccessTime = LocalDateTime.parse(tokensNewRespJson[token]['lastAccessTime'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLastAccessTime, newLastAccessTime) > 0
		def newLoginExpiration = LocalDateTime.parse(tokensNewRespJson[token]['loginExpiration'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLoginExpiration, newLoginExpiration) > 0
		oldLoginTime == tokensNewRespJson[token]['loginTime']
	}

	def "DELETE /auth/tokens should get unauthorized for a non-authenticated user"() {
		when:
		def tokensConnection = EntryStoreClient.deleteRequest('/auth/tokens', '[]', '')

		then:
		tokensConnection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should delete a specified token of the authenticated user"() {
		given:
		def username = 'userForTokenManagementDelete@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login1 = EntryStoreClient.loginIsolated(username)
		def tokenPart1 = tokenPart(login1.authCookie)
		def login2 = EntryStoreClient.loginIsolated(username)
		def tokenPart2 = tokenPart(login2.authCookie)
		def body = JsonOutput.toJson([token: tokenPart2])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login1.authCookie, login1.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		def token1Connection = EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login1.authCookie])
		token1Connection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(token1Connection.inputStream.text)
		(tokensRespJson as Map).keySet().size() == 1
		tokensRespJson[tokenPart1] != null
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login2.authCookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should delete current login token of the authenticated user and log him out"() {
		given:
		def username = 'userForTokenManagementDeleteCurrent@test.com'
		UserUtil.createUserWithPassword(username, password)
		def login = EntryStoreClient.loginIsolated(username)
		def body = JsonOutput.toJson([token: tokenPart(login.authCookie)])
		assert EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_OK

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json',
				EntryStoreClient.csrfHeaders(login.authCookie, login.csrf))

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: login.authCookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}
}
