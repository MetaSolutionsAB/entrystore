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
import spock.lang.Unroll

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE

class EchoIT extends BaseSpec {

	// Not valid multipart: a handler that parses it answers 400, so any other status shows it was not parsed.
	static final String UNPARSEABLE_MULTIPART_TYPE = 'multipart/form-data; boundary=never-sent'
	static final String UNPARSEABLE_MULTIPART_BODY = 'no boundary in here'

	def 'POST /echo multipart as guest asking for JSON is rejected without parsing the upload'() {
		when: 'Accept: application/json routes the request to POST /{context-id}, which takes no upload'
		def echoConn = EntryStoreClient.postRequest('/echo', UNPARSEABLE_MULTIPART_BODY, '',
			UNPARSEABLE_MULTIPART_TYPE, ['Accept': 'application/json'])

		then:
		echoConn.getResponseCode() == HTTP_UNSUPPORTED_TYPE
	}

	def 'POST /echo multipart as guest asking for XML is rejected without parsing the upload'() {
		when:
		def echoConn = EntryStoreClient.postRequest('/echo', UNPARSEABLE_MULTIPART_BODY, '',
			UNPARSEABLE_MULTIPART_TYPE, ['Accept': 'application/xml'])

		then:
		echoConn.getResponseCode() == HTTP_UNSUPPORTED_TYPE
	}

	def 'POST /echo with a malformed multipart body answers 400 in a textarea, as 5.x did'() {
		when:
		def echoConn = EntryStoreClient.postRequest('/echo', UNPARSEABLE_MULTIPART_BODY, 'admin', UNPARSEABLE_MULTIPART_TYPE)

		then:
		echoConn.getResponseCode() == HTTP_BAD_REQUEST
		echoConn.errorStream.text.contains('<textarea>status:400\nMalformed multipart request</textarea>')
	}

	def 'POST /echo multipart as guest is refused before the upload is parsed'() {
		when:
		def echoConn = EntryStoreClient.postRequest('/echo', UNPARSEABLE_MULTIPART_BODY, '', UNPARSEABLE_MULTIPART_TYPE)

		then:
		echoConn.getResponseCode() == HTTP_FORBIDDEN
	}

	def 'POST /echo as guest should respond with FORBIDDEN 403'() {
		given:
		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('echoTest', '.bin', 'Hello, its me! Mario!'.bytes)

		when:
		def echoConn = EntryStoreClient.postRequestMultiPart('/echo', testBinFile, '')

		then:
		echoConn.getResponseCode() == HTTP_FORBIDDEN
		echoConn.getContentType().contains('text/html')
		echoConn.errorStream.text.contains('<textarea>status:403\nGuest account is not allowed to use /echo endpoint.</textarea>')
	}

	@Unroll
	def 'POST /echo as #user with multi-part file should respond with the file contents as string in html textarea'() {
		given:
		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('echoTest', '.bin', 'Hello, its me! Mario!'.bytes)

		when:
		def echoConn = EntryStoreClient.postRequestMultiPart('/echo', testBinFile, user)

		then:
		echoConn.getResponseCode() == HTTP_OK
		echoConn.getContentType().contains('text/html')
		echoConn.inputStream.text.contains('<textarea>status:200\nHello, its me! Mario!</textarea>')

		where:
		user << ['user', 'userInAdminGroup', 'admin']
	}

	def 'POST /echo as admin with multi-part file should return the file contents as string with escaped html chars'() {
		given:
		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('echoTest', '.bin', 'Hello, its me! <b>bold</b> Mario and a hash tag # & !'.bytes)

		when:
		def echoConn = EntryStoreClient.postRequestMultiPart('/echo', testBinFile)

		then:
		echoConn.getResponseCode() == HTTP_OK
		echoConn.getContentType().contains('text/html')
		echoConn.inputStream.text.contains('<textarea>status:200\nHello, its me! &lt;b&gt;bold&lt;/b&gt; Mario and a hash tag # &amp; !</textarea>')
	}

	def 'POST /echo as admin with a multi-part file not named "file" should respond with the file contents'() {
		given:
		def testBinFile = createTempBinaryFile('echoTest', '.bin', 'Hello, its me! Mario!'.bytes)

		when: 'the file is sent in a part named "0" instead of "file"'
		def echoConn = EntryStoreClient.postRequestMultiPart('/echo', testBinFile, 'admin', [:], 'application/octet-stream', '0')

		then:
		echoConn.getResponseCode() == HTTP_OK
		echoConn.getContentType().contains('text/html')
		echoConn.inputStream.text.contains('<textarea>status:200\nHello, its me! Mario!</textarea>')
	}

	def 'POST /echo as admin with a multi-part request carrying no file part should respond with BAD_REQUEST 400'() {
		when:
		def echoConn = EntryStoreClient.postRequestMultiPartWithoutFile('/echo', [someField: 'someValue'])

		then:
		echoConn.getResponseCode() == HTTP_BAD_REQUEST
		echoConn.getContentType().contains('text/html')
		echoConn.errorStream.text.contains('<textarea>status:400\nMissing file part in the request</textarea>')
	}

	def 'POST /echo as admin with content other than multi-part file should respond with UNSUPPORTED_TYPE 415'() {
		when:
		def echoConn = EntryStoreClient.postRequest('/echo')  // sends empty json body by default

		then:
		echoConn.getResponseCode() == HTTP_UNSUPPORTED_TYPE
		echoConn.getContentType().contains('text/html')
		echoConn.errorStream.text.contains('<textarea>status:415\n/echo endpoint accepts only &#39;multipart/form-data&#39; requests</textarea>')
	}

	def 'POST /echo as admin with multi-part file larger than 10MB should respond with HTTP_ENTITY_TOO_LARGE 413'() {
		given:
		// create a test binary file with 11MB of some data
		def testBinFile = File.createTempFile('echoTest', '.bin')
		testBinFile.deleteOnExit()
		testBinFile.withOutputStream { out ->
			byte[] buffer = new byte[1024 * 1024] // 1MB buffer
			(0..<11).each { // 11 iterations of 1MB buff
				out.write(buffer)
			}
		}

		when:
		def echoConn = EntryStoreClient.postRequestMultiPart('/echo', testBinFile)

		then:
		echoConn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
		echoConn.getContentType().contains('text/html')
		echoConn.errorStream.text.contains('<textarea>status:413\nReceived file size (of 11534336B) exceeds maximum allowed size of: 10485760B</textarea>')
	}
}
