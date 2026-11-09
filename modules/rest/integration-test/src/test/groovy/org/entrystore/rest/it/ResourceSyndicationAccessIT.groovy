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
 * A feed expands each list it finds into the list's children. In private contexts, the guest may read a list and
 * some entries but not others: what it may not read is left out of its feed instead of failing it.
 * <p>
 * The specs request the feed of an outer list whose only child is the list under test. A list's feed queries only its
 * direct children, so the inner list's children reach it only through expansion; in a context feed every child is
 * also a hit of its own.
 */
class ResourceSyndicationAccessIT extends BaseSpec {

	/** Holds a guest-readable list with a guest-readable and a private child. */
	def static childContextId = 'syndication-access-child-it'
	/** Holds a list the guest may read only the metadata of, with a guest-readable child. */
	def static listContextId = 'syndication-access-list-it'

	/** The outer list in {@link #childContextId}. */
	def static childOuterListId
	/** The outer list in {@link #listContextId}. */
	def static listOuterListId

	def static guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'

	def setupSpec() {
		getOrCreateContext([contextId: childContextId])
		def readable = createTitledEntry(childContextId, 'readable child')
		def unreadable = createTitledEntry(childContextId, 'unreadable child')
		def list = createTitledEntry(childContextId, 'list', [graphtype: 'list'])
		setChildren(childContextId, list, [readable, unreadable])
		grantGuestRead(childContextId, readable, ['metadata'])
		grantGuestRead(childContextId, list, ['metadata', 'resource'])
		childOuterListId = createOuterList(childContextId, list)

		getOrCreateContext([contextId: listContextId])
		def child = createTitledEntry(listContextId, 'readable child')
		def metadataOnlyList = createTitledEntry(listContextId, 'list', [graphtype: 'list'])
		setChildren(listContextId, metadataOnlyList, [child])
		grantGuestRead(listContextId, child, ['metadata'])
		grantGuestRead(listContextId, metadataOnlyList, ['metadata'])
		listOuterListId = createOuterList(listContextId, metadataOnlyList)

		waitForSolrProcessing()
		// An empty guest feed proves nothing until the outer list's feed query finds the inner list for the guest
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until {
				guestListMembers(childContextId, childOuterListId) == 1
					&& guestListMembers(listContextId, listOuterListId) == 1
			}
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 as guest should leave out a list child the guest may not read"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(childContextId, childOuterListId), '')

		then:
		connection.getResponseCode() == HTTP_OK
		feedTitles(connection) == ['readable child']
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 as guest should leave out the children of a list whose resource the guest may not read"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(listContextId, listOuterListId), '')

		then:
		connection.getResponseCode() == HTTP_OK
		feedTitles(connection).isEmpty()
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 as admin should include every list child"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(childContextId, childOuterListId))

		then:
		connection.getResponseCode() == HTTP_OK
		feedTitles(connection).sort() == ['readable child', 'unreadable child']
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 as admin should include the children of a list whose resource the guest may not read"() {
		when:
		def connection = EntryStoreClient.getRequest(feedPath(listContextId, listOuterListId))

		then:
		connection.getResponseCode() == HTTP_OK
		feedTitles(connection) == ['readable child']
	}

	private static String createTitledEntry(String contextId, String title, Map params = [:]) {
		def resourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		return createEntry(contextId, params, createTitleMetadataBody(resourceIri, title))
	}

	/** Creates a list the guest may read, holding only the given list, and returns its id. */
	private static String createOuterList(String contextId, String innerListId) {
		def outerListId = createTitledEntry(contextId, 'outer list', [graphtype: 'list'])
		setChildren(contextId, outerListId, [innerListId])
		grantGuestRead(contextId, outerListId, ['metadata', 'resource'])
		return outerListId
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

	/** The number of children the guest finds with the Solr query of the list's feed. */
	private static int guestListMembers(String contextId, String listId) {
		def query = 'lists:"' + EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + listId + '"'
		def connection = EntryStoreClient.getRequest(
			'/search' + convertMapToQueryParams([type: 'solr', query: query]), '')
		if (connection.getResponseCode() != HTTP_OK) {
			return -1
		}
		return JSON_PARSER.parseText(connection.inputStream.text)['results']
	}

	private static String feedPath(String contextId, String entryId) {
		return '/' + contextId + '/resource/' + entryId + '?syndication=rss_2.0'
	}

	private static List<String> feedTitles(HttpURLConnection connection) {
		def channel = new XmlParser(false, false).parseText(connection.inputStream.text)['channel'][0] as Node
		return channel['item'].collect { ((it as Node)['title'][0] as Node).text() }
	}
}
