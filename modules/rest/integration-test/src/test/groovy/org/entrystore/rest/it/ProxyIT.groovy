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

package org.entrystore.rest.it

import com.sun.net.httpserver.HttpServer
import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ProxyIT extends BaseSpec {

	static HttpServer mockServer
	static String mockOrigin

	def setupSpec() {
		// Start a lightweight mock HTTP server for proxy target
		mockServer = HttpServer.create(new InetSocketAddress('localhost', 0), 0)
		mockOrigin = 'http://localhost:' + mockServer.address.port

		mockServer.createContext('/api/data') { exchange ->
			def body = '{"key":"value"}'
			exchange.responseHeaders.set('Content-Type', 'application/json')
			exchange.sendResponseHeaders(200, body.bytes.length)
			exchange.responseBody.write(body.bytes)
			exchange.responseBody.close()
		}

		mockServer.createContext('/page') { exchange ->
			def body = '<html><body>Hello</body></html>'
			exchange.responseHeaders.set('Content-Type', 'text/html')
			exchange.sendResponseHeaders(200, body.bytes.length)
			exchange.responseBody.write(body.bytes)
			exchange.responseBody.close()
		}

		mockServer.createContext('/redirect') { exchange ->
			exchange.responseHeaders.set('Location', mockOrigin + '/api/data')
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}

		mockServer.createContext('/echo-accept') { exchange ->
			def acceptHeader = exchange.requestHeaders.getFirst('Accept') ?: ''
			exchange.responseHeaders.set('Content-Type', 'text/plain')
			exchange.sendResponseHeaders(200, acceptHeader.bytes.length)
			exchange.responseBody.write(acceptHeader.bytes)
			exchange.responseBody.close()
		}

		mockServer.createContext('/redirect-relative') { exchange ->
			exchange.responseHeaders.set('Location', '/api/data')
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}

		mockServer.createContext('/redirect-blacklisted') { exchange ->
			exchange.responseHeaders.set('Location', 'http://192.168.1.1/')
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}

		mockServer.createContext('/redirect-ftp') { exchange ->
			exchange.responseHeaders.set('Location', 'ftp://files.example.com/data')
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}

		mockServer.start()
		log.info('Mock HTTP server started on {}', mockOrigin)
	}

	def cleanupSpec() {
		if (mockServer != null) {
			mockServer.stop(0)
		}
	}

	// --- Global /proxy - parameter validation ---

	def 'GET /proxy without url param should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// The four tests below cover one SsrfValidator.parseAndValidateUrl rejection branch each:
	// unparseable URI, absent scheme, disallowed scheme, and absent host.

	def 'GET /proxy with malformed URL should return 400'() {
		when:
		// Unterminated IPv6 literal — the only input here that URI's constructor rejects outright
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://[::1']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with scheme-less URL should return 400'() {
		when:
		// Parses as a valid relative URI, so it is the absent-scheme branch rather than malformed
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'not-a-url']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with file:// URL should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'file:///etc/passwd']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with URL missing host should return 400'() {
		when:
		// Allowed scheme with an empty authority, so validation reaches the host check
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http:///etc/passwd']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// --- Global /proxy - authentication/authorization ---

	def 'GET /proxy as guest to non-whitelisted host should return 401'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']), '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def 'GET /proxy as non-admin user should return proxied content'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']), 'user')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.inputStream.text == '{"key":"value"}'
	}

	def 'GET /proxy as admin should return proxied content'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.inputStream.text == '{"key":"value"}'
	}

	// --- Global /proxy - blacklist (SSRF protection) ---

	def 'GET /proxy to bare IPv4 address should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://192.168.1.1/test']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to .local domain should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://myhost.local/test']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to IPv6 address should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://[::1]/test']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to numeric IPv4 representation should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://2130706433/test']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to unresolvable host should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://definitely-not-a-real-host-xyz123.invalid/test']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	// --- Global /proxy - successful proxy behavior ---

	def 'GET /proxy should include Content-Security-Policy header'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Content-Security-Policy') == "script-src 'none'; form-action 'none';"
	}

	def 'GET /proxy should forward Accept header to upstream'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/echo-accept']), 'admin', 'text/html')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.text == 'text/html'
	}

	def 'GET /proxy should follow redirects'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/redirect']))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.inputStream.text == '{"key":"value"}'
	}

	// --- Context-scoped /{context-id}/proxy ---

	def 'GET /{context-id}/proxy with non-existent context should return 404'() {
		when:
		def conn = EntryStoreClient.getRequest('/999/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']))

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def 'GET /{context-id}/proxy as guest should return 404 (avoiding context-existence enumeration)'() {
		given:
		getOrCreateContext([contextId: 'proxy-guest'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-guest/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']), '')

		then:
		// Guests get 404 (not 401) so they cannot distinguish "context exists but is private" from "context does not exist"
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def 'GET /{context-id}/proxy as non-admin user without context access should return 403'() {
		given:
		getOrCreateContext([contextId: 'proxy-nonadmin'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-nonadmin/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']), 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /{context-id}/proxy as admin should return proxied content'() {
		given:
		getOrCreateContext([contextId: 'proxy-admin'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-admin/proxy' + convertMapToQueryParams([url: mockOrigin + '/api/data']))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.inputStream.text == '{"key":"value"}'
	}

	// --- URL scheme validation ---

	def 'GET /proxy with ftp:// URL should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'ftp://files.example.com/data']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with gopher:// URL should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'gopher://example.com/1']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with data: URL should return 400'() {
		when:
		// Kept free of URI-illegal characters so this exercises scheme rejection, not malformed-URI
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'data:text/plain,test']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// --- Userinfo rejection ---

	def 'GET /proxy with URL containing userinfo should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: 'http://user:pass@example.com/']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// --- Redirect security ---

	def 'GET /proxy with redirect to blacklisted host should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/redirect-blacklisted']))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy with redirect to disallowed scheme should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/redirect-ftp']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy should follow relative redirects'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: mockOrigin + '/redirect-relative']))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.inputStream.text == '{"key":"value"}'
	}
}
