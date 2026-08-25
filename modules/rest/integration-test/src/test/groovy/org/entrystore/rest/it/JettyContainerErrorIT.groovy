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

import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST

/**
 * Verifies that errors rendered by Jetty's container-level error handlers are sanitized
 * (ENTRYSTORE-1098). These errors bypass AppExceptionHandler: an unparseable form body blows up
 * inside the servlet filter chain (CheckUsernamePasswordFilter calls getParameter() on every
 * request), and a malformed request line or header never reaches the servlet context at all.
 * Jetty's default error bodies leak the raw connector-level request URL (internal host and
 * port behind a reverse proxy), the servlet name ("origin") and the internal exception message.
 */
class JettyContainerErrorIT extends BaseSpec {

	static final String UNPARSEABLE_FORM_BODY = 'auth_username=%zz&auth_password=x'

	def "POST with unparseable form body should return sanitized 400 JSON without internal URL"() {
		when:
		def conn = EntryStoreClient.postRequest('/search?type=solr&query=*:*', UNPARSEABLE_FORM_BODY,
				'admin', 'application/x-www-form-urlencoded', ['Accept': 'application/json'])

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def body = conn.errorStream.text
		def resp = JSON_PARSER.parseText(body)
		resp['status'] == 400
		resp['error'] == 'Bad Request'
		resp['timestamp'] != null
		resp['path'] == '/search'
		// Jetty's default error body leaked all of these before the sanitized handlers were installed
		!body.contains('"url"')
		!body.contains('"origin"')
		!body.contains('"message"')
		!body.contains('http://')
		!body.contains('dispatcherServlet')
		!body.contains('Unable to parse form content')
	}

	def "POST with unparseable form body and Accept text/html should return sanitized HTML error page"() {
		when:
		def conn = EntryStoreClient.postRequest('/search?type=solr&query=*:*', UNPARSEABLE_FORM_BODY,
				'admin', 'application/x-www-form-urlencoded', ['Accept': 'text/html'])

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('text/html')
		def body = conn.errorStream.text
		body.contains('HTTP ERROR 400 Bad Request')
		!body.contains('http://')
		!body.contains('URI:')
		!body.contains('MESSAGE')
		!body.contains('dispatcherServlet')
		!body.contains('Unable to parse form content')
	}

	def "POST with unparseable form body and Accept text/plain should return sanitized plain-text error"() {
		when:
		def conn = EntryStoreClient.postRequest('/search?type=solr&query=*:*', UNPARSEABLE_FORM_BODY,
				'admin', 'application/x-www-form-urlencoded', ['Accept': 'text/plain'])

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('text/plain')
		def body = conn.errorStream.text
		body.contains('HTTP ERROR 400 Bad Request')
		body.contains('PATH: /search')
		!body.contains('http://')
		!body.contains('dispatcherServlet')
		!body.contains('Unable to parse form content')
	}

	def "request with malformed header should return sanitized 400 from the server-level error handler"() {
		given: 'a raw socket, since HttpURLConnection cannot send a malformed header line'
		def socket = new Socket(EntryStoreClient.host, EntryStoreClient.port)

		when:
		String response
		socket.withCloseable {
			socket.outputStream.write((
					'GET /store/search HTTP/1.1\r\n' +
					'Host: internal-host:9999\r\n' +
					'Accept: application/json\r\n' +
					'HeaderLineWithoutColon\r\n' +
					'\r\n').getBytes('ISO-8859-1'))
			socket.outputStream.flush()
			response = socket.inputStream.getText('ISO-8859-1')
		}

		then:
		response.startsWith('HTTP/1.1 400')
		!response.contains('"url"')
		!response.contains('"origin"')
		!response.contains('http://')
		!response.contains('URI:')
	}
}
