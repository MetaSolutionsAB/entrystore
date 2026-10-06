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
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_PARTIAL
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED
import static java.nio.charset.StandardCharsets.UTF_8
import static org.entrystore.rest.springboot.filter.CacheControlFilter.CACHE_CONTROL_AUTHENTICATED

/**
 * Entry and resource GETs carry Last-Modified and ETag from the entry's modification date and answer conditional
 * requests with 304, and HEAD on an entry answers with those headers, as in 5.x.
 */
class ConditionalGetIT extends BaseSpec {

	private static final String CONTEXT_ID = 'conditional-get-it-ctx'

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
		etag ==~ /"\d+"/
		first.getHeaderField('Last-Modified') != null
		varyOf(first).containsAll(['Accept', 'Cookie', 'Authorization'])
		conn.getResponseCode() == HTTP_NOT_MODIFIED
		conn.getHeaderField('ETag') == etag

		where:
		representation         | query         | accept
		'JSON'                 | ''            | 'application/json'
		'JSON with includeAll' | '?includeAll' | 'application/json'
		'RDF/XML'              | ''            | 'application/rdf+xml'
		'Turtle'               | ''            | 'text/turtle'
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
		etag ==~ /"\d+"/
		first.getHeaderField('Last-Modified') != null
		conn.getResponseCode() == HTTP_NOT_MODIFIED

		where:
		type << ['file', 'graph', 'list', 'string', 'user']
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
		'resource' | ''      | HTTP_NOT_FOUND
		'resource' | 'admin' | HTTP_OK
		'metadata' | ''      | HTTP_NOT_FOUND
		'metadata' | 'admin' | HTTP_OK
	}

	/** All Vary values, which may arrive in several header lines. */
	private static List<String> varyOf(HttpURLConnection conn) {
		return (conn.getHeaderFields()['Vary'] ?: []).collectMany { it.split(',')*.trim() }
	}
}
