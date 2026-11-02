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

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

/**
 * ACL writes that change an entry's own ACL outside a PUT of its entry information must reach the entry's Solr
 * document (ENTRYSTORE-1240). Each case asserts on the indexed {@code public} or {@code acl.*} field through an admin
 * search, which sees every row.
 */
class SolrIndexAclIT extends BaseSpec {

	def static contextId = 'solr-index-acl'
	def static guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "PUT /{context-id}/entry/{list-id}?applyACLtoChildren granting guest read should index the list's child as public"() {
		given: 'a list holding a child without an ACL of its own'
		def listId = createEntry(contextId, [graphtype: 'list'])
		def childId = createEntry(contextId,
			[entrytype: 'link', resource: 'https://example.org/apply-acl-child', list: resourceUri(listId)])

		when:
		def conn = EntryStoreClient.putRequest('/' + contextId + '/entry/' + listId + '?applyACLtoChildren=true',
			JsonOutput.toJson(guestReadAcl(metadataUri(listId))))

		then:
		conn.getResponseCode() == HTTP_NO_CONTENT
		awaitIndexed('uri:"' + entryUri(childId) + '" AND public:true')
	}

	def "POST /{context-id}?list= with an entry information body should index the ACL copied from a guest-readable list"() {
		given: 'a guest-readable list, and an entry information body whose own ACL replaces the copied one'
		def listId = createGuestReadableList()
		def userUri = EntryStoreClient.createdEsUsers['user']['resourceUri']
		def body = [info: [(metadataUri('_newId')): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: userUri]]]]]
		def params = [entrytype: 'link', resource: 'https://example.org/info-child', list: resourceUri(listId)]

		when:
		def entryId = createEntry(contextId, params, body)

		then: 'the list ACL copied after the body was applied is what the index holds'
		awaitIndexed('uri:"' + entryUri(entryId) + '" AND public:true')
	}

	def "POST /{context-id}?list= without metadata or entry information should index the ACL copied from a guest-readable list"() {
		given:
		def listId = createGuestReadableList()
		def params = [entrytype: 'link', resource: 'https://example.org/bare-child', list: resourceUri(listId)]

		when:
		def entryId = createEntry(contextId, params)

		then:
		awaitIndexed('uri:"' + entryUri(entryId) + '" AND public:true')
	}

	def "POST /_principals/groups without a name should index the ACL of the new context"() {
		given:
		def groupContextId = 'solr-index-acl-group'

		when:
		def groupId = createContext([contextId: groupContextId])

		then: 'the group gets write access to the context after the context entry was created'
		def groupUri = EntryStoreClient.baseUrl + '/_principals/resource/' + groupId
		awaitIndexed('uri:"' + EntryStoreClient.baseUrl + '/_contexts/entry/' + groupContextId + '"'
			+ ' AND acl.resource.rw:"' + groupUri + '"')
	}

	private static String createGuestReadableList() {
		return createEntry(contextId, [graphtype: 'list'], [info: guestReadAcl(metadataUri('_newId'))])
	}

	/** Entry information granting guest read on the given metadata URI. */
	private static Map guestReadAcl(String metadataUri) {
		return [(metadataUri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
	}

	/** Polls an admin search until the query matches exactly one document; commitWithin lets the searcher lag. */
	private void awaitIndexed(String query) {
		waitForSolrProcessing()
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until {
				def conn = EntryStoreClient.getRequest(
					'/search' + convertMapToQueryParams([type: 'solr', query: query]))
				conn.getResponseCode() == HTTP_OK && JSON_PARSER.parseText(conn.inputStream.text)['results'] == 1
			}
	}

	private static String entryUri(String entryId) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
	}

	private static String resourceUri(String entryId) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId
	}

	private static String metadataUri(String entryId) {
		return EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
	}
}
