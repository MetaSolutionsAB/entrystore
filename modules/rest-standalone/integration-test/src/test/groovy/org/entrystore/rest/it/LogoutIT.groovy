package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_BAD_METHOD
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED


class LogoutIT extends BaseSpec {

	def 'GET /auth/logout as non-admin user in JSON format should logout the user'() {
		given:
		// we query /auth/user attaching user cookies manually for test clarity and to not invalidate the shared cookie in "EntryStoreClient.cookies['user']"
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(userConn.getInputStream().text)
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		connection.getHeaderField('Set-Cookie') != null
		connection.getHeaderField('Set-Cookie').contains('Max-Age=0')

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		// for some reason error body is an HTML page (even tho JSON was asked)
		userConn2.getContentType().contains('text/html')
		def responseBody = userConn2.getErrorStream()?.text
		responseBody.contains('<title>Status page</title>')
		responseBody.contains('Unauthorized')
		responseBody.contains('Please continue your visit at our <a href="/">home page</a>.')
	}

	def 'GET /auth/logout as non-admin user in HTML format should logout the user'() {
		given:
		// we query /auth/user attaching user cookies manually for test clarity and to not invalidate the shared cookie in "EntryStoreClient.cookies['user']"
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(userConn.getInputStream().text)
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'text/html', userCookies)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		connection.getHeaderField('Set-Cookie') != null
		connection.getHeaderField('Set-Cookie').contains('Max-Age=0')
		def responseBody = connection.getInputStream().text
		responseBody.contains('<title>Logout</title>')
		responseBody.contains('<div>Logout successful.</div>')

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		// for some reason error body is an HTML page (even tho JSON was asked)
		userConn2.getContentType().contains('text/html')
		def responseBody2 = userConn2.getErrorStream()?.text
		responseBody2.contains('<title>Status page</title>')
		responseBody2.contains('Unauthorized')
		responseBody2.contains('Please continue your visit at our <a href="/">home page</a>.')
	}

	def 'GET /auth/logout as guest in JSON format should silently do nothing as there is no session to logout'() {
		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '')

		then: 'we\'re a guest'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(userConn.getInputStream().text)
		jsonUserConn['user'] == 'guest'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '')

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '')

		then: 'we should get a OK with a guest user'
		userConn2.getResponseCode() == HTTP_OK
		userConn2.getContentType().contains('application/json')
		def jsonUserConn2 = JSON_PARSER.parseText(userConn2.getInputStream().text)
		jsonUserConn2['user'] == 'guest'
	}

	def 'GET /auth/logout as admin in JSON format should logout the user'() {
		given:
		// we query /auth/user attaching cookies manually for test clarity and to not invalidate the shared cookie in "EntryStoreClient.cookies['admin']"
		def userCookies = [Cookie: EntryStoreClient.authorize('admin').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the admin'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(userConn.getInputStream().text)
		jsonUserConn['user'] == 'admin'

		when: 'we logout'
		def connection = EntryStoreClient.getRequest('/auth/logout', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		connection.getHeaderField('Set-Cookie') != null
		connection.getHeaderField('Set-Cookie').contains('Max-Age=0')

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should get unauthorized as session/cookie got invalidated during logout'
		userConn2.getResponseCode() == HTTP_UNAUTHORIZED
		// for some reason error body is an HTML page (even tho JSON was asked)
		userConn2.getContentType().contains('text/html')
		def responseBody = userConn2.getErrorStream()?.text
		responseBody.contains('<title>Status page</title>')
		responseBody.contains('Unauthorized')
		responseBody.contains('Please continue your visit at our <a href="/">home page</a>.')
	}

	def 'POST /auth/logout as user in JSON format should respond with 405 as POST is not supported in Restlet'() {
		given:
		// we query /auth/user attaching cookies manually for test clarity and to not invalidate the shared cookie in "EntryStoreClient.cookies['user']"
		def userCookies = [Cookie: EntryStoreClient.authorize('user').toString()]

		when: 'we query /auth/user'
		def userConn = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'verify cookie works - we\'re the user'
		userConn.getResponseCode() == HTTP_OK
		userConn.getContentType().contains('application/json')
		def jsonUserConn = JSON_PARSER.parseText(userConn.getInputStream().text)
		jsonUserConn['user'] == 'user'

		when: 'we logout'
		def connection = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json', userCookies)

		then:
		connection.getResponseCode() == HTTP_BAD_METHOD

		when: 'we query backend with the same cookie'
		def userConn2 = EntryStoreClient.getRequest('/auth/user', '', 'application/json', userCookies)

		then: 'we should still be logged-in as the user'
		userConn2.getResponseCode() == HTTP_OK
		userConn2.getContentType().contains('application/json')
		def jsonUserConn2 = JSON_PARSER.parseText(userConn2.getInputStream().text)
		jsonUserConn2['user'] == 'user'
	}
}
