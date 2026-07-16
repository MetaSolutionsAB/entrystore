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

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_PARTIAL
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static java.nio.charset.StandardCharsets.UTF_8
import static org.entrystore.rest.springboot.filter.CacheControlFilter.CACHE_CONTROL_AUTHENTICATED

/**
 * Entry and resource GETs carry Last-Modified from the entry's modification date and an ETag, and answer conditional
 * requests with 304, and HEAD on an entry answers with those headers, as in 5.x. The ETag is that date where the
 * representation depends on the entry alone, and a hash of the body where it embeds other entries or the caller's
 * rights. Metadata GETs carry an ETag that also covers the representation (media type and representation-affecting
 * parameters), so If-None-Match revalidates only the exact representation the client holds, and authorization
 * always precedes the 304 decision.
 */
class ConditionalGetIT extends BaseSpec {

	private static final String CONTEXT_ID = 'conditional-get-it-ctx'

	private static final String DATE_ETAG = /"\d+"/

	private static final String CONTENT_ETAG = /"[0-9a-f]{32}"/

	private static final String LINKED_RESOURCE_URL = 'https://conditional.example.com'

	private static String entryPath
	private static String emptyResourcePath
	private static Map<String, String> resourcePaths

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def entryId = createEntry(CONTEXT_ID, [:], createTitleMetadataBody(resourceIri, 'Conditional GET entry'))
		entryPath = '/' + CONTEXT_ID + '/entry/' + entryId

		def fileId = createEntry(CONTEXT_ID, [:])
		def file = createTempBinaryFile('conditional', '.bin', 'file content'.bytes)
		assert EntryStoreClient.putRequestFile('/' + CONTEXT_ID + '/resource/' + fileId, file).getResponseCode() ==
				HTTP_CREATED
		emptyResourcePath = '/' + CONTEXT_ID + '/resource/' + createEntry(CONTEXT_ID, [:])
		def graphId = createEntry(CONTEXT_ID, [graphtype: 'graph'])
		def listId = createEntry(CONTEXT_ID, [graphtype: 'list'], [resource: [entryId]])
		def stringId = createEntry(CONTEXT_ID, [graphtype: 'string'], [resource: 'Some text'])
		resourcePaths = [
			file  : '/' + CONTEXT_ID + '/resource/' + fileId,
			graph : '/' + CONTEXT_ID + '/resource/' + graphId,
			list  : '/' + CONTEXT_ID + '/resource/' + listId,
			string: '/' + CONTEXT_ID + '/resource/' + stringId,
			user  : '/_principals/resource/_admin'
		]
	}

	def "GET entry as #representation should carry Last-Modified, ETag and Vary, and answer 304 to If-None-Match"() {
		given:
		def first = EntryStoreClient.getRequest(entryPath + query, 'admin', accept)
		assert first.getResponseCode() == HTTP_OK
		def etag = first.getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(entryPath + query, 'admin', accept, ['If-None-Match': etag])

		then:
		etag ==~ etagForm
		first.getHeaderField('Last-Modified') != null
		varyOf(first).containsAll(['Accept', 'Cookie', 'Authorization'])
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		conn.getHeaderField('ETag') == etag

		where:
		representation         | query         | accept                | etagForm
		'JSON'                 | ''            | 'application/json'    | DATE_ETAG
		'JSON with includeAll' | '?includeAll' | 'application/json'    | CONTENT_ETAG
		'RDF/XML'              | ''            | 'application/rdf+xml' | DATE_ETAG
		'Turtle'               | ''            | 'text/turtle'         | DATE_ETAG
	}

	def "GET entry should answer 304 to If-Modified-Since with its Last-Modified"() {
		given:
		def lastModified = EntryStoreClient.getRequest(entryPath).getHeaderField('Last-Modified')

		when:
		def conn = EntryStoreClient.getRequest(entryPath, 'admin', 'application/json',
				['If-Modified-Since': lastModified])

		then:
		lastModified != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED
	}

	def "GET entry with the ETag from before a metadata write should answer 200 with a new ETag"() {
		given:
		def oldEtag = EntryStoreClient.getRequest(entryPath).getHeaderField('ETag')
		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/' + entryPath.tokenize('/').last()
		def metadataPath = entryPath.replace('/entry/', '/metadata/')
		def metadata = JsonOutput.toJson(createTitleMetadataBody(resourceIri, 'Renamed')['metadata'])
		assert EntryStoreClient.putRequest(metadataPath, metadata).getResponseCode() == HTTP_NO_CONTENT

		when:
		def conn = EntryStoreClient.getRequest(entryPath, 'admin', 'application/json', ['If-None-Match': oldEtag])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('ETag') != oldEtag
	}

	def "GET #type resource should carry Last-Modified and ETag, and answer 304 to If-None-Match"() {
		given:
		def first = EntryStoreClient.getRequest(resourcePaths[type])
		assert first.getResponseCode() == HTTP_OK
		def etag = first.getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(resourcePaths[type], 'admin', 'application/json',
				['If-None-Match': etag])

		then:
		etag ==~ (type == 'file' ? DATE_ETAG : CONTENT_ETAG)
		first.getHeaderField('Last-Modified') != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED

		where:
		type << ['file', 'graph', 'list', 'string', 'user']
	}

	def "GET list entry with includeAll should answer 200 with a new ETag after a member's metadata changes"() {
		given: 'a list whose member is edited, which leaves the list entry itself unchanged'
		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def memberId = createEntry(CONTEXT_ID, [:], createTitleMetadataBody(resourceIri, 'Member before'))
		def listId = createEntry(CONTEXT_ID, [graphtype: 'list'], [resource: [memberId]])
		def path = '/' + CONTEXT_ID + '/entry/' + listId + '?includeAll'
		def before = EntryStoreClient.getRequest(path)
		// read now: the connection sends its request only when its response is first read
		def beforeEtag = before.getHeaderField('ETag')
		def beforeLastModified = before.getHeaderField('Last-Modified')
		def memberIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/' + memberId
		def metadata = JsonOutput.toJson(createTitleMetadataBody(memberIri, 'Member after')['metadata'])
		assert EntryStoreClient.putRequest('/' + CONTEXT_ID + '/metadata/' + memberId, metadata)
				.getResponseCode() == HTTP_NO_CONTENT

		when:
		def conn = EntryStoreClient.getRequest(path, 'admin', 'application/json', ['If-None-Match': beforeEtag])

		then:
		conn.getHeaderField('Last-Modified') == beforeLastModified
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('ETag') != beforeEtag
		conn.getInputStream().text.contains('Member after')
	}

	def "GET #type resource should answer 304 to If-Modified-Since with its Last-Modified"() {
		given:
		def lastModified = EntryStoreClient.getRequest(resourcePaths[type]).getHeaderField('Last-Modified')

		when:
		def conn = EntryStoreClient.getRequest(resourcePaths[type], 'admin', 'application/json',
				['If-Modified-Since': lastModified])

		then:
		lastModified != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED

		where:
		type << ['file', 'graph', 'list', 'string', 'user']
	}

	def "authenticated GET #target should return Cache-Control: private, no-cache and vary on the credentials"() {
		when:
		def conn = EntryStoreClient.getRequest(target == 'entry' ? entryPath : resourcePaths[target])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
		varyOf(conn).containsAll(['Cookie', 'Authorization'])

		where:
		target << ['entry', 'file', 'graph', 'list', 'string', 'user']
	}

	def "anonymous GET entry should return Last-Modified, Cache-Control: no-cache and vary on the credentials"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath, '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Last-Modified') != null
		conn.getHeaderField('Cache-Control') == 'no-cache'
		varyOf(conn).containsAll(['Accept', 'Cookie', 'Authorization'])
	}

	def "CORS GET entry should keep the CORS Vary values next to its own"() {
		when:
		def conn = EntryStoreClient.getRequest(entryPath, 'admin', 'application/json', [Origin: 'http://example.com'])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Access-Control-Allow-Origin') == 'http://example.com'
		varyOf(conn).containsAll(['Origin', 'Accept', 'Cookie', 'Authorization'])
	}

	def "CORS conditional GET entry should answer 304 with the CORS Vary values next to its own"() {
		given:
		def etag = EntryStoreClient.getRequest(entryPath).getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(entryPath, 'admin', 'application/json',
				[Origin: 'http://example.com', 'If-None-Match': etag])

		then:
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		varyOf(conn).containsAll(['Origin', 'Accept', 'Cookie', 'Authorization'])
	}

	def "HEAD entry should answer with the Content-Type, Last-Modified and ETag of the GET and no body"() {
		given:
		def get = EntryStoreClient.getRequest(entryPath)

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, entryPath, null, 'admin', null,
				[Accept: 'application/json'])

		then:
		head.getResponseCode() == HTTP_OK
		head.getContentType().startsWith('application/json')
		head.getHeaderField('ETag') == get.getHeaderField('ETag')
		head.getHeaderField('Last-Modified') == get.getHeaderField('Last-Modified')
		varyOf(head).containsAll(['Accept', 'Cookie', 'Authorization'])
		head.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
		head.getInputStream().text == ''
	}

	def "HEAD entry should answer without serializing the entry, unlike GET"() {
		given:
		def get = EntryStoreClient.getRequest(entryPath, 'admin', 'application/rdf+xml')

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, entryPath, null, 'admin', null,
				[Accept: 'application/rdf+xml'])

		then:
		get.getHeaderField('Content-Length').toInteger() > 0
		head.getResponseCode() == HTTP_OK
		head.getContentType().startsWith('application/rdf+xml')
		head.getHeaderField('Content-Length') in [null, '0']
	}

	def "HEAD entry with includeAll as JSON should omit the ETag that the GET computes from the JSON"() {
		given:
		def get = EntryStoreClient.getRequest(entryPath + '?includeAll')

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, entryPath + '?includeAll', null, 'admin',
				null, [Accept: 'application/json'])

		then:
		get.getHeaderField('ETag') ==~ CONTENT_ETAG
		head.getResponseCode() == HTTP_OK
		head.getHeaderField('ETag') == null
		head.getHeaderField('Last-Modified') == get.getHeaderField('Last-Modified')
	}

	def "HEAD entry should answer 304 to If-None-Match with its ETag"() {
		given:
		def etag = EntryStoreClient.getRequest(entryPath).getHeaderField('ETag')

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, entryPath, null, 'admin', null,
				['If-None-Match': etag])

		then:
		head.getResponseCode() == HTTP_NOT_MODIFIED
	}

	def "HEAD on a nonexistent entry should answer 404"() {
		when:
		def head = EntryStoreClient.headRequest('/' + CONTEXT_ID + '/entry/doesNotExist')

		then:
		head.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET file resource with Range and If-Range naming the #ifRangeForm should answer #status"() {
		given:
		def current = EntryStoreClient.getRequest(resourcePaths['file'])
		def ifRange = [
			'current ETag'         : current.getHeaderField('ETag'),
			'stale ETag'           : '"1"',
			'current Last-Modified': current.getHeaderField('Last-Modified'),
			'earlier date'         : 'Thu, 01 Jan 1970 00:00:01 GMT'][ifRangeForm]

		when:
		def conn = EntryStoreClient.getRequest(resourcePaths['file'], 'admin', 'application/json',
				[Range: 'bytes=0-3', 'If-Range': ifRange])

		then:
		conn.getResponseCode() == status
		conn.getHeaderField('Accept-Ranges') == 'bytes'
		conn.getInputStream().text == body

		where:
		ifRangeForm             | status       | body
		'current ETag'          | HTTP_PARTIAL | 'file'
		'stale ETag'            | HTTP_OK      | 'file content'
		'current Last-Modified' | HTTP_PARTIAL | 'file'
		'earlier date'          | HTTP_OK      | 'file content'
	}

	def "GET file resource resuming a download with the ETag of a replaced file should answer the whole new file"() {
		given:
		def path = '/' + CONTEXT_ID + '/resource/' + createEntry(CONTEXT_ID, [:])
		assert EntryStoreClient.putRequestFile(path, createTempBinaryFile('old', '.bin', 'old content'.bytes))
				.getResponseCode() == HTTP_CREATED
		def oldEtag = EntryStoreClient.getRequest(path).getHeaderField('ETag')
		assert EntryStoreClient.putRequestFile(path, createTempBinaryFile('new', '.bin', 'new content!'.bytes))
				.getResponseCode() == HTTP_CREATED

		when:
		def conn = EntryStoreClient.getRequest(path, 'admin', 'application/json',
				[Range: 'bytes=4-', 'If-Range': oldEtag])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getInputStream().text == 'new content!'
	}

	def "GET local resource without data should answer 204 with validators, and 304 to If-None-Match"() {
		given:
		def first = EntryStoreClient.getRequest(emptyResourcePath)
		assert first.getResponseCode() == HTTP_NO_CONTENT
		def etag = first.getHeaderField('ETag')

		when:
		def conn = EntryStoreClient.getRequest(emptyResourcePath, 'admin', 'application/json',
				['If-None-Match': etag])

		then:
		etag ==~ /"\d+"/
		first.getHeaderField('Last-Modified') != null
		first.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
		varyOf(first).containsAll(['Cookie', 'Authorization'])
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		conn.getHeaderField('ETag') == etag
	}

	def "GET local resource without data should answer 304 to If-Modified-Since with its Last-Modified"() {
		given:
		def lastModified = EntryStoreClient.getRequest(emptyResourcePath).getHeaderField('Last-Modified')

		when:
		def conn = EntryStoreClient.getRequest(emptyResourcePath, 'admin', 'application/json',
				['If-Modified-Since': lastModified])

		then:
		lastModified != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED
	}

	def "#method local resource without data with an outdated If-Unmodified-Since should answer 412"() {
		when:
		def conn = EntryStoreClient.sendRequestAsStream(method, emptyResourcePath, null, 'admin', null,
				['If-Unmodified-Since': 'Thu, 01 Jan 1970 00:00:01 GMT'])

		then:
		conn.getResponseCode() == HTTP_PRECON_FAILED

		where:
		method << [HttpMethod.GET, HttpMethod.HEAD]
	}

	def "HEAD entry with format=application/json;charset=UTF-8 should answer as the JSON GET does"() {
		given:
		def path = entryPath + '?format=' + URLEncoder.encode('application/json;charset=UTF-8', UTF_8)
		def get = EntryStoreClient.getRequest(path, 'admin', null)

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, path, null, 'admin', null)

		then:
		get.getResponseCode() == HTTP_OK
		get.getContentType().startsWith('application/json')
		head.getResponseCode() == HTTP_OK
		head.getContentType().startsWith('application/json')
		head.getHeaderField('ETag') == get.getHeaderField('ETag')
	}

	def "HEAD on the #target of a non-public entry as #user should answer #status with the ETag of the GET"() {
		given:
		def path = '/' + CONTEXT_ID + '/' + target + '/' + resourcePaths['graph'].tokenize('/').last()
		def get = EntryStoreClient.getRequest(path, user, 'application/json')

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, path, null, user, null,
				[Accept: 'application/json'])

		then:
		get.getResponseCode() == status
		head.getResponseCode() == status
		head.getHeaderField('ETag') == get.getHeaderField('ETag')

		where:
		target     | user    | status
		'resource' | ''      | HTTP_UNAUTHORIZED
		'resource' | 'admin' | HTTP_OK
		'metadata' | ''      | HTTP_UNAUTHORIZED
		'metadata' | 'admin' | HTTP_OK
	}

	def "GET metadata with matching If-None-Match should return 304 with empty body"() {
		given:
		def entryId = createTitledEntry('Conditional entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def firstConn = EntryStoreClient.getRequest(metadataUri)
		assert firstConn.getResponseCode() == HTTP_OK
		def etag = firstConn.getHeaderField('ETag')
		assert etag != null

		when:
		def conditionalConn = EntryStoreClient.getRequest(metadataUri, 'admin', 'application/json',
			['If-None-Match': etag])

		then:
		conditionalConn.getResponseCode() == HTTP_NOT_MODIFIED
		conditionalConn.getHeaderField('ETag') == etag
		conditionalConn.getContentLengthLong() <= 0
	}

	def "GET metadata with ?callback= should carry no conditional headers and never 304"() {
		given: 'an entry and the ETag of its plain JSON representation'
		def entryId = createTitledEntry('JSONP conditional entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def plainConn = EntryStoreClient.getRequest(metadataUri)
		assert plainConn.getResponseCode() == HTTP_OK
		def plainEtag = plainConn.getHeaderField('ETag')
		assert plainEtag != null

		when: 'requesting the JSONP-wrapped representation'
		def jsonpConn = EntryStoreClient.getRequest(metadataUri + '?callback=cb')

		then: 'no conditional headers — the callback name is in the body but not in the ETag key'
		jsonpConn.getResponseCode() == HTTP_OK
		jsonpConn.getHeaderField('ETag') == null
		jsonpConn.getHeaderField('Last-Modified') == null

		when: 'revalidating the JSONP shape with the plain ETag'
		def conditionalConn = EntryStoreClient.getRequest(metadataUri + '?callback=cb', 'admin',
				'application/json', ['If-None-Match': plainEtag])

		then: 'a full 200 — a representation the client does not hold is never confirmed'
		conditionalConn.getResponseCode() == HTTP_OK
	}

	def "GET metadata with an ETag of a different representation should return 200"() {
		given:
		def entryId = createTitledEntry('Cross representation entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def jsonConn = EntryStoreClient.getRequest(metadataUri)
		assert jsonConn.getResponseCode() == HTTP_OK
		def jsonEtag = jsonConn.getHeaderField('ETag')
		def turtleConn = EntryStoreClient.getRequest(metadataUri, 'admin', 'text/turtle')
		assert turtleConn.getResponseCode() == HTTP_OK
		def turtleEtag = turtleConn.getHeaderField('ETag')

		expect:
		// two representations of the same metadata never share an ETag
		jsonEtag != turtleEtag

		when: 'revalidating the turtle representation with the json ETag'
		def crossConn = EntryStoreClient.getRequest(metadataUri, 'admin', 'text/turtle',
			['If-None-Match': jsonEtag])

		then: 'the client does not hold this representation, so it must be sent in full'
		crossConn.getResponseCode() == HTTP_OK
		crossConn.inputStream.text.length() > 0
	}

	def "GET metadata with a stale If-None-Match after modification should return 200 with a new ETag"() {
		given:
		def entryId = createTitledEntry('Soon to be updated')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def firstConn = EntryStoreClient.getRequest(metadataUri)
		assert firstConn.getResponseCode() == HTTP_OK
		def oldEtag = firstConn.getHeaderField('ETag')

		def resourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/' + entryId
		def newMetadata = [(resourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																			 value: 'Updated title']]]]
		def putConn = EntryStoreClient.putRequest(metadataUri, JsonOutput.toJson(newMetadata))
		assert putConn.getResponseCode() < 300

		when:
		def conditionalConn = EntryStoreClient.getRequest(metadataUri, 'admin', 'application/json',
			['If-None-Match': oldEtag])

		then:
		conditionalConn.getResponseCode() == HTTP_OK
		def newEtag = conditionalConn.getHeaderField('ETag')
		newEtag != null
		newEtag != oldEtag
	}

	def "GET metadata with If-Modified-Since should return 304 for unmodified entry"() {
		given:
		def entryId = createTitledEntry('If modified since entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		// entries are timestamped with millisecond precision but HTTP dates carry seconds only;
		// wait past the creation second so the If-Modified-Since comparison is unambiguous
		sleep(1100)
		def firstConn = EntryStoreClient.getRequest(metadataUri)
		assert firstConn.getResponseCode() == HTTP_OK
		def lastModified = firstConn.getHeaderField('Last-Modified')
		assert lastModified != null

		when:
		def conditionalConn = EntryStoreClient.getRequest(metadataUri, 'admin', 'application/json',
			['If-Modified-Since': lastModified])

		then:
		conditionalConn.getResponseCode() == HTTP_NOT_MODIFIED
	}

	def "conditional GET must not bypass authorization"() {
		given: 'an admin-only entry and a valid ETag for it'
		def entryId = createTitledEntry('Private entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def adminConn = EntryStoreClient.getRequest(metadataUri)
		assert adminConn.getResponseCode() == HTTP_OK
		def etag = adminConn.getHeaderField('ETag')

		when: 'a guest revalidates with a matching ETag'
		def guestConn = EntryStoreClient.getRequest(metadataUri, '', 'application/json',
			['If-None-Match': etag])

		then: 'the guest is denied — 401, never 304'
		guestConn.getResponseCode() == HTTP_UNAUTHORIZED

		when: 'a non-admin user revalidates with a matching ETag'
		def userConn = EntryStoreClient.getRequest(metadataUri, 'user', 'application/json',
			['If-None-Match': etag])

		then: 'access must be denied — 403, never 304'
		userConn.getResponseCode() == HTTP_FORBIDDEN
	}

	private def createTitledEntry(String title) {
		def params = [entrytype: 'link', resource: LINKED_RESOURCE_URL]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: title]],]]]
		return createEntry(CONTEXT_ID, params, body)
	}

	/** All Vary values, which may arrive in several header lines. */
	private static List<String> varyOf(HttpURLConnection conn) {
		return (conn.getHeaderFields()['Vary'] ?: []).collectMany { it.split(',')*.trim() }
	}
}
