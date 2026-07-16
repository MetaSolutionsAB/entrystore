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

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import static java.net.HttpURLConnection.HTTP_OK

/**
 * ENTRYSTORE-1087: conditional GET with representation-aware ETags on the metadata endpoint.
 * If-None-Match must revalidate only the exact representation the client holds, and
 * authorization must always precede the 304 decision.
 */
class ConditionalGetIT extends BaseSpec {

	def static contextId = '91'
	def resourceUrl = 'https://conditional.example.com'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	private def createTitledEntry(String title) {
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: title]],]]]
		return createEntry(contextId, params, body)
	}

	def "GET metadata with matching If-None-Match should return 304 with empty body"() {
		given:
		def entryId = createTitledEntry('Conditional entry')
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
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
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
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
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
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
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
		def firstConn = EntryStoreClient.getRequest(metadataUri)
		assert firstConn.getResponseCode() == HTTP_OK
		def oldEtag = firstConn.getHeaderField('ETag')

		def resourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId
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
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
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
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
		def adminConn = EntryStoreClient.getRequest(metadataUri)
		assert adminConn.getResponseCode() == HTTP_OK
		def etag = adminConn.getHeaderField('ETag')

		when: 'a guest revalidates with a matching ETag'
		def guestConn = EntryStoreClient.getRequest(metadataUri, '', 'application/json',
			['If-None-Match': etag])

		then: 'existence must not be confirmed — 404, never 304'
		guestConn.getResponseCode() == HTTP_NOT_FOUND

		when: 'a non-admin user revalidates with a matching ETag'
		def userConn = EntryStoreClient.getRequest(metadataUri, 'user', 'application/json',
			['If-None-Match': etag])

		then: 'access must be denied — 403, never 304'
		userConn.getResponseCode() == HTTP_FORBIDDEN
	}
}
