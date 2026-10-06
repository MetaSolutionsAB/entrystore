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

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Writes with {@code If-Unmodified-Since}, as entrystore.js sends on every save: a date older than the entry's
 * modification date must answer 412 and change nothing, as in 5.x.
 */
class IfUnmodifiedSinceIT extends BaseSpec {

	static final String CONTEXT_ID = 'if-unmodified-since-it'
	static final int HTTP_PRECONDITION_FAILED = 412
	static final Map LINK_ENTRY = [entrytype: 'link', resource: 'https://a.org']
	static final Map STRING_ENTRY = [graphtype: 'string']
	static final Map REFERENCE_ENTRY =
		[entrytype: 'reference', resource: 'https://a.org', 'cached-external-metadata': 'https://a.org/md']
	static final DateTimeFormatter HTTP_DATE =
		DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
	}

	def "PUT metadata with an If-Unmodified-Since older than the entry should return 412 and keep the metadata"() {
		given:
		def entryId = createLinkEntry('original title')
		def stale = httpDate(modified(entryId).minus(Duration.ofHours(1)))

		when:
		def connection = putMetadata(entryId, 'overwritten title', stale)

		then:
		connection.getResponseCode() == HTTP_PRECONDITION_FAILED
		title(entryId) == 'original title'
	}

	def "PUT metadata with the entry's current modification date should succeed"() {
		given:
		def entryId = createLinkEntry('original title')
		def current = httpDate(modified(entryId))

		when:
		def connection = putMetadata(entryId, 'new title', current)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		title(entryId) == 'new title'
	}

	def "PUT metadata without If-Unmodified-Since should succeed"() {
		given:
		def entryId = createLinkEntry('original title')

		when:
		def connection = putMetadata(entryId, 'new title', null)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		title(entryId) == 'new title'
	}

	def "PUT metadata with an unparsable If-Unmodified-Since should ignore the header"() {
		given:
		def entryId = createLinkEntry('original title')

		when:
		def connection = putMetadata(entryId, 'new title', 'not a date')

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		title(entryId) == 'new title'
	}

	def "a second client saving metadata with the date it read before the first client's save should get 412"() {
		given: 'two clients read the same entry'
		def entryId = createLinkEntry('original title')
		def readByBoth = httpDate(modified(entryId))
		// HTTP dates have second precision, so the first save must land in a later second
		sleep(1100)

		when: 'both save with the date they read'
		def first = putMetadata(entryId, 'first client', readByBoth)
		def second = putMetadata(entryId, 'second client', readByBoth)

		then: 'the first save wins and the second client is told about the conflict'
		first.getResponseCode() == HTTP_NO_CONTENT
		second.getResponseCode() == HTTP_PRECONDITION_FAILED
		title(entryId) == 'first client'
	}

	def "#method #route with an If-Unmodified-Since older than the entry should return 412 and change nothing"() {
		given:
		def entryId = createEntry(CONTEXT_ID, entryParams)
		def modifiedBefore = modified(entryId)
		def path = '/' + CONTEXT_ID + '/' + route + '/' + entryId
		def headers = ['If-Unmodified-Since': httpDate(modifiedBefore.minus(Duration.ofHours(1)))]

		when:
		def connection = method == 'DELETE'
			? EntryStoreClient.deleteRequest(path, null, 'admin', null, headers)
			: EntryStoreClient.putRequest(path, body, 'admin', contentType, headers)

		then:
		connection.getResponseCode() == HTTP_PRECONDITION_FAILED
		modified(entryId) == modifiedBefore

		where:
		method   | route      | entryParams  | body                | contentType
		'PUT'    | 'entry'    | LINK_ENTRY   | '{}'                | 'application/json'
		'DELETE' | 'entry'    | LINK_ENTRY   | null                | null
		'DELETE' | 'metadata' | LINK_ENTRY   | null                | null
		'PUT'    | 'resource' | STRING_ENTRY | 'new resource text' | 'text/plain'
	}

	def "PUT cached-external-metadata with an If-Unmodified-Since older than the cached metadata should return 412"() {
		given:
		def entryId = createEntry(CONTEXT_ID, REFERENCE_ENTRY)
		assert putCachedMetadata(entryId, 'cached title', null).getResponseCode() == HTTP_NO_CONTENT
		def stale = httpDate(modified(entryId).minus(Duration.ofHours(1)))

		when:
		def connection = putCachedMetadata(entryId, 'overwritten title', stale)

		then:
		connection.getResponseCode() == HTTP_PRECONDITION_FAILED
		cachedTitle(entryId) == 'cached title'
	}

	def "PUT cached-external-metadata with the entry's modification date should succeed, as entrystore.js sends it"() {
		given:
		def entryId = createEntry(CONTEXT_ID, REFERENCE_ENTRY)
		assert putCachedMetadata(entryId, 'cached title', null).getResponseCode() == HTTP_NO_CONTENT
		def current = httpDate(modified(entryId))

		when:
		def connection = putCachedMetadata(entryId, 'new title', current)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		cachedTitle(entryId) == 'new title'
	}

	def "a user with only write access to the metadata should save with the entry's current modification date"() {
		given:
		def entryId = createLinkEntry('original title')
		grantMetadataWrite(entryId, 'user')
		def current = httpDate(modified(entryId))

		when:
		def connection = putMetadata(entryId, 'written by user', current, 'user')

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		title(entryId) == 'written by user'
	}

	def "a user with only write access to the metadata should get 412 for an old If-Unmodified-Since"() {
		given:
		def entryId = createLinkEntry('original title')
		grantMetadataWrite(entryId, 'user')
		def stale = httpDate(modified(entryId).minus(Duration.ofHours(1)))

		when:
		def connection = putMetadata(entryId, 'written by user', stale, 'user')

		then:
		connection.getResponseCode() == HTTP_PRECONDITION_FAILED
		title(entryId) == 'original title'
	}

	def "a guest without read access sending an old If-Unmodified-Since should get 401, not 412"() {
		given:
		def entryId = createLinkEntry('original title')
		def stale = httpDate(modified(entryId).minus(Duration.ofHours(1)))
		def body = metadataJson(entryId, 'guest title')

		when:
		def connection = EntryStoreClient.putRequest('/' + CONTEXT_ID + '/metadata/' + entryId, body, '',
			'application/json', ['If-Unmodified-Since': stale])

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		JSON_PARSER.parseText(connection.errorStream.text)['error'] == 'Not authorized'
		title(entryId) == 'original title'
	}

	private static String createLinkEntry(String title) {
		def entryId = createEntry(CONTEXT_ID, [entrytype: 'link', resource: 'https://example.org/' + UUID.randomUUID()])
		assert putMetadata(entryId, title, null).getResponseCode() == HTTP_NO_CONTENT
		return entryId
	}

	private static HttpURLConnection putMetadata(String entryId, String title, String ifUnmodifiedSince,
			String asUser = 'admin') {
		def headers = ifUnmodifiedSince == null ? [:] : ['If-Unmodified-Since': ifUnmodifiedSince]
		return EntryStoreClient.putRequest('/' + CONTEXT_ID + '/metadata/' + entryId, metadataJson(entryId, title),
			asUser, 'application/json', headers)
	}

	private static HttpURLConnection putCachedMetadata(String entryId, String title, String ifUnmodifiedSince) {
		def headers = ifUnmodifiedSince == null ? [:] : ['If-Unmodified-Since': ifUnmodifiedSince]
		return EntryStoreClient.putRequest('/' + CONTEXT_ID + '/cached-external-metadata/' + entryId,
			metadataJson(entryId, title), 'admin', 'application/json', headers)
	}

	private static String cachedTitle(String entryId) {
		def connection = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/cached-external-metadata/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		def metadata = JSON_PARSER.parseText(connection.inputStream.text)
		return metadata[resourceUri(entryId)][NameSpaceConst.DC_TERM_TITLE][0]['value']
	}

	/**
	 * Grants the user es:write on the entry's metadata only, which implies reading it.
	 */
	private static void grantMetadataWrite(String entryId, String username) {
		def metadataUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/metadata/' + entryId
		def userResourceUri = EntryStoreClient.createdEsUsers[username]['resourceUri']
		def acl = [(metadataUri): [(NameSpaceConst.TERM_WRITE): [[type: 'uri', value: userResourceUri]]]]
		def connection = EntryStoreClient.putRequest('/' + CONTEXT_ID + '/entry/' + entryId, JsonOutput.toJson(acl))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}

	private static String metadataJson(String entryId, String title) {
		def resourceUri = resourceUri(entryId)
		return JsonOutput.toJson([(resourceUri): [(NameSpaceConst.DC_TERM_TITLE): [[type: 'literal', value: title]]]])
	}

	private static String title(String entryId) {
		def connection = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/metadata/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		def metadata = JSON_PARSER.parseText(connection.inputStream.text)
		return metadata[resourceUri(entryId)][NameSpaceConst.DC_TERM_TITLE][0]['value']
	}

	/**
	 * @return the entry's modification date from its entry information, which entrystore.js sends back
	 */
	private static OffsetDateTime modified(String entryId) {
		def connection = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/entry/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		def entryUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + entryId
		def info = JSON_PARSER.parseText(connection.inputStream.text)['info'][entryUri]
		return OffsetDateTime.parse(info[NameSpaceConst.DC_TERMS + 'modified'][0]['value'].toString())
	}

	private static String resourceUri(String entryId) {
		def connection = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/entry/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		def entryUri = EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + entryId
		def info = JSON_PARSER.parseText(connection.inputStream.text)['info'][entryUri]
		return info[NameSpaceConst.TERM_RESOURCE][0]['value'].toString()
	}

	private static String httpDate(OffsetDateTime date) {
		return HTTP_DATE.format(date)
	}
}
