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

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_ACCEPTED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

/**
 * ENTRYSTORE-1273. A recursive delete of a list answers 202 Accepted and removes the tree in the background, from its
 * leaves to the list.
 */
class ListTreeRemovalIT extends BaseSpec {

	def static contextId = 'treeRemoval'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "DELETE /{context-id}/entry/{entry-id}?recursive of a list should respond with Accepted and then remove its tree"() {
		given:
		def rootId = createEntry(contextId, [graphtype: 'list'])
		def folderId = createEntry(contextId, [graphtype: 'list', list: listUri(rootId)])
		def memberId = createEntry(contextId, [entrytype: 'link', resource: 'https://slashdot.org/', list: listUri(folderId)])

		when:
		def connection = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + rootId + '?recursive=true')

		then:
		connection.getResponseCode() == HTTP_ACCEPTED
		await().pollInterval(200, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
			.until { [rootId, folderId, memberId].every { entryStatus(it) == HTTP_NOT_FOUND } }
	}

	def "DELETE /{context-id}/resource/{entry-id}?recursive of a list should respond with Accepted and then remove the list and its tree"() {
		given:
		def rootId = createEntry(contextId, [graphtype: 'list'])
		def memberId = createEntry(contextId, [entrytype: 'link', resource: 'https://slashdot.org/', list: listUri(rootId)])

		when:
		def connection = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + rootId + '?recursive=true')

		then:
		connection.getResponseCode() == HTTP_ACCEPTED
		await().pollInterval(200, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
			.until { [rootId, memberId].every { entryStatus(it) == HTTP_NOT_FOUND } }
	}

	def "DELETE /{context-id}/resource/{entry-id} of a list without recursive should respond with No Content and empty it at once"() {
		given:
		def listId = createEntry(contextId, [graphtype: 'list'])
		def memberId = createEntry(contextId, [entrytype: 'link', resource: 'https://slashdot.org/', list: listUri(listId)])

		when:
		def connection = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + listId)

		then:
		connection.getResponseCode() == HTTP_NO_CONTENT
		def listConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + listId)
		listConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(listConn.inputStream.text) == []
		entryStatus(memberId) == HTTP_OK
	}

	private static String listUri(String entryId) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
	}

	private static int entryStatus(String entryId) {
		return EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId).getResponseCode()
	}
}
