package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil
import spock.lang.Ignore

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

// NOT MIGRATED YET
@Ignore
class CookieLoginResourceIT extends BaseSpec {

	static def password = 'newPass12345'
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForLogin@test.com', password)
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
	}

	def "POST /auth/cookie should fail if the data sent to server is larger then 32KB or unknown"() {
		given:
		def username = RandomStringUtils.secure().nextAlphabetic(32769)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /auth/cookie should fail when required parameters are missing - username"() {
		given:
		def bodyParams = 'auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail when required parameters are missing - password"() {
		given:
		def username = 'anyone@test.com'
		def bodyParams = 'auth_username=' + username

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

		then:
		loginConnection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /auth/cookie should fail if the password is too long"() {
		given:
		def username = 'anyone@test.com'
		def password = RandomStringUtils.secure().nextAlphabetic(2049)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password

		when:
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded')

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

	@Ignore
	def "POST /auth/cookie should not log in the user after cookie maxAge is set in request and expired"() {
		given:
		def username = 'userForLogin@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def bodyParams = 'auth_username=' + username + '&auth_password=' + password + '&auth_maxage=1'
		assert EntryStoreClient.postRequest('/auth/cookie', bodyParams, "", 'application/x-www-form-urlencoded').getResponseCode() == HTTP_OK
		Thread.sleep(2000)

		when:
		def detailsConn = EntryStoreClient.createConnection('/auth/user')
		detailsConn.connect()

		then:
		detailsConn.getResponseCode() == HTTP_UNAUTHORIZED
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

}
