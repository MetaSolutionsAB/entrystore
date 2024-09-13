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

	def getRequest(String path, String asUser = null) {
		def connection = (HttpURLConnection) new URI(origin + path).toURL().openConnection()
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.connect()
		return connection
	}

	def authorize(String asUser) {
		def urlParams = 'auth_username=' + asUser + '&auth_password=' + creds[asUser]
		def conn = (HttpURLConnection) new URI(origin + '/auth/cookie').toURL().openConnection()
		conn.setRequestMethod('POST')
		conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
		conn.setDoOutput(true)
		conn.getOutputStream().write(urlParams.getBytes())
		conn.connect()

		assert conn.getResponseCode() == HTTP_OK
		def cookies = conn.getHeaderField('Set-Cookie')
		assert cookies != null
		assert cookies.contains('auth_token=')
		return cookies
	}

}
