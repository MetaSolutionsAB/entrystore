package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import spock.lang.Ignore

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class CookieLoginResourceIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForLogin@test.com', newPassword)
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
	}

	def "POST /auth/cookie should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def longString = RandomStringUtils.secure().nextAlphabetic(32769)
		def bodyParams = 'auth_username=' + longString + '&auth_password=' + newPassword

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/cookie should fail when required parameters are missing - username"() {
		given:
		def bodyParams = 'auth_password=' + newPassword

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail when required parameters are missing - password"() {
		given:
		def bodyParams = 'auth_username=anyone@test.com'

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail if the password is too long"() {
		given:
		def longString = RandomStringUtils.secure().nextAlphabetic(2049)
		def bodyParams = 'auth_username=anyone@test.com&auth_password=' + longString

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should log in the user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForLogin@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: newPassword
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def bodyParams = 'auth_username=userForLogin@test.com&auth_password=' + newPassword

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_OK
		loginConnection.getHeaderField('Set-Cookie') != null
		loginConnection.getHeaderField('Set-Cookie').contains('auth_token=')
		loginConnection.getContentType().contains('text/html')
		loginConnection.getInputStream().text.contains("Login successful.")
	}

	@Ignore
	def "POST /auth/cookie should not log in the user after cookie maxAge is set in request and expired"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForLoginExpired@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: newPassword
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def bodyParams = 'auth_username=userForLoginExpired@test.com&auth_password=' + newPassword + '&auth_maxage=1'
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')
		loginConnection.getResponseCode() == HTTP_OK
		Thread.sleep(2000)

		when:
		def detailsConn = EntryStoreClient.createConnection('/auth/user')
		detailsConn.connect()

		then:
		detailsConn.getResponseCode() == HTTP_UNAUTHORIZED

	}

	def "POST /auth/cookie should not log in the blacklisted user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForLoginBlacklist@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: newPassword
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def bodyParams = 'auth_username=userForLoginBlacklist@test.com&auth_password=' + newPassword

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('text/html')
		loginConnection.getErrorStream().text.contains("Login failed.")
	}

	def "POST /auth/cookie should not log in the blacklisted user and not respond with 'Login failed.' html"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForLoginBlacklistNoHtml@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: newPassword
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def bodyParams = 'auth_username=userForLoginBlacklistNoHtml@test.com&auth_password=' + newPassword
		def acceptHtml = new HashMap<String, String>()
		acceptHtml.put('Accept', 'application/json')

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded', acceptHtml)

		then:
		loginConnection.getResponseCode() == HTTP_UNAUTHORIZED
		loginConnection.getContentType().contains('text/html')
		!loginConnection.getErrorStream().text.contains("Login failed.")
	}

	def "POST /auth/cookie should not log in the disabled user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForLoginDisabled@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: newPassword,
				disabled: true
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def bodyParams = 'auth_username=userForLoginDisabled@test.com&auth_password=' + newPassword

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_FORBIDDEN
		loginConnection.getContentType().contains('text/html')
		loginConnection.getErrorStream().text.contains("Login failed. The account is disabled.")
	}

}
