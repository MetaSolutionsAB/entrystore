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

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * {@code GET /{context-id}/entry/{entry-id}?includeAll} for callers who may not read the entry's resource, in a
 * context neither the guest nor {@code user} is a member of. As in 5.x, serializing the resource of a local entry
 * of graph type None, String or Graph denies the request; List and User resources are serialized without the
 * parts the caller may not read.
 */
class EntryIncludeAllAccessIT extends BaseSpec {

	def static contextId = 'include-all-access-it'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a named resource entry should respond with Unauthorized 401"() {
		given:
		def entryId = createEntry(contextId, [informationresource: 'false'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		JSON_PARSER.parseText(connection.errorStream.text)['error'] == 'Not authorized'
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as non-member user on a named resource entry should respond with Forbidden 403"() {
		given:
		def entryId = createEntry(contextId, [informationresource: 'false'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		JSON_PARSER.parseText(connection.errorStream.text)['error'] == 'Not authorized'
	}

	def "GET /{context-id}/entry/{entry-id} without includeAll as guest on a named resource entry should return the info graph"() {
		given:
		def entryId = createEntry(contextId, [informationresource: 'false'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, '')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['info'][EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId] != null
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a file entry should respond with Unauthorized 401"() {
		given:
		def entryId = createFileEntry()

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		JSON_PARSER.parseText(connection.errorStream.text)['error'] == 'Not authorized'
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as admin on a file entry should return the file's digest"() {
		given:
		def entryId = createFileEntry()

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['resource']['sha256'] ==~ /[0-9a-f]{64}/
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as a user granted ReadResource on a file entry should return the file's digest"() {
		given:
		def entryId = createFileEntry()
		grantRead('resource', entryId, 'user')

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'user')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['resource']['sha256'] ==~ /[0-9a-f]{64}/
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as a user granted only ReadMetadata on a file entry should respond with Forbidden 403"() {
		given:
		def entryId = createFileEntry()
		grantRead('metadata', entryId, 'user')

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == 'Not authorized'
		json['info'] == null
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as non-member user on a file entry should respond with Forbidden 403"() {
		given:
		def entryId = createFileEntry()

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == 'Not authorized'
		json['info'] == null
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a String entry should respond with Unauthorized 401"() {
		given:
		def entryId = createEntry(contextId, [graphtype: 'string'], [resource: 'private text'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		!connection.errorStream.text.contains('private text')
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a Graph entry should respond with Unauthorized 401"() {
		given:
		def entryId = createEntry(contextId, [graphtype: 'graph'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a List entry should return the info graph and an empty resource"() {
		given:
		def listId = createEntry(contextId, [graphtype: 'list'])
		def childId = createEntry(contextId, [informationresource: 'false'])
		def listResourcePath = '/' + contextId + '/resource/' + listId
		def addChild = EntryStoreClient.putRequest(listResourcePath, '["' + childId + '"]')
		assert addChild.getResponseCode() < 300

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + listId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['info'][EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + listId] != null
		json['resource'] == [:]
	}

	def "GET /_principals/entry/{entry-id}?includeAll as guest on a user entry should return an empty resource"() {
		given:
		def userEntryId = EntryStoreClient.createdEsUsers['user']['entryId'].toString()

		when:
		def connection = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['resource'] == [:]
	}

	private static String createFileEntry() {
		def entryId = createEntry(contextId, [:])
		def file = createTempBinaryFile('include-all', '.bin', 'private bytes'.bytes)
		def upload = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, file, 'admin',
			'application/octet-stream')
		assert upload.getResponseCode() == HTTP_CREATED
		return entryId
	}

	/** Replaces the entry's ACL with read access for {@code username} on its metadata or its resource. */
	private static void grantRead(String metadataOrResource, String entryId, String username) {
		def uri = EntryStoreClient.baseUrl + '/' + contextId + '/' + metadataOrResource + '/' + entryId
		def userUri = EntryStoreClient.createdEsUsers[username]['resourceUri']
		def acl = [(uri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: userUri]]]]
		def connection = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId, JsonOutput.toJson(acl))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}
}
