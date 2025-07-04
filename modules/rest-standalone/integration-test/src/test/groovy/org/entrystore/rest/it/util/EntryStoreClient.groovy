package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.apache.commons.lang3.StringUtils

import static java.net.HttpURLConnection.HTTP_OK

class EntryStoreClient {

	static String host = 'localhost'
	static int port = 8181 // Math.abs(new Random().nextInt() % 50000) + 10000
	static String origin = 'http://' + host + ':' + port
	static String baseUrl = origin + '/store'

	def static emptyJsonBody = JsonOutput.toJson([:])

	def static creds = ['admin': 'adminpass']
	def static cookies = [:].withDefault { userName ->
		{
			authorize(userName.toString())
		}
	}

	def static getRequest(String path, String asUser = 'admin', String requestAcceptType = 'application/json') {
		def connection = createConnection(path)
		if (requestAcceptType?.trim()) {
			connection.setRequestProperty('Accept', requestAcceptType)
		}
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.connect()
		return connection
	}

	def static postRequest(String path, String body = emptyJsonBody, String asUser = 'admin', String contentType = 'application/json') {
		def connection = createConnection(path)
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.setRequestMethod('POST')
		connection.setRequestProperty('Content-Type', contentType)
		if (body != null) {
			connection.setDoOutput(true)
			connection.getOutputStream().write(body.getBytes())
			connection.connect()
		}
		return connection
	}

	def static putRequest(String path, String body = emptyJsonBody, String asUser = 'admin', String contentType = 'application/json') {
		return putRequestStream(path, new ByteArrayInputStream(body.getBytes()), asUser, contentType)
	}

	def static putRequestFile(String path, File file, String asUser = 'admin', String contentType = 'application/octet-stream') {
		file.withInputStream { inputStream ->
			def extraHeaders = [
				'Content-Length'     : file.length().toString(),
				'Content-Disposition': 'form-data; name="file"; filename="' + file.getName() + '"'
			]
			return putRequestStream(path, inputStream, asUser, contentType, extraHeaders)
		}
	}

	def static putRequestMultiPart(String path, File file, String asUser = 'admin', Map<String, String> formData = [:]) {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary)
		def inputStream = new ByteArrayInputStream(content)

		return putRequestStream(path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static putRequestStream(String path, InputStream inputStream, String asUser, String contentType, Map<String, String> extraHeaders = [:]) {
		def connection = createConnection(path)
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.setRequestMethod('PUT')
		connection.setDoOutput(true)
		connection.setRequestProperty('Content-Type', contentType)
		extraHeaders.each { key, value ->
			connection.setRequestProperty(key, value)
		}

		connection.outputStream.withStream { output ->
			output << inputStream
		}
		connection.connect()

		return connection
	}

	def static deleteRequest(String path, String asUser = 'admin') {
		def connection = createConnection(path)
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookies[asUser].toString())
		}
		connection.setRequestMethod('DELETE')
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
		def conn = postRequest('/auth/cookie', bodyParams, null,
			'application/x-www-form-urlencoded')

		assert conn.getResponseCode() == HTTP_OK
		def cookies = conn.getHeaderField('Set-Cookie')
		assert cookies != null
		assert cookies.contains('auth_token=')
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
