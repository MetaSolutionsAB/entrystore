package org.entrystore.rest.it.util

import groovy.json.JsonOutput
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

	private static final Set<HttpMethod> MUTATING_METHODS = Set.of(
			HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

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
		authorize(userName.toString())
	}

	// Per-user CSRF token, populated alongside the session cookie in `authorize`.
	// Sent as X-XSRF-TOKEN on mutating requests so SecurityConfig's CSRF protection
	// accepts cookie-authenticated POST/PUT/PATCH/DELETE.
	static def csrfTokens = [:]

	static def cleanCookies() {
		cookies.clear()
		csrfTokens.clear()
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

	def static putRequestMultiPart(String path, File file, String asUser = 'admin',
								   Map<String, String> formData = [:],
								   String partContentType = 'application/octet-stream') {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary, partContentType)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.PUT, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static postRequestMultiPart(String path, File file, String asUser = 'admin',
									Map<String, String> formData = [:],
									String partContentType = 'application/octet-stream') {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary, partContentType)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.POST, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static deleteRequest(String path, String body = emptyJsonBody, String asUser = 'admin',
							 String contentType = 'application/json', Map<String, String> extraHeaders = [:]) {
		def contentStream = (body == null) ? null : new ByteArrayInputStream(body.getBytes(UTF_8))
		return sendRequestAsStream(HttpMethod.DELETE, path, contentStream, asUser, contentType, extraHeaders)
	}

	def static sendRequestAsStream(HttpMethod method, String path, InputStream inputStream, String asUser,
								   String contentType, Map<String, String> extraHeaders = [:]) {

		def connection = createConnection(path)
		connection.setRequestMethod(method.name())
		connection.setInstanceFollowRedirects(false)
		if (asUser?.trim()) {
			connection.setRequestProperty('Cookie', cookieHeader(asUser))
			if (MUTATING_METHODS.contains(method) && csrfTokens[asUser] != null) {
				connection.setRequestProperty('X-XSRF-TOKEN', csrfTokens[asUser].toString())
			}
		}
		if (contentType?.trim()) {
			connection.setRequestProperty('Content-Type', contentType)
		}
		extraHeaders?.each { key, value ->
			// Skip Content-Length — HttpURLConnection manages it internally
			// (setting it manually conflicts with chunked streaming mode)
			if (key != 'Content-Length') {
				connection.setRequestProperty(key, value)
			}
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
			path = path.replaceFirst('/store', '')
		}
		return (HttpURLConnection) new URI(hostInfo + path).toURL().openConnection()
	}

	def static authorize(String asUser) {
		def bodyParams = 'auth_username=' + asUser + '&auth_password=' + creds[asUser]
		def conn = postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		assert conn.getResponseCode() == HTTP_OK
		def authCookie = findSetCookie(conn, 'auth_token')
		assert authCookie != null
		// Fail fast here rather than letting downstream mutation ITs see opaque 401s if a regression
		// stops emitting XSRF-TOKEN on /auth/cookie — the missing token is the real root cause.
		def csrfValue = findCookieValue(conn, 'XSRF-TOKEN')
		assert csrfValue != null: '/auth/cookie response must carry XSRF-TOKEN cookie'
		csrfTokens[asUser] = csrfValue
		return authCookie
	}

	/**
	 * Returns the first Set-Cookie response header line whose cookie name matches {@code cookieName}
	 * (i.e. starts with {@code cookieName + '='}), or null if no such header is present. Use this
	 * instead of {@code connection.getHeaderField('Set-Cookie')} when the response carries multiple
	 * Set-Cookie headers (e.g. both auth_token and XSRF-TOKEN are emitted on login). Set-Cookie
	 * always begins with {@code name=value}, so a startsWith match is exact for the cookie name and
	 * cannot be tricked by an attribute value (e.g. {@code Path=/x?auth_token=}) that contains a
	 * similar substring.
	 */
	def static findSetCookie(HttpURLConnection connection, String cookieName) {
		def headerValues = connection.getHeaderFields().get('Set-Cookie')
		if (headerValues == null) {
			return null
		}
		return headerValues.find { it != null && it.startsWith(cookieName + '=') }
	}

	private static String extractCookieValue(String setCookieLine, String cookieName) {
		def prefix = cookieName + '='
		def idx = setCookieLine.indexOf(prefix)
		if (idx == -1) return null
		def start = idx + prefix.length()
		def end = setCookieLine.indexOf(';', start)
		return end == -1 ? setCookieLine.substring(start) : setCookieLine.substring(start, end)
	}

	/**
	 * Returns the value of the named cookie from the response's Set-Cookie headers, or null.
	 * Useful when a test needs to grab the XSRF-TOKEN value to forward it as X-XSRF-TOKEN
	 * on a subsequent request that does not flow through `asUser`-based auto-injection.
	 */
	def static findCookieValue(HttpURLConnection connection, String cookieName) {
		def line = findSetCookie(connection, cookieName)
		return line == null ? null : extractCookieValue(line, cookieName)
	}

	/**
	 * Returns a Cookie request-header value for a user that includes both the session cookie and
	 * (when present) the XSRF-TOKEN cookie, so server-side {@code CookieCsrfTokenRepository} can
	 * validate the X-XSRF-TOKEN header against a matching cookie on cookie-authenticated mutations.
	 */
	def static cookieHeader(String asUser) {
		def header = cookies[asUser].toString()
		if (csrfTokens[asUser] != null) {
			header = header + '; XSRF-TOKEN=' + csrfTokens[asUser]
		}
		return header
	}

	/**
	 * Builds the Cookie + X-XSRF-TOKEN header pair required by SecurityConfig's CSRF protection for
	 * a cookie-authenticated mutation. Use this when constructing extraHeaders for a request that
	 * does not flow through {@code asUser}-based auto-injection (e.g. isolated-session tests that
	 * captured their own auth_token/XSRF-TOKEN pair from a fresh login).
	 */
	def static Map<String, String> csrfHeaders(String authCookie, String csrf) {
		return [
				Cookie        : authCookie + '; XSRF-TOKEN=' + csrf,
				'X-XSRF-TOKEN': csrf
		]
	}

	def static buildMultipartContent(File file, Map<String, String> formData, String boundary,
									 String partContentType = 'application/octet-stream') {
		def os = new ByteArrayOutputStream()

		formData.each { name, value ->
			os.write("--${boundary}\r\n".bytes)
			os.write("Content-Disposition: form-data; name=\"${name}\"\r\n\r\n".bytes)
			os.write("${value}\r\n".bytes)
		}

		os.write("--${boundary}\r\n".bytes)
		os.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".bytes)
		os.write("Content-Type: ${partContentType}\r\n\r\n".bytes)
		os.write(file.bytes)
		os.write("\r\n--${boundary}--\r\n".bytes)

		return os.toByteArray()
	}

}
