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
import org.entrystore.rest.it.util.UserUtil
import org.springframework.http.HttpMethod

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

/**
 * {@code GET /{context-id}/entry/{entry-id}} for callers who may read neither the metadata nor the resource of an
 * entry, in a context neither the guest nor {@code user} is a member of. They get the entry information reduced to
 * the types, the URIs of the entry's parts, the creation and modification dates and the home context; callers with
 * read access get it in full.
 */
class EntryInfoReductionIT extends BaseSpec {

	def static contextId = 'entry-info-reduction-it'
	def static contextName = 'entryInfoReductionContext'
	def static contextGroupId

	def static base = EntryStoreClient.baseUrl
	def static guestUri = base + '/_principals/resource/_guest'
	def static adminUri = base + '/_principals/resource/_admin'

	static final String DC_CREATOR = NameSpaceConst.DC_TERM_CREATOR
	static final String DC_CONTRIBUTOR = NameSpaceConst.DC_TERMS + 'contributor'
	static final String DC_FORMAT = NameSpaceConst.DC_TERM_FORMAT
	static final String DC_EXTENT = NameSpaceConst.DC_TERMS + 'extent'
	static final String DC_CREATED = NameSpaceConst.DC_TERMS + 'created'
	static final String DC_MODIFIED = NameSpaceConst.DC_TERMS + 'modified'
	static final String RDFS_LABEL = 'http://www.w3.org/2000/01/rdf-schema#label'
	static final String ES_RELATION = NameSpaceConst.ES_TERMS + 'relation'
	static final String ES_CACHED = NameSpaceConst.ES_TERMS + 'cached'

	static final String DATE_ETAG = /"\d+"/
	static final String REDUCED_ETAG = /"\d+-r"/

	/** The predicates a reduced info graph may have on the entry URI. */
	static final Set<String> ENTRY_PREDICATES = [NameSpaceConst.RDF_TYPE, NameSpaceConst.TERM_RESOURCE,
		NameSpaceConst.TERM_METADATA, NameSpaceConst.TERM_EXTERNAL_METADATA,
		NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA, ES_RELATION, DC_CREATED, DC_MODIFIED] as Set

	/** The predicates a reduced info graph may have on the resource URI. */
	static final Set<String> RESOURCE_PREDICATES = [NameSpaceConst.RDF_TYPE, NameSpaceConst.TERM_HOME_CONTEXT] as Set

	def setupSpec() {
		contextGroupId = createContext([contextId: contextId, name: contextName])
	}

	def "GET /{context-id}/entry/{entry-id} as admin on a file entry should return the creator and the file details"() {
		given:
		def entryId = createFileEntry()

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, 'admin')

		then:
		def entry = info[entryUri(entryId)]
		entry[DC_CREATOR] != null
		def resource = info[resourceUri(entryId)]
		resource[RDFS_LABEL] != null
		resource[DC_FORMAT] != null
		resource[DC_EXTENT] != null
	}

	def "GET /{context-id}/entry/{entry-id} as guest on a private file entry should return only the reduced info graph"() {
		given:
		def entryId = createFileEntry()

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, '')

		then:
		assertReduced(info, entryId)
		info[entryUri(entryId)][NameSpaceConst.TERM_RESOURCE] != null
		info[entryUri(entryId)][NameSpaceConst.TERM_METADATA] != null
		info[entryUri(entryId)][DC_CREATED] != null
		info[entryUri(entryId)][DC_MODIFIED] != null
	}

	def "GET /{context-id}/entry/{entry-id} as non-member user on a private file entry should not return the creator, contributors or file details"() {
		given:
		def entryId = createFileEntry()

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, 'user')

		then:
		assertReduced(info, entryId)
		!JsonOutput.toJson(info).contains(DC_CREATOR)
		!JsonOutput.toJson(info).contains(DC_CONTRIBUTOR)
		!JsonOutput.toJson(info).contains(RDFS_LABEL)
		!JsonOutput.toJson(info).contains(DC_EXTENT)
	}

	def "GET /{context-id}/entry/{entry-id} as guest in N-Triples on a private file entry should not return the creator or file details"() {
		given:
		def entryId = createFileEntry()

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, '',
			'application/n-triples')

		then:
		connection.getResponseCode() == HTTP_OK
		def body = connection.inputStream.text
		body.contains('<' + entryUri(entryId) + '> <' + NameSpaceConst.TERM_RESOURCE + '>')
		!body.contains(DC_CREATOR)
		!body.contains(DC_CONTRIBUTOR)
		!body.contains(RDFS_LABEL)
		!body.contains(DC_FORMAT)
		!body.contains(DC_EXTENT)
	}

	def "GET /{context-id}/entry/{entry-id}?callback= as guest on a private file entry should not return the creator"() {
		given:
		def entryId = createFileEntry()

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?callback=cb', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def body = connection.inputStream.text
		body.startsWith('cb(')
		body.contains(entryUri(entryId))
		!body.contains(DC_CREATOR)
		!body.contains(RDFS_LABEL)
	}

	def "GET /{context-id}/entry/{entry-id} as guest on an entry with its own ACL should not return the ACL principals"() {
		given:
		def entryId = createEntry(contextId, [:])
		def aclUserUri = base + '/_principals/resource/' + UserUtil.createUserEntry('entryInfoReductionAclUser')
		addToInfo(entryId, metadataUri(entryId), NameSpaceConst.TERM_READ, aclUserUri)
		addToInfo(entryId, resourceUri(entryId), NameSpaceConst.TERM_WRITE, aclUserUri)

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, '')

		then:
		assertReduced(info, entryId)
		!JsonOutput.toJson(info).contains(aclUserUri)
		!JsonOutput.toJson(info).contains(NameSpaceConst.TERM_READ)
		!JsonOutput.toJson(info).contains(NameSpaceConst.TERM_WRITE)
	}

	def "GET /{context-id}/entry/{entry-id} as a user granted only ReadMetadata should return the full info graph"() {
		given:
		def entryId = createFileEntry()
		addToInfo(entryId, metadataUri(entryId), NameSpaceConst.TERM_READ, userUri('user'))

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, 'user')

		then:
		info[entryUri(entryId)][DC_CREATOR] != null
		info[resourceUri(entryId)][RDFS_LABEL] != null
		info[metadataUri(entryId)][NameSpaceConst.TERM_READ] != null
	}

	def "GET /{context-id}/entry/{entry-id} as a user granted only ReadResource should return the full info graph"() {
		given:
		def entryId = createFileEntry()
		addToInfo(entryId, resourceUri(entryId), NameSpaceConst.TERM_READ, userUri('user'))

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, 'user')

		then:
		info[entryUri(entryId)][DC_CREATOR] != null
		info[resourceUri(entryId)][RDFS_LABEL] != null
		info[resourceUri(entryId)][NameSpaceConst.TERM_READ] != null
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a private List entry should return the reduced info graph with rights and relations"() {
		given:
		def listId = createEntry(contextId, [graphtype: 'list'])

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + listId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		assertReduced(json['info'] as Map, listId)
		json['info'][resourceUri(listId)][NameSpaceConst.RDF_TYPE].collect { it['value'] }
			.contains(NameSpaceConst.TERM_LIST)
		json['rights'] == []
		json['relations'] != null
		json['resource'] == [:]
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll as guest on a public list should reduce the info of the children the guest may not read"() {
		given:
		def listId = createEntry(contextId, [graphtype: 'list'])
		addToInfo(listId, resourceUri(listId), NameSpaceConst.TERM_READ, guestUri)
		def privateChildId = createEntry(contextId, [:])
		def publicChildId = createEntry(contextId, [:])
		addToInfo(publicChildId, metadataUri(publicChildId), NameSpaceConst.TERM_READ, guestUri)
		def addChildren = EntryStoreClient.putRequest('/' + contextId + '/resource/' + listId,
			JsonOutput.toJson([privateChildId, publicChildId]))
		assert addChildren.getResponseCode() < 300

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/entry/' + listId + '?includeAll', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def children = JSON_PARSER.parseText(connection.inputStream.text)['resource']['children'] as List
		def privateChild = children.find { it['entryId'] == privateChildId }
		assertReduced(privateChild['info'] as Map, privateChildId)
		privateChild['info'][entryUri(privateChildId)][NameSpaceConst.TERM_RESOURCE] != null
		privateChild['rights'] == []
		def publicChild = children.find { it['entryId'] == publicChildId }
		publicChild['info'][entryUri(publicChildId)][DC_CREATOR] != null
	}

	def "GET /{context-id}/entry/{entry-id} as guest on a private link entry should return only the reduced info graph"() {
		given:
		def entryId = createEntry(contextId, [entrytype: 'link', resource: 'https://example.org/private-link'])

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, '')

		then:
		assertReduced(info, entryId, 'https://example.org/private-link')
		info[entryUri(entryId)][NameSpaceConst.RDF_TYPE].collect { it['value'] }.contains(NameSpaceConst.TERM_LINK)
		info[entryUri(entryId)][NameSpaceConst.TERM_RESOURCE][0]['value'] == 'https://example.org/private-link'
	}

	def "GET /{context-id}/entry/{entry-id} as guest on a private link reference entry should keep the external metadata URIs"() {
		given:
		def entryId = createEntry(contextId, [entrytype                 : 'linkreference',
											  resource                  : 'https://example.org/private-linkref',
											  'cached-external-metadata': 'https://example.org/private-linkref-md'])

		when:
		def info = getInfo('/' + contextId + '/entry/' + entryId, '')

		then:
		assertReduced(info, entryId, 'https://example.org/private-linkref')
		def entry = info[entryUri(entryId)]
		entry[NameSpaceConst.TERM_EXTERNAL_METADATA][0]['value'] == 'https://example.org/private-linkref-md'
		entry[NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA] != null
	}

	def "GET /_principals/entry/{entry-id} as guest on a user entry should keep the home context and drop the creator"() {
		given:
		def userEntryId = UserUtil.createUserEntry('entryInfoReductionHomeUser', contextId)

		when:
		def info = getInfo('/_principals/entry/' + userEntryId, '')

		then:
		assertReduced(info, userEntryId, base + '/_principals/resource/' + userEntryId, '_principals')
		info[base + '/_principals/resource/' + userEntryId][NameSpaceConst.TERM_HOME_CONTEXT] != null
	}

	def "GET /_principals/entry/{entry-id} as the user itself should return its full info graph"() {
		given:
		def userEntryId = EntryStoreClient.createdEsUsers['user']['entryId'].toString()

		when:
		def info = getInfo('/_principals/entry/' + userEntryId, 'user')

		then:
		info[base + '/_principals/entry/' + userEntryId][DC_CREATOR] != null
	}

	def "GET /_principals/entry/{entry-id} as guest on a context group should keep the home context and drop the creator"() {
		when:
		def info = getInfo('/_principals/entry/' + contextGroupId, '')

		then:
		assertReduced(info, contextGroupId, base + '/_principals/resource/' + contextGroupId, '_principals')
		info[base + '/_principals/resource/' + contextGroupId][NameSpaceConst.TERM_HOME_CONTEXT] != null
	}

	def "GET /_contexts/entry/{entry-id} as guest on a private context should keep the name and drop the creator"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + contextId, '')

		then:
		connection.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		json['name'] == contextName
		assertReduced(json['info'] as Map, contextId, base + '/' + contextId, '_contexts')
	}

	def "GET /_contexts/entry/{entry-id} as admin on a context should return the creator"() {
		when:
		def info = getInfo('/_contexts/entry/' + contextId, 'admin')

		then:
		info[base + '/_contexts/entry/' + contextId][DC_CREATOR] != null
	}

	def "GET /{context-id}/entry/{entry-id} with the ETag of a reader should answer 200 with the reduced info after its read right is revoked"() {
		given:
		def entryId = createEntry(contextId, [:])
		def groupId = createGroupWithReadAccessTo(entryId)
		setGroupMembers(groupId, [userEntryId('user')])
		def path = '/' + contextId + '/entry/' + entryId
		def first = EntryStoreClient.getRequest(path, 'user')
		assert first.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(first.inputStream.text)['info'][entryUri(entryId)][DC_CREATOR] != null
		def etag = first.getHeaderField('ETag')
		setGroupMembers(groupId, [])

		when:
		def conn = EntryStoreClient.getRequest(path, 'user', 'application/json', ['If-None-Match': etag])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Last-Modified') == first.getHeaderField('Last-Modified')
		conn.getHeaderField('ETag') ==~ REDUCED_ETAG
		def info = JSON_PARSER.parseText(conn.inputStream.text)['info'] as Map
		assertReduced(info, entryId)
		info[entryUri(entryId)][DC_CREATOR] == null
	}

	def "GET /{context-id}/entry/{entry-id} in Turtle with the ETag of a non-reader should answer 200 with the full info after a read right is granted"() {
		given:
		def entryId = createEntry(contextId, [:])
		def groupId = createGroupWithReadAccessTo(entryId)
		def path = '/' + contextId + '/entry/' + entryId
		def first = EntryStoreClient.getRequest(path, 'user', 'text/turtle')
		assert first.getResponseCode() == HTTP_OK
		assert !first.inputStream.text.contains('dcterms:creator')
		def etag = first.getHeaderField('ETag')
		assert etag ==~ REDUCED_ETAG
		setGroupMembers(groupId, [userEntryId('user')])

		when:
		def conn = EntryStoreClient.getRequest(path, 'user', 'text/turtle', ['If-None-Match': etag])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Last-Modified') == first.getHeaderField('Last-Modified')
		conn.getHeaderField('ETag') ==~ DATE_ETAG
		conn.inputStream.text.contains('dcterms:creator')
	}

	def "HEAD /{context-id}/entry/{entry-id} as guest in JSON should answer with the reduced ETag of the GET"() {
		given:
		def entryId = createEntry(contextId, [:])
		def path = '/' + contextId + '/entry/' + entryId
		def get = EntryStoreClient.getRequest(path, '', 'application/json')
		assert get.getResponseCode() == HTTP_OK

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, path, null, '', null, [Accept: 'application/json'])

		then:
		head.getResponseCode() == HTTP_OK
		head.getHeaderField('ETag') ==~ REDUCED_ETAG
		head.getHeaderField('ETag') == get.getHeaderField('ETag')
		head.getHeaderField('Last-Modified') == get.getHeaderField('Last-Modified')
	}

	def "HEAD /{context-id}/entry/{entry-id} as guest in RDF/XML should answer with the reduced ETag of the GET"() {
		given:
		def entryId = createEntry(contextId, [:])
		def path = '/' + contextId + '/entry/' + entryId
		def get = EntryStoreClient.getRequest(path, '', 'application/rdf+xml')
		assert get.getResponseCode() == HTTP_OK

		when:
		def head = EntryStoreClient.sendRequestAsStream(HttpMethod.HEAD, path, null, '', null, [Accept: 'application/rdf+xml'])

		then:
		head.getResponseCode() == HTTP_OK
		head.getHeaderField('ETag') ==~ REDUCED_ETAG
		head.getHeaderField('ETag') == get.getHeaderField('ETag')
		head.getHeaderField('Last-Modified') == get.getHeaderField('Last-Modified')
	}

	def "GET a private context entry with includeAll as guest, then its group from the relations, should answer 200 with the reduced info"() {
		when:
		def contextConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextId + '?includeAll', '')
		def contextJson = JSON_PARSER.parseText(contextConn.inputStream.text)
		// the referrers are the group and the users with this home context; the group is told apart by its type
		def referrers = (contextJson['relations'] as Map)
			.findAll { subject, predicates -> predicates[NameSpaceConst.TERM_HOME_CONTEXT] != null }
			.keySet()
		def principalConns = referrers.collectEntries { uri ->
			[(uri): EntryStoreClient.getRequest(uri.replace('/resource/', '/entry/') + '?includeAll', '')]
		}
		def principalJsons = principalConns.collectEntries { uri, conn ->
			[(uri): JSON_PARSER.parseText(conn.inputStream.text)]
		}
		def groupResourceUri = principalJsons.find { uri, json ->
			json['info'][uri]?.get(NameSpaceConst.RDF_TYPE)?.collect { it['value'] }?.contains(NameSpaceConst.TERM_GROUP)
		}?.key
		def groupJson = principalJsons[groupResourceUri]

		then:
		contextConn.getResponseCode() == HTTP_OK
		contextJson['rights'] == []
		assertReduced(contextJson['info'] as Map, contextId, base + '/' + contextId, '_contexts')
		principalConns.values().every { it.getResponseCode() == HTTP_OK }
		groupResourceUri == base + '/_principals/resource/' + contextGroupId
		groupJson['rights'] == []
		assertReduced(groupJson['info'] as Map, contextGroupId, groupResourceUri, '_principals')
		groupJson['info'][groupResourceUri][NameSpaceConst.TERM_HOME_CONTEXT] != null
		groupJson['info'][base + '/_principals/entry/' + contextGroupId][DC_CREATOR] == null
	}

	private static String entryUri(String entryId, String ctx = contextId) {
		return base + '/' + ctx + '/entry/' + entryId
	}

	private static String resourceUri(String entryId) {
		return base + '/' + contextId + '/resource/' + entryId
	}

	private static String metadataUri(String entryId) {
		return base + '/' + contextId + '/metadata/' + entryId
	}

	private static String userUri(String username) {
		return EntryStoreClient.createdEsUsers[username]['resourceUri']
	}

	private static Map getInfo(String path, String asUser) {
		def connection = EntryStoreClient.getRequest(path, asUser)
		assert connection.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(connection.inputStream.text)['info'] as Map
	}

	/**
	 * Asserts that the RDF/JSON info graph has statements only on the entry URI and the resource URI, each with an
	 * allowed predicate, apart from es:cached on the cached external metadata URI.
	 */
	private static void assertReduced(Map info, String entryId, String resource = resourceUri(entryId),
									  String ctx = contextId) {
		def entry = entryUri(entryId, ctx)
		assert info[entry] != null
		info.each { subject, predicates ->
			def allowed = subject == entry ? ENTRY_PREDICATES
				: subject == resource ? RESOURCE_PREDICATES
				: [ES_CACHED] as Set
			assert allowed.containsAll((predicates as Map).keySet()): 'unexpected predicates on ' + subject
		}
	}

	private static String createFileEntry() {
		def entryId = createEntry(contextId, [:])
		def file = createTempBinaryFile('entry-info-reduction', '.bin', 'private bytes'.bytes)
		def upload = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, file, 'admin',
			'application/octet-stream')
		assert upload.getResponseCode() == HTTP_CREATED
		return entryId
	}

	/** Creates a group whose members may read the entry's metadata; the group starts without members. */
	private static String createGroupWithReadAccessTo(String entryId) {
		def groupId = createEntry('_principals', [graphtype: 'group'])
		addToInfo(entryId, metadataUri(entryId), NameSpaceConst.TERM_READ, base + '/_principals/resource/' + groupId)
		return groupId
	}

	private static void setGroupMembers(String groupId, List<String> memberEntryIds) {
		def connection = EntryStoreClient.putRequest('/_principals/resource/' + groupId,
			JsonOutput.toJson(memberEntryIds))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}

	private static String userEntryId(String username) {
		return EntryStoreClient.createdEsUsers[username]['entryId'].toString()
	}

	/** Adds a statement to the entry's info graph, keeping the statements it already has. */
	private static void addToInfo(String entryId, String subject, String predicate, String object) {
		def info = getInfo('/' + contextId + '/entry/' + entryId, 'admin')
		def predicates = (info[subject] ?: [:]) as Map
		predicates[predicate] = ((predicates[predicate] ?: []) as List) + [[type: 'uri', value: object]]
		info[subject] = predicates
		def connection = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId, JsonOutput.toJson(info))
		assert connection.getResponseCode() == HTTP_NO_CONTENT
	}
}
