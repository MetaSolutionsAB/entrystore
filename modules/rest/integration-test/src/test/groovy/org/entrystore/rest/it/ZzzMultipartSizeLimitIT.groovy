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
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzMultipartSizeLimitIT extends BaseSpec {

	static final String contextId = '666'

	// Marker carried in Jetty 12's error HTML body when its multipart parser rejects a request
	// for breaching the configured cap (org.eclipse.jetty.http.BadMessageException: 400: bad
	// multipart). Asserting on this marker pins the rejection to the cap path rather than the
	// raw 400 status, which any unrelated 4xx could produce.
	static final String JETTY_BAD_MULTIPART_MARKER = 'bad multipart'

	def setupSpec() {
		stopPreexistingAppIfRunning()

		// Caps are intentionally tiny so that all request bodies stay below EntryStoreClient's
		// 8000-byte chunked-streaming threshold (otherwise a server-side rejection mid-write
		// closes the connection before the client can read the response code). max-file-size
		// and max-request-size are deliberately *different* so a regression that wires only
		// one of the two properties fails the cap it leaves unbound.
		startOwnedApp([
			'--spring.servlet.multipart.max-file-size=2KB',
			'--spring.servlet.multipart.max-request-size=4KB'
		])
	}

	// Intentionally no cleanupSpec — matches the canonical pattern of ZzzCasLoginIT and
	// ZzzSamlLoginIT. The next lifecycle-owning IT's stopPreexistingAppIfRunning() closes
	// our appInstance; resetting appInstance=null or appStarted=false here would violate
	// BaseSpec invariant #2 (see the invariant comment above appStarted in BaseSpec).

	// Jetty 12 enforces spring.servlet.multipart.max-file-size / max-request-size at the
	// container layer and rejects oversize requests with BadMessageException ("400: bad
	// multipart") *before* Spring's DispatcherServlet sees them, so AppExceptionHandler cannot
	// remap to a more semantically-correct 413. Tests below assert the current Jetty contract
	// (status 400 + body containing the "bad multipart" marker) — that pins the rejection to
	// the cap path rather than the raw status code.
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
		conn.getResponseCode() == HTTP_BAD_REQUEST
		errorBody.contains(JETTY_BAD_MULTIPART_MARKER)

		when: 'the resource is fetched afterwards'
		def getConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then: 'the rejected upload never persisted any bytes — the resource is still empty (204 No Content)'
		getConn.getResponseCode() == HTTP_NO_CONTENT
	}

	def "PUT /{context-id}/resource/{entry-id} multipart upload above max-request-size cap is rejected"() {
		given:
		getOrCreateContext([contextId: contextId])
		def entryId = getOrCreateEntry(contextId, [id: 'requestCapId'], [resource: [name: 'Request-cap entry']])
		assert entryId.length() > 0

		// 512 B file is under max-file-size (2 KB); 4 KB of form-field padding pushes the
		// total multipart body well over max-request-size (4 KB) but still under the 8000-byte
		// chunked-streaming threshold, so only max-request-size can produce the rejection.
		def payload = new byte[512]
		new Random(7).nextBytes(payload)
		def smallFile = createTempBinaryFile('under-file-cap', '.bin', payload)
		def formData = [padding: 'x' * (4 * 1024)]

		when:
		def conn = EntryStoreClient.putRequestMultiPart(
				'/' + contextId + '/resource/' + entryId, smallFile, 'admin', formData)
		def errorBody = readErrorBody(conn)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		errorBody.contains(JETTY_BAD_MULTIPART_MARKER)

		when: 'the resource is fetched afterwards'
		def getConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then: 'the rejected upload never persisted any bytes — the resource is still empty (204 No Content)'
		getConn.getResponseCode() == HTTP_NO_CONTENT
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

	def "POST /{context-id}/import multipart upload above max-file-size cap is rejected"() {
		given:
		getOrCreateContext([contextId: contextId])
		// Snapshot the entry-id listing before the import so we can prove no entries were
		// created by the rejected request (the controller never runs, but a future change
		// to the rejection path that side-effects on storage would slip through otherwise).
		def entriesBefore = listContextEntryIds(contextId)

		// 3 KB payload — does not need to be a valid zip, the cap fires before the controller runs.
		def payload = new byte[3 * 1024]
		new Random(42).nextBytes(payload)
		def overCapZip = createTempBinaryFile('over-cap', '.zip', payload)

		when:
		def conn = EntryStoreClient.postRequestMultiPart('/' + contextId + '/import', overCapZip)
		def errorBody = readErrorBody(conn)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		errorBody.contains(JETTY_BAD_MULTIPART_MARKER)

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
