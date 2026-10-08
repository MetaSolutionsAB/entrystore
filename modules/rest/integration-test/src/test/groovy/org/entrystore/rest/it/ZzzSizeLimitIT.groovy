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

import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

// Configured size limits on uploads and on proxied responses, sharing one app start.
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSizeLimitIT extends BaseSpec {

	static final String contextId = '666'

	// Above Jetty's 32 KiB output buffer, so a body cut off at the cap has already been committed.
	static final int PROXY_CAP = 64 * 1024

	// Applies to raw PUT bodies; above the 1 KB multipart upload below, which must still succeed.
	static final int DATA_CAP = 1536

	// Far above DATA_CAP, but within the 4 MB the server reads and discards so that a client that reads the
	// response only after sending its whole body sees the 413 rather than a connection reset.
	static final int OVERSIZED_BODY = 3 * 1024 * 1024

	static HttpServer upstream
	static String upstreamOrigin

	def setupSpec() {
		upstream = HttpServer.create(new InetSocketAddress('localhost', 0), 0)
		upstreamOrigin = 'http://localhost:' + upstream.address.port
		upstream.createContext('/at-cap') { exchange -> respond(exchange, PROXY_CAP, PROXY_CAP) }
		upstream.createContext('/over-cap-with-length') { exchange -> respond(exchange, PROXY_CAP + 1, PROXY_CAP + 1) }
		// Length 0 makes the JDK server send the body chunked, without Content-Length.
		upstream.createContext('/over-cap-chunked') { exchange -> respond(exchange, 0, 1024 * 1024) }
		upstream.start()

		stopPreexistingAppIfRunning()

		// Caps are intentionally tiny so that the max-file-size requests stay below EntryStoreClient's
		// 8000-byte chunked-streaming threshold (otherwise the rejection mid-write closes the
		// connection before the client can read the response code). The request shape succeeds below
		// both caps and fails above exactly one, which pins each rejection to its cap; the two caps are
		// deliberately *different* so a regression that wires only one property fails the cap it leaves unbound.
		startOwnedApp([
			'--spring.servlet.multipart.max-file-size=2KB',
			'--spring.servlet.multipart.max-request-size=4KB',
			'--entrystore.data.max-file-size=' + DATA_CAP,
			'--entrystore.proxy.max-response-size=' + PROXY_CAP + 'B'
		])
	}

	// Stops only the upstream mock. The app stays up until the next lifecycle-owning IT's
	// stopPreexistingAppIfRunning() closes it; resetting appInstance or appStarted here would violate
	// BaseSpec invariant #2 (see the invariant comment above appStarted in BaseSpec).
	def cleanupSpec() {
		upstream?.stop(0)
	}

	private static void respond(exchange, long announcedLength, int bodySize) {
		// JSON, because Spring would append its JSON error body to a committed JSON response.
		exchange.responseHeaders.set('Content-Type', 'application/json')
		exchange.sendResponseHeaders(200, announcedLength)
		try {
			exchange.responseBody.write(new byte[bodySize])
		} catch (IOException ignored) {
			// The proxy stops reading once the cap is exceeded.
		} finally {
			exchange.close()
		}
	}

	def "GET /proxy streams an upstream body exactly at entrystore.proxy.max-response-size"() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: upstreamOrigin + '/at-cap']), 'admin', '*/*')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.bytes.length == PROXY_CAP
	}

	def "GET /proxy answers 502 up front when the upstream Content-Length exceeds entrystore.proxy.max-response-size"() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: upstreamOrigin + '/over-cap-with-length']), 'admin', '*/*')

		then:
		conn.getResponseCode() == HTTP_BAD_GATEWAY
		JSON_PARSER.parseText(conn.errorStream.text).error == "Upstream response exceeds maximum allowed size of ${PROXY_CAP} bytes"
	}

	def "GET /proxy aborts the transfer when an upstream body without Content-Length exceeds entrystore.proxy.max-response-size"() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy' + convertMapToQueryParams([url: upstreamOrigin + '/over-cap-chunked']), 'admin', '*/*')
		def status = conn.getResponseCode()
		long received = 0
		conn.inputStream.withCloseable { body ->
			def buf = new byte[8192]
			int read
			while ((read = body.read(buf)) != -1) {
				received += read
			}
		}

		then: 'the client sees a broken transfer, not a complete body that is silently truncated'
		status == HTTP_OK
		thrown(IOException)
		received <= PROXY_CAP
	}

	// A Content-Length above max-request-size is answered 413 by MultipartRequestFilter before the
	// body is read. A part above max-file-size is found while the controller parses the upload, after the
	// access check, and AppExceptionHandler answers 413 as well.
	private static String readErrorBody(HttpURLConnection conn) {
		// Force the response to be read before grabbing the error stream — without first
		// calling getResponseCode(), HttpURLConnection may return null for getErrorStream()
		// even when the server sent an error body.
		conn.getResponseCode()
		def stream = conn.getErrorStream()
		return stream != null ? new String(stream.readAllBytes()) : ''
	}

	def "PUT /{context-id}/resource/{entry-id} multipart upload above max-file-size cap is rejected and leaves the resource empty"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'fileCapId'], [resource: [name: 'File-cap entry']])
		assert entryId.length() > 0

		// 3 KB file: exceeds max-file-size (2 KB) but the full multipart body stays under
		// max-request-size (4 KB), so the rejection is pinned to the file-size cap specifically.
		def payload = new byte[3 * 1024]
		new Random(42).nextBytes(payload)
		def overCapFile = createTempBinaryFile('over-file-cap', '.bin', payload)

		when:
		def conn = EntryStoreClient.putRequestMultiPart('/' + contextId + '/resource/' + entryId, overCapFile)
		def errorBody = readErrorBody(conn)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(errorBody).error == 'Multipart upload exceeds the maximum allowed size'

		when: 'the resource is fetched afterwards'
		def getConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then: 'the rejected upload never persisted any bytes — the resource is still empty (204 No Content)'
		getConn.getResponseCode() == HTTP_NO_CONTENT
	}

	def "PUT /{context-id}/resource/{entry-id} multipart upload with a Content-Length above max-request-size is answered 413"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'contentLengthCapId'], [resource: [name: 'Content-Length cap entry']])

		when: 'the body is still being sent when the server answers, so the server must read it for the client to see the 413'
		def conn = EntryStoreClient.putRequestMultiPartStreamed('/' + contextId + '/resource/' + entryId, 3L * 1024 * 1024)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(readErrorBody(conn)).error.startsWith('Request size of ')

		when: 'the resource is fetched afterwards'
		def getConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then: 'nothing was stored'
		getConn.getResponseCode() == HTTP_NO_CONTENT
	}

	def "PUT /{context-id}/resource/{entry-id} chunked multipart upload above max-request-size is answered 413"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'chunkedCapId'], [resource: [name: 'Chunked cap entry']])

		// 512 B file under max-file-size (2 KB), 4 KB of form-field padding over max-request-size (4 KB).
		def boundary = 'chunked-boundary'
		def body = ("--${boundary}\r\nContent-Disposition: form-data; name=\"padding\"\r\n\r\n${'x' * 4096}\r\n" +
				"--${boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n" +
				"Content-Type: application/octet-stream\r\n\r\n${'y' * 512}\r\n--${boundary}--\r\n").bytes

		when: 'without Content-Length the limit is only found while the upload is parsed'
		def conn = EntryStoreClient.createConnection('/' + contextId + '/resource/' + entryId)
		conn.setRequestMethod('PUT')
		conn.setRequestProperty('Cookie', EntryStoreClient.cookieHeader('admin'))
		conn.setRequestProperty('Content-Type', 'multipart/form-data; boundary=' + boundary)
		conn.setDoOutput(true)
		conn.setChunkedStreamingMode(1024)
		conn.outputStream.withStream { it.write(body) }

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(readErrorBody(conn)).error == 'Multipart upload exceeds the maximum allowed size'
	}

	def "PUT /{context-id}/resource/{entry-id} multipart upload below both caps succeeds"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'underCapFileId'], [resource: [name: 'Under-cap file entry']])
		assert entryId.length() > 0

		def payload = new byte[1024]
		new Random(7).nextBytes(payload)
		def underCapFile = createTempBinaryFile('under-cap', '.bin', payload)

		when:
		def conn = EntryStoreClient.putRequestMultiPart('/' + contextId + '/resource/' + entryId, underCapFile)

		then:
		conn.getResponseCode() == HTTP_CREATED

		when: 'the uploaded resource is fetched afterwards'
		def getConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then: 'the bytes round-trip identically — proves the success path is not silently truncating'
		getConn.getResponseCode() == HTTP_OK
		getConn.getInputStream().readAllBytes() == payload
	}

	def "PUT /{context-id}/resource/{entry-id} raw upload exactly at entrystore.data.max-file-size succeeds without Content-Length"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'rawAtCapId'], [resource: [name: 'Raw at-cap entry']])

		when:
		def conn = EntryStoreClient.putRequestStreamed('/' + contextId + '/resource/' + entryId, DATA_CAP, true)

		then:
		conn.getResponseCode() == HTTP_CREATED
		storedSize(entryId) == DATA_CAP
	}

	def "PUT /{context-id}/resource/{entry-id} raw upload with a Content-Length above entrystore.data.max-file-size is answered 413 and keeps the previous file"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'rawOverCapId'], [resource: [name: 'Raw over-cap entry']])
		assert EntryStoreClient.putRequestStreamed('/' + contextId + '/resource/' + entryId, 1024).getResponseCode() == HTTP_CREATED

		when: 'the client sends all of a body far above the cap before it reads the response'
		def conn = EntryStoreClient.putRequestStreamed('/' + contextId + '/resource/' + entryId, OVERSIZED_BODY)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(readErrorBody(conn)).error ==
				"Received file size (of ${OVERSIZED_BODY}b) exceeds maximum allowed size of: ${DATA_CAP}b"
		storedSize(entryId) == 1024
	}

	def "PUT /{context-id}/resource/{entry-id} chunked raw upload above entrystore.data.max-file-size is answered 413 and keeps the previous file"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'rawChunkedOverCapId'], [resource: [name: 'Raw chunked over-cap entry']])
		assert EntryStoreClient.putRequestStreamed('/' + contextId + '/resource/' + entryId, 1024).getResponseCode() == HTTP_CREATED

		when: 'without Content-Length the limit is only found while the body is stored, and the client sends on'
		def conn = EntryStoreClient.putRequestStreamed('/' + contextId + '/resource/' + entryId, OVERSIZED_BODY, true)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(readErrorBody(conn)).error == "Received file exceeds maximum allowed size of: ${DATA_CAP}b"
		storedSize(entryId) == 1024
	}

	def "PUT /{context-id}/resource/{entry-id} raw upload with Expect: 100-continue and a Content-Length above entrystore.data.max-file-size is answered 413 instead of 100 Continue"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'rawExpectOverCapId'], [resource: [name: 'Raw expect over-cap entry']])

		expect:
		EntryStoreClient.firstStatusOfPutExpectingContinue('/' + contextId + '/resource/' + entryId, 'admin',
				DATA_CAP + 1) == HTTP_ENTITY_TOO_LARGE
	}

	def "PUT /{context-id}/resource/{entry-id} form-urlencoded raw upload with Expect: 100-continue and a Content-Length above entrystore.data.max-file-size is answered 413 instead of 100 Continue"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'rawFormOverCapId'], [resource: [name: 'Raw form over-cap entry']])

		expect:
		EntryStoreClient.firstStatusOfPutExpectingContinue('/' + contextId + '/resource/' + entryId, 'admin',
				DATA_CAP + 1, 'application/x-www-form-urlencoded') == HTTP_ENTITY_TOO_LARGE
	}

	private static long storedSize(String entryId) {
		def conn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)
		assert conn.getResponseCode() == HTTP_OK
		return conn.inputStream.bytes.length
	}

	def "POST /echo multipart upload above max-file-size cap answers 413 in a textarea, as 5.x did"() {
		given:
		def overCapFile = createTempBinaryFile('echo-over-cap', '.bin', ('x' * (3 * 1024)).bytes)

		when:
		def conn = EntryStoreClient.postRequestMultiPart('/echo', overCapFile)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		readErrorBody(conn).contains('<textarea>status:413\nMultipart upload exceeds the maximum allowed size</textarea>')
	}

	def "POST /echo multipart upload with a Content-Length above max-request-size answers 413 in a textarea, as 5.x did"() {
		given: 'a 5 KB file: the request exceeds max-request-size (4 KB), which is checked before the body is parsed'
		def overCapFile = createTempBinaryFile('echo-over-request-cap', '.bin', ('x' * (5 * 1024)).bytes)

		when:
		def conn = EntryStoreClient.postRequestMultiPart('/echo', overCapFile)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		conn.getContentType().startsWith('text/html')
		readErrorBody(conn).startsWith('<textarea>status:413\nRequest size of ')
	}

	def "POST /{context-id}/import multipart upload above max-file-size cap is rejected"() {
		given:
		getOrCreateContext([contextId: contextId])
		// Snapshot the entry-id listing before the import so we can prove no entries were
		// created by the rejected request (the import never starts, but a future change
		// to the rejection path that side-effects on storage would slip through otherwise).
		def entriesBefore = listContextEntryIds(contextId)

		// 3 KB payload — does not need to be a valid zip, the cap fires while the upload is parsed.
		def payload = new byte[3 * 1024]
		new Random(42).nextBytes(payload)
		def overCapZip = createTempBinaryFile('over-cap', '.zip', payload)

		when:
		def conn = EntryStoreClient.postRequestMultiPart('/' + contextId + '/import', overCapZip)
		def errorBody = readErrorBody(conn)

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		JSON_PARSER.parseText(errorBody).error == 'Multipart upload exceeds the maximum allowed size'

		when: 'the entry listing is re-fetched afterwards'
		def entriesAfter = listContextEntryIds(contextId)

		then: 'the rejected import created no new entries'
		entriesAfter == entriesBefore
	}

	private static List<String> listContextEntryIds(String contextId) {
		def conn = EntryStoreClient.getRequest('/' + contextId)
		assert conn.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(conn.getInputStream().text) as List<String>
	}
}
