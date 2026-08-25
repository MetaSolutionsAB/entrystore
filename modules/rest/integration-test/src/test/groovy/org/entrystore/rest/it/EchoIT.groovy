package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import spock.lang.Unroll

import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE

class EchoIT extends BaseSpec {

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
	def 'POST /echo as "#user" with multi-part file should respond with the file contents as string in html textarea'() {
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
