package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.apache.commons.lang3.StringUtils
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8

/**
 * A utility client for interacting with the EntryStore REST API during integration tests.
 * Handles authentication, request construction, and multipart form data.
 *
 * `creds` map contains User accounts that can be used by this client.
 * On the first time use of a given User, the `authorize` method is called, to login the user.
 * Then login cookie is stored in `cookies` map, to reuse it in the next requests for the given User.
 *
 */
class EntryStoreClient {

	static String host = 'localhost'
	static int port = 8181 // Math.abs(new Random().nextInt() % 50000) + 10000
	static String origin = 'http://' + host + ':' + port
	static String baseUrl = origin + '/store'
	static String adminsGroupUri = baseUrl + '/_principals/resource/_admins'

	static def emptyJsonBody = JsonOutput.toJson([:])

	// Map of Users (username->user) that were created during the tests initialization (createCommonUserAccounts)
	static def createdEsUsers = [:]

	// User accounts that can be used by this client
	static def creds = [
		'admin'            : 'adminpass',
		'user'             : 'userPass123',
		'userInAdminGroup' : 'userPass123',
		'userForNameChange': 'userPass123'
	]

	static def accountsInAdminGroup = [
		'userInAdminGroup'
	]

	static def cookies = [:].withDefault { userName ->
		{
			authorize(userName.toString())
		}
	}

	static def cleanCookies() {
		cookies.clear()
	}

	static def isAnAdmin(String username) {
		return accountsInAdminGroup.contains(username)
	}

	def static getRequest(String path, String asUser = 'admin', String requestAcceptType = 'application/json', Map<String, String> extraHeaders = [:]) {
		if (requestAcceptType?.trim()) {
			extraHeaders['Accept'] = requestAcceptType
		}
		return sendRequestAsStream(HttpMethod.GET, path, null, asUser, null, extraHeaders)
	}

	def static postRequest(String path, String body = emptyJsonBody, String asUser = 'admin',
						   String contentType = 'application/json', Map<String, String> extraHeaders = [:]) {
		def contentStream = (body == null) ? null : new ByteArrayInputStream(body.getBytes(UTF_8))
		return sendRequestAsStream(HttpMethod.POST, path, contentStream, asUser, contentType, extraHeaders)
	}

	def static putRequest(String path, String body = emptyJsonBody, String asUser = 'admin',
						  String contentType = 'application/json', Map<String, String> extraHeaders = [:]) {
		return sendRequestAsStream(HttpMethod.PUT, path, new ByteArrayInputStream(body.getBytes()), asUser, contentType, extraHeaders)
	}

	def static putRequestFile(String path, File file, String asUser = 'admin', String contentType = 'application/octet-stream') {
		file.withInputStream { inputStream ->
			def extraHeaders = [
				'Content-Length'     : file.length().toString(),
				'Content-Disposition': 'form-data; name="file"; filename="' + file.getName() + '"'
			]
			return sendRequestAsStream(HttpMethod.PUT, path, inputStream, asUser, contentType, extraHeaders)
		}
	}

	def static putRequestMultiPart(String path, File file, String asUser = 'admin', Map<String, String> formData = [:]) {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.PUT, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static postRequestMultiPart(String path, File file, String asUser = 'admin', Map<String, String> formData = [:]) {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.POST, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static deleteRequest(String path, String asUser = 'admin') {
		return sendRequestAsStream(HttpMethod.DELETE, path, null, asUser, null)
	}

	def static sendRequestAsStream(HttpMethod method, String path, InputStream inputStream, String asUser,
								   String contentType, Map<String, String> extraHeaders = [:]) {

		def connection = createConnection(path)
		connection.setRequestMethod(method.name())
		connection.setInstanceFollowRedirects(false)
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		if (contentType?.trim()) {
			connection.setRequestProperty('Content-Type', contentType)
		}
		extraHeaders?.each { key, value ->
			connection.setRequestProperty(key, value)
		}

		if (inputStream != null) {
			connection.setDoOutput(true)
			if (extraHeaders.getOrDefault('Content-Length', "0").toInteger() > 8000) {
				connection.setChunkedStreamingMode(8192)
			}
			connection.outputStream.withStream { output ->
				output << inputStream
			}
		}
		connection.connect()

		return connection
	}

	/**
	 *
	 * @param path can be a local path. e.g. /_contexts/entry/_principals or a full URL
	 * @return
	 */
	def static createConnection(String path) {
		def hostInfo = ''
		if (path.startsWith('/')) {
			hostInfo = origin
		} else {
			path = StringUtils.replaceOnce(path, '/store', '')
		}
		return (HttpURLConnection) new URI(hostInfo + path).toURL().openConnection()
	}

	def static authorize(String asUser) {
		def bodyParams = 'auth_username=' + asUser + '&auth_password=' + creds[asUser]
		def conn = postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		assert conn.getResponseCode() == HTTP_OK
		def cookies = conn.getHeaderField('Set-Cookie')
		assert cookies != null
		assert cookies.contains('auth_token')
		return cookies
	}

	def static buildMultipartContent(File file, Map<String, String> formData, String boundary) {
		def os = new ByteArrayOutputStream()

		formData.each { name, value ->
			os.write("--${boundary}\r\n".bytes)
			os.write("Content-Disposition: form-data; name=\"${name}\"\r\n\r\n".bytes)
			os.write("${value}\r\n".bytes)
		}

		os.write("--${boundary}\r\n".bytes)
		os.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".bytes)
		os.write("Content-Type: application/octet-stream\r\n\r\n".bytes)
		os.write(file.bytes)
		os.write("\r\n--${boundary}--\r\n".bytes)

		return os.toByteArray()
	}

}
