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
import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

/**
 * A context feed expands each list it finds into the list's children. In private contexts, the guest may read a
 * list and some entries but not others: what it may not read is left out of its feed instead of failing it.
 */
class ResourceSyndicationAccessIT extends BaseSpec {

	/** A guest-readable list holding a guest-readable and a private child. */
	def static childContextId = 'syndication-access-child-it'
	/** A list the guest may read only the metadata of, holding a guest-readable child. */
	def static listContextId = 'syndication-access-list-it'

	def static guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'

	def setupSpec() {
		getOrCreateContext([contextId: childContextId])
		def readable = createTitledEntry(childContextId, 'readable child')
		def unreadable = createTitledEntry(childContextId, 'unreadable child')
		def list = createTitledEntry(childContextId, 'list', [graphtype: 'list'])
		setChildren(childContextId, list, [readable, unreadable])
		grantGuestRead(childContextId, readable, ['metadata'])
		grantGuestRead(childContextId, list, ['metadata', 'resource'])

		getOrCreateContext([contextId: listContextId])
		def child = createTitledEntry(listContextId, 'readable child')
		def metadataOnlyList = createTitledEntry(listContextId, 'list', [graphtype: 'list'])
		setChildren(listContextId, metadataOnlyList, [child])
		grantGuestRead(listContextId, child, ['metadata'])
		grantGuestRead(listContextId, metadataOnlyList, ['metadata'])

		waitForSolrProcessing()
		// Both contexts hold two guest-readable entries once the index has the ACLs
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until { guestSearchResults(childContextId) == 2 && guestSearchResults(listContextId) == 2 }
	}

	def "GET /_contexts/resource/{context-id}?syndication=rss_2.0 as guest should leave out a list child the guest may not read"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(childContextId), '')

		then:
		connection.getResponseCode() == HTTP_OK
		def titles = feedTitles(connection)
		titles.contains('readable child')
		!titles.contains('unreadable child')
		!titles.contains('Missing title')
	}

	def "GET /_contexts/resource/{context-id}?syndication=rss_2.0 as guest should not fail on a list whose resource the guest may not read"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(listContextId), '')

		then:
		connection.getResponseCode() == HTTP_OK
		feedTitles(connection).contains('readable child')
	}

	def "GET /_contexts/resource/{context-id}?syndication=rss_2.0 as admin should include every list child"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(childContextId))

		then:
		connection.getResponseCode() == HTTP_OK
		def titles = feedTitles(connection)
		titles.contains('readable child')
		titles.contains('unreadable child')
	}

	private static String createTitledEntry(String contextId, String title, Map params = [:]) {
		def resourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		return createEntry(contextId, params, createTitleMetadataBody(resourceIri, title))
	}

	private static void setChildren(String contextId, String listId, List<String> childIds) {
		def connection = EntryStoreClient.putRequest('/' + contextId + '/resource/' + listId,
			JsonOutput.toJson(childIds))
		assert connection.getResponseCode() < 300
	}

	/** Replaces the entry's ACL with guest read access on the given parts, 'metadata' and/or 'resource'. */
	private static void grantGuestRead(String contextId, String entryId, List<String> parts) {
		def acl = parts.collectEntries { part ->
			[(EntryStoreClient.baseUrl + '/' + contextId + '/' + part + '/' + entryId):
				 [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUri]]]]
		}
		def connection = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId, JsonOutput.toJson(acl))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}

	private static int guestSearchResults(String contextId) {
		def query = 'context:"' + EntryStoreClient.baseUrl + '/' + contextId + '"'
		def connection = EntryStoreClient.getRequest(
			'/search' + convertMapToQueryParams([type: 'solr', query: query]), '')
		if (connection.getResponseCode() != HTTP_OK) {
			return -1
		}
		return JSON_PARSER.parseText(connection.inputStream.text)['results']
	}

	private static String feedPath(String contextId) {
		return '/_contexts/resource/' + contextId + '?syndication=rss_2.0'
	}

	private static List<String> feedTitles(HttpURLConnection connection) {
		def channel = new XmlParser(false, false).parseText(connection.inputStream.text)['channel'][0] as Node
		return channel['item'].collect { ((it as Node)['title'][0] as Node).text() }
	}
}
