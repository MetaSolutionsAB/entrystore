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
	static int mockPort

	def setupSpec() {
		// Start a lightweight mock HTTP server for proxy target
		mockServer = HttpServer.create(new InetSocketAddress('localhost', 0), 0)
		mockPort = mockServer.address.port

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
			exchange.responseHeaders.set('Location', "http://localhost:${mockPort}/api/data")
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

		mockServer.start()
		log.info('Mock HTTP server started on port {}', mockPort)
	}

	def cleanupSpec() {
		if (mockServer != null) {
			mockServer.stop(0)
		}
	}

	private String mockUrl(String path) {
		return URLEncoder.encode("http://localhost:${mockPort}${path}", 'UTF-8')
	}

	// --- Global /proxy - parameter validation ---

	def 'GET /proxy without url param should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with malformed URL should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('not-a-url', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def 'GET /proxy with URL missing host should return 400'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('file:///etc/passwd', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	// --- Global /proxy - authentication/authorization ---

	def 'GET /proxy as guest to non-whitelisted host should return 401'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/api/data'), '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def 'GET /proxy as non-admin user should return proxied content'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/api/data'), 'user')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.getInputStream().text == '{"key":"value"}'
	}

	def 'GET /proxy as admin should return proxied content'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/api/data'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.getInputStream().text == '{"key":"value"}'
	}

	// --- Global /proxy - blacklist (SSRF protection) ---

	def 'GET /proxy to bare IPv4 address should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('http://192.168.1.1/test', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to .local domain should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('http://myhost.local/test', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to IPv6 address should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('http://[::1]/test', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to numeric IPv4 representation should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('http://2130706433/test', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /proxy to unresolvable host should return 403'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + URLEncoder.encode('http://definitely-not-a-real-host-xyz123.invalid/test', 'UTF-8'))

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	// --- Global /proxy - successful proxy behavior ---

	def 'GET /proxy as admin should return JSON from mock server'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/api/data'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.getInputStream().text)
		responseJson['key'] == 'value'
	}

	def 'GET /proxy should include Content-Security-Policy header'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/api/data'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Content-Security-Policy') == "script-src 'none'; form-action 'none';"
	}

	def 'GET /proxy should forward Accept header to upstream'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/echo-accept'), 'admin', 'text/html')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getInputStream().text == 'text/html'
	}

	def 'GET /proxy should follow redirects'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + mockUrl('/redirect'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.getInputStream().text == '{"key":"value"}'
	}

	// --- Context-scoped /{context-id}/proxy ---

	def 'GET /{context-id}/proxy with non-existent context should return 404'() {
		when:
		def conn = EntryStoreClient.getRequest('/999/proxy?url=' + mockUrl('/api/data'))

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def 'GET /{context-id}/proxy as guest should return 401'() {
		given:
		getOrCreateContext([contextId: 'proxy-guest'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-guest/proxy?url=' + mockUrl('/api/data'), '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def 'GET /{context-id}/proxy as non-admin user without context access should return 403'() {
		given:
		getOrCreateContext([contextId: 'proxy-nonadmin'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-nonadmin/proxy?url=' + mockUrl('/api/data'), 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'GET /{context-id}/proxy as admin should return proxied content'() {
		given:
		getOrCreateContext([contextId: 'proxy-admin'])

		when:
		def conn = EntryStoreClient.getRequest('/proxy-admin/proxy?url=' + mockUrl('/api/data'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.getInputStream().text == '{"key":"value"}'
	}
}
