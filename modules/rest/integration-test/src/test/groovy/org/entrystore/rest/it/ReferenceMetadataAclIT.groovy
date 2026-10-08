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

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Guest access to the cached external metadata of a Reference, which the ReadMetadata ACL on
 * {@code {context}/metadata/{id}} governs, whether or not the entry graph has es:metadata.
 * <p>
 * A Reference created without an entry graph has no es:metadata. Its in-memory instance from the creation still
 * knows its metadata URI, so the References without es:metadata are exported and imported into another context:
 * there they are only ever loaded from the store.
 */
class ReferenceMetadataAclIT extends BaseSpec {

	def static sourceContextId = 'reference-metadata-acl-source'
	def static importedContextId = 'reference-metadata-acl-imported'
	def static createdContextId = 'reference-metadata-acl-created'

	def static base = EntryStoreClient.baseUrl
	def static guestUri = base + '/_principals/resource/_guest'
	def static adminUri = base + '/_principals/resource/_admin'
	def static resourceUrl = 'https://example.org/reference-metadata-acl'
	def static externalMetadataUrl = 'https://example.org/reference-metadata-acl/metadata'

	def setupSpec() {
		getOrCreateContext([contextId: sourceContextId])
		['resource-grant', 'metadata-grant'].each { entryId ->
			createEntry(sourceContextId, [entrytype: 'reference', id: entryId, resource: resourceUrl,
										  'cached-external-metadata': externalMetadataUrl])
		}
		getOrCreateContext([contextId: importedContextId])
		def export = EntryStoreClient.getRequest('/' + sourceContextId + '/export')
		assert export.getResponseCode() == HTTP_OK
		def imported = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + importedContextId + '/import',
			export.getInputStream(), 'admin', 'application/zip')
		assert imported.getResponseCode() == HTTP_OK

		getOrCreateContext([contextId: createdContextId])
	}

	def "GET cached-external-metadata as guest, Reference without es:metadata, resource grant only: 401"() {
		given:
		def ctx = importedContextId
		def entryId = 'resource-grant'
		assert metadataOf(ctx, entryId) == null
		addToInfo(ctx, entryId, resourceOf(ctx, entryId), NameSpaceConst.TERM_READ, guestUri)

		when:
		def connection = getCachedExternalMetadataAsGuest(ctx, entryId)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET cached-external-metadata as guest, Reference without es:metadata, metadata URI grant: 200"() {
		given:
		def ctx = importedContextId
		def entryId = 'metadata-grant'
		assert metadataOf(ctx, entryId) == null
		addToInfo(ctx, entryId, metadataUri(ctx, entryId), NameSpaceConst.TERM_READ, guestUri)

		when:
		def connection = getCachedExternalMetadataAsGuest(ctx, entryId)

		then:
		connection.getResponseCode() == HTTP_OK
	}

	def "GET cached-external-metadata as guest, Reference with es:metadata, resource grant only: 401"() {
		given:
		def entryId = createReferenceWithEntryGraph()
		addToInfo(createdContextId, entryId, resourceOf(createdContextId, entryId), NameSpaceConst.TERM_READ, guestUri)

		when:
		def connection = getCachedExternalMetadataAsGuest(createdContextId, entryId)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET cached-external-metadata as guest, Reference with es:metadata, metadata URI grant: 200"() {
		given:
		def entryId = createReferenceWithEntryGraph()
		addToInfo(createdContextId, entryId, metadataUri(createdContextId, entryId), NameSpaceConst.TERM_READ, guestUri)

		when:
		def connection = getCachedExternalMetadataAsGuest(createdContextId, entryId)

		then:
		connection.getResponseCode() == HTTP_OK
	}

	/** Creates a Reference with an entry graph, as EntryScape does, which gives it es:metadata. */
	private static String createReferenceWithEntryGraph() {
		def adminReadOnMetadata = [(base + '/' + createdContextId + '/metadata/_newId'):
									   [(NameSpaceConst.TERM_READ): [[type: 'uri', value: adminUri]]]]
		def entryId = createEntry(createdContextId, [entrytype: 'reference', resource: resourceUrl,
													 'cached-external-metadata': externalMetadataUrl],
			[info: adminReadOnMetadata])
		assert metadataOf(createdContextId, entryId) != null
		return entryId
	}

	private static HttpURLConnection getCachedExternalMetadataAsGuest(String contextId, String entryId) {
		return EntryStoreClient.getRequest('/' + contextId + '/cached-external-metadata/' + entryId, '')
	}

	/** The es:metadata of the entry graph, null if it has none. */
	private static Object metadataOf(String contextId, String entryId) {
		return getInfo(contextId, entryId)[entryUri(contextId, entryId)][NameSpaceConst.TERM_METADATA]
	}

	/** The resource URI as the entry graph states it. */
	private static String resourceOf(String contextId, String entryId) {
		def resources = getInfo(contextId, entryId)[entryUri(contextId, entryId)][NameSpaceConst.TERM_RESOURCE] as List
		return resources[0]['value']
	}

	private static String entryUri(String contextId, String entryId) {
		return base + '/' + contextId + '/entry/' + entryId
	}

	private static String metadataUri(String contextId, String entryId) {
		return base + '/' + contextId + '/metadata/' + entryId
	}

	private static Map getInfo(String contextId, String entryId) {
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(connection.inputStream.text)['info'] as Map
	}

	/** Adds a statement to the entry's info graph, keeping the statements it already has. */
	private static void addToInfo(String contextId, String entryId, String subject, String predicate, String object) {
		def info = getInfo(contextId, entryId)
		def predicates = (info[subject] ?: [:]) as Map
		predicates[predicate] = ((predicates[predicate] ?: []) as List) + [[type: 'uri', value: object]]
		info[subject] = predicates
		def connection = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId, JsonOutput.toJson(info))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}
}
