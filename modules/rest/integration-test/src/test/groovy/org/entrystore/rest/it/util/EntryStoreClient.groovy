/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.entrystore.rest.it.BaseSpec
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
	static int port = 8181
	static String origin = 'http://' + host + ':' + port
	static String contextPath = '/store'
	static String baseUrl = origin + contextPath
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

	// Non-null only while a snapshotCreds/restoreCreds pair is in flight; the asserts below enforce pairing.
	private static Map credsSnapshot = null

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

	/**
	 * Snapshots the shared {@code creds} map so a spec can add temporary users in setupSpec.
	 * Pair with {@link #restoreCreds} in cleanupSpec.
	 */
	static def snapshotCreds() {
		assert credsSnapshot == null: 'snapshotCreds called twice without restoreCreds — overlapping spec snapshots'
		credsSnapshot = creds.clone() as Map
	}

	/** Restores the {@code creds} map captured by {@link #snapshotCreds}. */
	static def restoreCreds() {
		assert credsSnapshot != null: 'restoreCreds called without a prior snapshotCreds'
		creds = credsSnapshot
		credsSnapshot = null
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
		def contentStream = (body == null) ? null : new ByteArrayInputStream(body.getBytes())
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
								   String partContentType = 'application/octet-stream',
								   String partName = 'file') {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary, partContentType, partName)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.PUT, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	/** Sends a multipart/form-data request carrying only form fields, i.e. without any file part. */
	def static putRequestMultiPartWithoutFile(String path, Map<String, String> formData, String asUser = 'admin') {
		return putRequestMultiPart(path, null, asUser, formData)
	}

	/** Sends a PUT built from raw file-part descriptors, see {@link #buildMultipartBody}. */
	def static putRequestMultiPartParts(String path, List<Map> fileParts,
										Map<String, String> formData = [:], String asUser = 'admin') {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartBody(formData, fileParts, boundary)

		return sendRequestAsStream(HttpMethod.PUT, path, new ByteArrayInputStream(content), asUser, contentType,
			['Content-Length': content.length.toString()])
	}

	/** Sends a multipart/form-data request carrying only form fields, i.e. without any file part. */
	def static postRequestMultiPartWithoutFile(String path, Map<String, String> formData, String asUser = 'admin') {
		return postRequestMultiPart(path, null, asUser, formData)
	}

	def static postRequestMultiPart(String path, File file, String asUser = 'admin',
									Map<String, String> formData = [:],
									String partContentType = 'application/octet-stream',
									String partName = 'file') {
		def boundary = '----FormBoundary' + System.currentTimeMillis()
		def contentType = 'multipart/form-data; boundary=' + boundary

		def content = buildMultipartContent(file, formData, boundary, partContentType, partName)
		def inputStream = new ByteArrayInputStream(content)

		return sendRequestAsStream(HttpMethod.POST, path, inputStream, asUser, contentType, ['Content-Length': content.length.toString()])
	}

	def static deleteRequest(String path, String body = emptyJsonBody, String asUser = 'admin',
							 String contentType = 'application/json', Map<String, String> extraHeaders = [:]) {
		def contentStream = (body == null) ? null : new ByteArrayInputStream(body.getBytes())
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
	 * or a local path with context e.g. /store/*
	 * @return
	 */
	def static createConnection(String path) {
		def hostUrl = ''
		if (path.startsWith(contextPath)) {
			hostUrl = origin
		} else if (path.startsWith('/')) {
			hostUrl = baseUrl
		}
		return (HttpURLConnection) new URI(hostUrl + path).toURL().openConnection()
	}

	def static authorize(String asUser) {
		def login = loginIsolated(asUser)
		csrfTokens[asUser] = login.csrf
		return login.authCookie
	}

	/**
	 * Logs the user in via POST /auth/cookie without touching the shared {@code cookies}/
	 * {@code csrfTokens} maps, so a test can exercise a session it owns (logout, CSRF) without
	 * invalidating the shared session other ITs reuse. The user must be present in {@code creds}.
	 *
	 * @return map with the auth_token Set-Cookie line ({@code authCookie}) and the XSRF-TOKEN
	 * value ({@code csrf}; null when the app under test runs with CSRF protection disabled)
	 */
	def static loginIsolated(String user) {
		assert creds.containsKey(user): "user '" + user + "' is not present in EntryStoreClient.creds — add it in setupSpec"
		def bodyParams = 'auth_username=' + URLEncoder.encode(user, UTF_8) +
			'&auth_password=' + URLEncoder.encode(creds[user].toString(), UTF_8)
		def conn = postRequest('/auth/cookie', bodyParams, '', 'application/x-www-form-urlencoded')

		assert conn.getResponseCode() == HTTP_OK
		def authCookie = findSetCookie(conn, 'auth_token')
		assert authCookie != null
		def csrfValue = findCookieValue(conn, 'XSRF-TOKEN')
		if (isCsrfEnabled()) {
			// Fail fast here rather than letting downstream mutation ITs see opaque 401s if a regression
			// stops emitting XSRF-TOKEN on /auth/cookie — the missing token is the real root cause.
			assert csrfValue != null: '/auth/cookie response must carry XSRF-TOKEN cookie when CSRF protection is enabled'
		} else {
			// Inverse fail-fast: a CSRF-disabled app must not issue a token cookie either.
			assert csrfValue == null: '/auth/cookie response must not carry XSRF-TOKEN cookie when CSRF protection is disabled'
		}
		return [authCookie: authCookie, csrf: csrfValue]
	}

	/**
	 * Reads the effective CSRF setting from the running app under test, so this client adapts to
	 * both the shared app (started by BaseSpec with {@code --entrystore.csrf.enabled=true}) and
	 * lifecycle-owning Zzz ITs whose apps run with the default (disabled, ENTRYSTORE-1096).
	 */
	private static boolean isCsrfEnabled() {
		return BaseSpec.appInstance != null &&
			BaseSpec.appInstance.getEnvironment().getProperty('entrystore.csrf.enabled', Boolean, false)
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
	def static csrfHeaders(String authCookie, String csrf) {
		return [
				Cookie        : authCookie + '; XSRF-TOKEN=' + csrf,
				'X-XSRF-TOKEN': csrf
		]
	}

	/**
	 * Logs the user into a session the test owns (via {@link #loginIsolated}, so the shared
	 * cookies/csrfTokens maps stay untouched) and returns the Cookie + X-XSRF-TOKEN header pair
	 * for cookie-authenticated mutations on that session.
	 */
	static Map<String, String> isolatedCsrfHeaders(String user) {
		def login = loginIsolated(user)
		return csrfHeaders(login.authCookie.toString(), login.csrf.toString())
	}

	/**
	 * Converts Set-Cookie response-header lines into a Cookie request-header value, stripping the
	 * cookie attributes (Path, HttpOnly, ...) each line carries.
	 */
	static String toCookieHeader(List<String> setCookieLines) {
		assert setCookieLines: 'response carries no Set-Cookie header'
		return setCookieLines.collect { it.split(';')[0] }.join('; ')
	}

	/** Builds a multipart body of the given form fields, plus a file part when a file is given. */
	def static buildMultipartContent(File file, Map<String, String> formData, String boundary,
									 String partContentType = 'application/octet-stream',
									 String partName = 'file') {
		def fileParts = (file == null) ? [] :
			[[name: partName, filename: file.name, contentType: partContentType, bytes: file.bytes]]
		return buildMultipartBody(formData, fileParts, boundary)
	}

	/**
	 * Builds a multipart body from raw file-part descriptors, for the shapes the file-plus-form-fields
	 * helpers cannot express: several file parts, or a part whose filename differs from the file's name.
	 * Each descriptor is a map of [name:, filename:, contentType:, bytes:].
	 */
	def static buildMultipartBody(Map<String, String> formData, List<Map> fileParts, String boundary) {
		def os = new ByteArrayOutputStream()

		formData.each { name, value ->
			os.write("--${boundary}\r\n".bytes)
			os.write("Content-Disposition: form-data; name=\"${name}\"\r\n\r\n".bytes)
			os.write("${value}\r\n".bytes)
		}

		fileParts.each { part ->
			assert part.name != null && part.filename != null && part.bytes != null:
				"file part descriptor needs name, filename and bytes, got: " + part
			os.write("--${boundary}\r\n".bytes)
			os.write("Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.filename}\"\r\n".bytes)
			os.write("Content-Type: ${part.contentType ?: 'application/octet-stream'}\r\n\r\n".bytes)
			os.write(part.bytes as byte[])
			os.write("\r\n".bytes)
		}
		os.write("--${boundary}--\r\n".bytes)

		return os.toByteArray()
	}

}
