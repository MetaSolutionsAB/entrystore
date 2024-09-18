package org.entrystore.rest.it.util

import static java.net.HttpURLConnection.HTTP_OK

class EntryStoreClient {

	static String host = 'localhost'
	static int port = 8181 // Math.abs(new Random().nextInt() % 50000) + 10000
	static String origin = 'http://' + host + ':' + port

	def creds = ['admin': 'adminpass']
	def cookies = [:].withDefault { userName ->
		{
			authorize(userName.toString())
		}
	}

	def getRequest(String path, String asUser = null, String requestAcceptType = 'application/json') {
		def connection = (HttpURLConnection) new URI(origin + path).toURL().openConnection()
		if (requestAcceptType?.trim()) {
			connection.setRequestProperty('Accept', requestAcceptType)
		}
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.connect()
		return connection
	}

	def postRequest(String path, String body, String asUser = null, String contentType = 'application/json') {
		def connection = (HttpURLConnection) new URI(origin + path).toURL().openConnection()
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.setRequestMethod('POST')
		connection.setRequestProperty('Content-Type', contentType)
		connection.setDoOutput(true)
		connection.getOutputStream().write(body.getBytes())
		connection.connect()
		return connection
	}

	def authorize(String asUser) {
		def bodyParams = 'auth_username=' + asUser + '&auth_password=' + creds[asUser]
		def conn = postRequest('/auth/cookie', bodyParams, null,
			'application/x-www-form-urlencoded')

		assert conn.getResponseCode() == HTTP_OK
		def cookies = conn.getHeaderField('Set-Cookie')
		assert cookies != null
		assert cookies.contains('auth_token=')
		return cookies
	}

}
