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
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForTokenManagement@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDelete@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementDeleteCurrent@test.com', password)
		EntryStoreClient.creds.put('userForTokenManagementUpdate@test.com', password)
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
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
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams1 = 'auth_username=' + username + '&auth_password=' + password + '&auth_maxage=100'
		def loginConnection1 = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection1.getResponseCode() == HTTP_OK
		def cookie1 = loginConnection1.getHeaderField('Set-Cookie')
		def tokenPart1 = cookie1.substring(cookie1.indexOf('auth_token=') + 11, cookie1.indexOf('.node'))
		if (tokenPart1.contains(';')) {
			tokenPart1 = tokenPart1.substring(0, tokenPart1.indexOf(';'))
		}
		def bodyParams2 = 'auth_username=' + username + '&auth_password=' + password + '&auth_maxage=50'
		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams2, '', 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = loginConnection2.getHeaderField('Set-Cookie')
		def tokenPart2 = cookie2.substring(cookie2.indexOf('auth_token=') + 11, cookie2.indexOf('.node'))
		if (tokenPart2.contains(';')) {
			tokenPart2 = tokenPart2.substring(0, tokenPart2.indexOf(';'))
		}

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
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams1 = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection1 = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection1.getResponseCode() == HTTP_OK
		def cookie = loginConnection1.getHeaderField('Set-Cookie')
		def tokenPart = cookie.substring(cookie.indexOf('auth_token=') + 11, cookie.indexOf('.node'))
		if (tokenPart.contains(';')) {
			tokenPart = tokenPart.substring(0, tokenPart.indexOf(';'))
		}
		def tokensConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie])
		assert tokensConnection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(tokensConnection.inputStream.text)
		def oldLastAccessTime = LocalDateTime.parse(tokensRespJson[tokenPart]['lastAccessTime'].toString(), dtf)
		def oldLoginExpiration = LocalDateTime.parse(tokensRespJson[tokenPart]['loginExpiration'].toString(), dtf)
		def oldLoginTime = tokensRespJson[tokenPart]['loginTime']

		when:
		def tokensNewConnection = EntryStoreClient.getRequest('/auth/tokens', '', null, [Cookie: cookie])

		then:
		tokensNewConnection.getResponseCode() == HTTP_OK
		def tokensNewRespJson = JSON_PARSER.parseText(tokensNewConnection.inputStream.text)
		def newLastAccessTime = LocalDateTime.parse(tokensNewRespJson[tokenPart]['lastAccessTime'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLastAccessTime, newLastAccessTime) > 0
		def newLoginExpiration = LocalDateTime.parse(tokensNewRespJson[tokenPart]['loginExpiration'].toString(), dtf)
		ChronoUnit.MILLIS.between(oldLoginExpiration, newLoginExpiration) > 0
		oldLoginTime == tokensNewRespJson[tokenPart]['loginTime']
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
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams1 = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection1 = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection1.getResponseCode() == HTTP_OK
		def cookie1 = loginConnection1.getHeaderField('Set-Cookie')
		def tokenPart1 = cookie1.substring(cookie1.indexOf('auth_token=') + 11, cookie1.indexOf('.node'))
		if (tokenPart1.contains(';')) {
			tokenPart1 = tokenPart1.substring(0, tokenPart1.indexOf(';'))
		}
		def bodyParams2 = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection2 = EntryStoreClient.postRequest('/auth/cookie', bodyParams2, '', 'application/x-www-form-urlencoded')
		assert loginConnection2.getResponseCode() == HTTP_OK
		def cookie2 = loginConnection2.getHeaderField('Set-Cookie')
		def tokenPart2 = cookie2.substring(cookie2.indexOf('auth_token=') + 11, cookie2.indexOf('.node'))
		if (tokenPart2.contains(';')) {
			tokenPart2 = tokenPart2.substring(0, tokenPart2.indexOf(';'))
		}
		def body = JsonOutput.toJson([token: tokenPart2])

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json', [Cookie: cookie1])

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		def token1Connection = EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: cookie1])
		token1Connection.getResponseCode() == HTTP_OK
		def tokensRespJson = JSON_PARSER.parseText(token1Connection.inputStream.text)
		(tokensRespJson as Map).keySet().size() == 1
		tokensRespJson[tokenPart1] != null
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: cookie2]).getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "DELETE /auth/tokens should delete current login token of the authenticated user and log him out"() {
		given:
		def username = 'userForTokenManagementDeleteCurrent@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams1 = 'auth_username=' + username + '&auth_password=' + password
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams1, '', 'application/x-www-form-urlencoded')
		assert loginConnection.getResponseCode() == HTTP_OK
		def cookie = loginConnection.getHeaderField('Set-Cookie')
		def tokenPart = cookie.substring(cookie.indexOf('auth_token=') + 11, cookie.indexOf('.node'))
		if (tokenPart.contains(';')) {
			tokenPart = tokenPart.substring(0, tokenPart.indexOf(';'))
		}
		def body = JsonOutput.toJson([token: tokenPart])
		assert EntryStoreClient.getRequest('/auth/user', '', '', [Cookie: cookie]).getResponseCode() == HTTP_OK

		when:
		def tokensDeleteConnection = EntryStoreClient.deleteRequest('/auth/tokens', body, '', 'application/json', [Cookie: cookie])

		then:
		tokensDeleteConnection.getResponseCode() == HTTP_NO_CONTENT
		EntryStoreClient.getRequest('/auth/tokens', '', '', [Cookie: cookie]).getResponseCode() == HTTP_UNAUTHORIZED
	}
}
