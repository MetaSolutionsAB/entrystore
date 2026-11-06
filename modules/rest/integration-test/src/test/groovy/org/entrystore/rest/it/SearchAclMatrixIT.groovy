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
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient
import org.apache.solr.common.SolrInputDocument
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import org.entrystore.rest.it.util.UserUtil

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import static org.awaitility.Awaitility.await

/**
 * Solr evaluates the read access of callers who are not admins itself (ENTRYSTORE-1120), so a search must find,
 * count and facet an entry exactly when the repository's access check lets the caller read the entry's metadata.
 * Each caller's expected entries are listed explicitly and also checked against the metadata endpoints, which ask the
 * access check itself.
 */
class SearchAclMatrixIT extends BaseSpec {

	static final String BASE = EntryStoreClient.baseUrl
	/** Owned by OWNER and readable by the members of the group; holds the entries of every ACL shape. */
	static final String CONTEXT = 'search-acl-matrix'
	/** Readable by guest; holds the references, so that only their targets decide. */
	static final String REF_CONTEXT = 'search-acl-matrix-refs'
	/** Readable by none of the callers who are not admins. */
	static final String PRIVATE_CONTEXT = 'search-acl-matrix-private'
	/** Writable, and so readable, by PLAIN through a write grant on the context. */
	static final String WRITE_CONTEXT = 'search-acl-matrix-write'
	/** Its context entry's metadata is readable by guest, its entries are not. */
	static final String META_CONTEXT = 'search-acl-matrix-refs-meta'

	static final String MARKER_IRI = 'http://example.org/ns/searchAclMatrixIT-marker'
	static final String MARKER_FIELD = literalField(MARKER_IRI)
	static final String MARKER = 'aclmatrixmarker'

	static final String GUEST = BASE + '/_principals/resource/_guest'
	static final String ADMIN = BASE + '/_principals/resource/_admin'
	static final String PASSWORD = 'aclMatrixPass123'
	// whitelisted for password login in entrystore-it.properties
	static final String PLAIN = 'searchAclMatrixPlain@test.com'
	static final String MEMBER = 'searchAclMatrixMember@test.com'
	static final String OWNER = 'searchAclMatrixOwner@test.com'
	static final List<String> SHAPES = ['inherited', 'own-user', 'own-group', 'own-users', 'own-guest',
										'own-excludes', 'own-user-write', 'own-resource-only', 'context-write',
										'ref-readable', 'ref-unreadable', 'ref-via-context-entry', 'self',
										'context-entry']

	/** Documents written straight into Solr, of entries in _contexts that name CONTEXT as their resource. */
	static final List<String> FAKE_CONTEXT_ENTRY_URIS = [BASE + '/_contexts/entry/search-acl-matrix-fake-link',
														 BASE + '/_contexts/entry/search-acl-matrix-fake-local']

	/** Per shape: its entry ID and URI, and the path whose GET answers whether a caller may read its metadata. */
	static Map<String, Map<String, String>> shapes = [:]
	static Map<String, Map> users = [:]
	static String deletedTargetReferenceUri
	static String privateContextReferenceUri
	/** Entry URIs whose Solr documents are rewritten as indexed before acl.explicit existed. */
	static Map<String, String> unmarkedUris = [:]

	def setupSpec() {
		EntryStoreClient.snapshotCreds()
		[PLAIN, MEMBER, OWNER].each { name ->
			EntryStoreClient.creds.put(name, PASSWORD)
			users[name] = UserUtil.createUserWithPassword(name, PASSWORD)
		}
		def groupEntryId = createEntry('_principals', [graphtype: 'group'], [resource: [name: 'searchAclMatrixGroup']])
		def groupUri = BASE + '/_principals/resource/' + groupEntryId
		putJson('/_principals/resource/' + groupEntryId, [users[MEMBER]['entryId']])

		getOrCreateContext([contextId: CONTEXT])
		def ownerUri = users[OWNER]['resourceUri']
		putJson('/_contexts/entry/' + CONTEXT, [
			(BASE + '/_contexts/entry/' + CONTEXT): [(NameSpaceConst.TERM_WRITE): [uriValue(ownerUri)]],
			(BASE + '/' + CONTEXT)                : [(NameSpaceConst.TERM_READ): [uriValue(groupUri)]]])
		getOrCreateContext([contextId: REF_CONTEXT])
		putJson('/_contexts/entry/' + REF_CONTEXT,
			[(BASE + '/' + REF_CONTEXT): [(NameSpaceConst.TERM_READ): [uriValue(GUEST)]]])
		getOrCreateContext([contextId: PRIVATE_CONTEXT])
		def plainUri = users[PLAIN]['resourceUri']
		getOrCreateContext([contextId: WRITE_CONTEXT])
		putJson('/_contexts/entry/' + WRITE_CONTEXT,
			[(BASE + '/' + WRITE_CONTEXT): [(NameSpaceConst.TERM_WRITE): [uriValue(plainUri)]]])
		getOrCreateContext([contextId: META_CONTEXT])
		putJson('/_contexts/entry/' + META_CONTEXT,
			[(BASE + '/_contexts/metadata/' + META_CONTEXT): [(NameSpaceConst.TERM_READ): [uriValue(GUEST)]]])

		addEntryShape('inherited', null)
		addEntryShape('own-user', grant('metadata', NameSpaceConst.TERM_READ, plainUri))
		addEntryShape('own-group', grant('metadata', NameSpaceConst.TERM_READ, groupUri))
		addEntryShape('own-users', grant('metadata', NameSpaceConst.TERM_READ, BASE + '/_principals/resource/_users'))
		addEntryShape('own-guest', grant('metadata', NameSpaceConst.TERM_READ, GUEST))
		// an ACL of its own replaces the context's, so the group's read on the context does not reach this entry
		addEntryShape('own-excludes', grant('metadata', NameSpaceConst.TERM_READ, ADMIN))
		// write on the metadata implies reading it
		addEntryShape('own-user-write', grant('metadata', NameSpaceConst.TERM_WRITE, plainUri))
		// reading the resource does not imply reading the metadata, and the ACL replaces the context's
		addEntryShape('own-resource-only', grant('resource', NameSpaceConst.TERM_READ, GUEST))
		addEntryShape('context-write', null, WRITE_CONTEXT)
		addReferenceShape('ref-readable', 'own-guest')
		addReferenceShape('ref-unreadable', 'inherited')
		// neither the reference nor its context is readable, only its context entry's metadata and its target
		addReferenceShape('ref-via-context-entry', 'own-guest', META_CONTEXT)
		addSelfShape()
		putJson('/_contexts/metadata/' + CONTEXT, markerMetadata(BASE + '/' + CONTEXT))
		shapes['context-entry'] = [uri      : BASE + '/_contexts/entry/' + CONTEXT,
								   readCheck: '/_contexts/metadata/' + CONTEXT]

		// not carrying MARKER, so that the marker search keeps finding the shapes only
		def newResourceIri = BASE + '/' + CONTEXT + '/resource/_newId'
		def guestRead = grant('metadata', NameSpaceConst.TERM_READ, GUEST)
		def deletedTargetId = createEntry(CONTEXT, [graphtype: 'string'], [resource: 'deleted target',
			metadata: markerMetadata(newResourceIri, 'aclmatrixdeletedtarget'), info: guestRead])
		def deletedTargetReferenceId = createReference(REF_CONTEXT, deletedTargetId)
		deletedTargetReferenceUri = BASE + '/' + REF_CONTEXT + '/entry/' + deletedTargetReferenceId
		def publicTargetId = createEntry(CONTEXT, [graphtype: 'string'], [resource: 'public target',
			metadata: markerMetadata(newResourceIri, 'aclmatrixpublictarget'), info: guestRead])
		def privateContextReferenceId = createReference(PRIVATE_CONTEXT, publicTargetId)
		privateContextReferenceUri = BASE + '/' + PRIVATE_CONTEXT + '/entry/' + privateContextReferenceId
		def deletedTargetQuery = 'uri:"' + BASE + '/' + CONTEXT + '/entry/' + deletedTargetId + '"'
		awaitAdminSearchCount(deletedTargetQuery, 1)
		def deleteConn = EntryStoreClient.deleteRequest('/' + CONTEXT + '/entry/' + deletedTargetId)
		assert deleteConn.getResponseCode() == HTTP_NO_CONTENT

		// the admin search drops the deleted entry's row itself, so only Solr's facet shows that the document is gone
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until {
				def conn = EntryStoreClient.getRequest('/search' +
					convertMapToQueryParams([type: 'solr', query: deletedTargetQuery, facetFields: 'uri']))
				conn.getResponseCode() == HTTP_OK
					&& bucketsOf(JSON_PARSER.parseText(conn.inputStream.text), 'uri').isEmpty()
			}
		awaitAdminSearchCount(MARKER_FIELD + ':' + MARKER, SHAPES.size())
		awaitAdminSearchCount('uri:"' + privateContextReferenceUri + '"', 1)

		// what a Link in _contexts, which may claim graph type Context, and a renamed Local entry there would index;
		// naming guest as owner, they must not stand in for CONTEXT's entry (the guest row of the marker search)
		writeSolrDocuments([
			fakeContextEntry(FAKE_CONTEXT_ENTRY_URIS[0], 'Link', 'Context'),
			fakeContextEntry(FAKE_CONTEXT_ENTRY_URIS[1], 'Local', 'None')])

		addUnmarkedDocuments()
	}

	def cleanupSpec() {
		deleteSolrDocuments(FAKE_CONTEXT_ENTRY_URIS)
		EntryStoreClient.restoreCreds()
	}

	private static String solrCoreUrl() {
		return 'http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core'
	}

	/** Writes documents straight into Solr, replacing those with the same uri, and makes them searchable. */
	private static void writeSolrDocuments(List<Map<String, Object>> documents) {
		new HttpJettySolrClient.Builder(solrCoreUrl()).useHttp1_1(true).build().withCloseable { client ->
			client.add(documents.collect { fields ->
				def document = new SolrInputDocument()
				fields.each { name, value -> document.setField(name, value) }
				document
			})
			client.commit()
		}
	}

	private static void deleteSolrDocuments(List<String> uris) {
		new HttpJettySolrClient.Builder(solrCoreUrl()).useHttp1_1(true).build().withCloseable { client ->
			client.deleteById(uris)
			client.commit()
		}
	}

	private static Map<String, Object> fakeContextEntry(String uri, String entryType, String graphType) {
		return [uri      : uri, resource: BASE + '/' + CONTEXT, context: BASE + '/_contexts',
				entryType: entryType, graphType: graphType, 'acl.explicit': true, 'acl.admin': [GUEST]]
	}

	/**
	 * Two entries of the guest-readable REF_CONTEXT whose Solr documents are rewritten as indexed before acl.explicit
	 * existed: one without an ACL of its own, one whose own ACL grants only the admin.
	 */
	private void addUnmarkedDocuments() {
		def inheritedId = createEntry(REF_CONTEXT, [graphtype: 'string'], [resource: 'unmarked inherited',
			metadata: markerMetadata(BASE + '/' + REF_CONTEXT + '/resource/_newId', 'aclmatrixunmarkedinherited')])
		def ownAclId = createEntry(REF_CONTEXT, [graphtype: 'string'], [resource: 'unmarked own acl',
			metadata: markerMetadata(BASE + '/' + REF_CONTEXT + '/resource/_newId', 'aclmatrixunmarkedownacl'),
			info    : [(BASE + '/' + REF_CONTEXT + '/metadata/_newId'):
						   [(NameSpaceConst.TERM_READ): [uriValue(ADMIN)]]]])
		unmarkedUris['inherited'] = BASE + '/' + REF_CONTEXT + '/entry/' + inheritedId
		unmarkedUris['ownAcl'] = BASE + '/' + REF_CONTEXT + '/entry/' + ownAclId
		unmarkedUris.values().each { awaitAdminSearchCount('uri:"' + it + '"', 1) }

		writeSolrDocuments([
			unmarkedDocument(inheritedId, 'aclmatrixunmarkedinherited', [:]),
			unmarkedDocument(ownAclId, 'aclmatrixunmarkedownacl', ['acl.metadata.r': [ADMIN]])])
		unmarkedUris.values().each { awaitAdminSearchCount('uri:"' + it + '" -acl.explicit:*', 1) }
	}

	private static Map<String, Object> unmarkedDocument(String entryId, String markerValue, Map aclFields) {
		return [uri      : BASE + '/' + REF_CONTEXT + '/entry/' + entryId,
				resource : BASE + '/' + REF_CONTEXT + '/resource/' + entryId, context: BASE + '/' + REF_CONTEXT,
				entryType: 'Local', graphType: 'String', (MARKER_FIELD): [markerValue]] + aclFields
	}

	private static Map uriValue(String uri) {
		return [type: 'uri', value: uri]
	}

	private static Map markerMetadata(String resourceIri, String value = MARKER) {
		return [(resourceIri): [(MARKER_IRI): [[type: 'literal', value: value]]]]
	}

	/** Entry information of a new entry of CONTEXT granting the grantee read or write on its metadata or resource. */
	private static Map grant(String metadataOrResource, String readOrWrite, String grantee) {
		return [(BASE + '/' + CONTEXT + '/' + metadataOrResource + '/_newId'): [(readOrWrite): [uriValue(grantee)]]]
	}

	private static void putJson(String path, Object body) {
		assert EntryStoreClient.putRequest(path, JsonOutput.toJson(body)).getResponseCode() == HTTP_NO_CONTENT
	}

	/** An entry carrying the marker, with the given entry information as its own ACL, or none if null. */
	private static void addEntryShape(String shape, Map info, String contextId = CONTEXT) {
		def body = [resource: shape, metadata: markerMetadata(BASE + '/' + contextId + '/resource/_newId')]
		if (info != null) {
			body.info = info
		}
		def entryId = createEntry(contextId, [graphtype: 'string'], body)
		shapes[shape] = [entryId  : entryId, uri: BASE + '/' + contextId + '/entry/' + entryId,
						 readCheck: '/' + contextId + '/metadata/' + entryId]
	}

	/**
	 * A reference without an ACL of its own to the local metadata of another shape's entry. Its indexed metadata is
	 * the target's, and reading it is checked on the target.
	 */
	private static void addReferenceShape(String shape, String targetShape, String contextId = REF_CONTEXT) {
		def referenceId = createReference(contextId, shapes[targetShape]['entryId'])
		shapes[shape] = [uri      : BASE + '/' + contextId + '/entry/' + referenceId,
						 readCheck: '/' + contextId + '/cached-external-metadata/' + referenceId]
	}

	/** Creates a reference in the given context to the local metadata of an entry of CONTEXT; returns its ID. */
	private static String createReference(String contextId, String targetId) {
		return createEntry(contextId, [entrytype: 'reference',
			resource: 'https://example.org/search-acl-matrix/' + contextId + '/' + targetId,
			'cached-external-metadata': BASE + '/' + CONTEXT + '/metadata/' + targetId])
	}

	/**
	 * The plain user's own entry, readable through no ACL but the self rule: the users group's read on its metadata
	 * is replaced by one for the admin only.
	 */
	private static void addSelfShape() {
		def entryId = users[PLAIN]['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryInformation = JSON_PARSER.parseText(entryConn.inputStream.text)['info'] as Map
		entryInformation[BASE + '/_principals/metadata/' + entryId] =
			[(NameSpaceConst.TERM_READ): [uriValue(BASE + '/_principals/resource/_admin')]]
		putJson('/_principals/entry/' + entryId, entryInformation)
		putJson('/_principals/metadata/' + entryId, markerMetadata(users[PLAIN]['resourceUri'].toString()))
		shapes['self'] = [uri: BASE + '/_principals/entry/' + entryId, readCheck: '/_principals/metadata/' + entryId]
	}

	/** The repository's decision: whether the caller may GET a metadata graph, which checks ReadMetadata. */
	private static boolean mayRead(String caller, String path) {
		def code = EntryStoreClient.getRequest(path, caller).getResponseCode()
		assert code in [HTTP_OK, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN]
		return code == HTTP_OK
	}

	private static Set<String> childUrisOf(Object respJson) {
		return respJson['resource']['children']
			.collect { BASE + '/' + it['contextId'] + '/entry/' + it['entryId'] } as Set
	}

	def "GET /search?type=solr for the marker as #callerName should find, count and facet exactly the shapes it may read"() {
		given:
		def mismatchesBefore = getSolrStatus()['readFilterMismatches']
		def accessCheckReadable = SHAPES.findAll { mayRead(caller, shapes[it]['readCheck']) }
		def expectedUris = readableShapes.collect { shapes[it]['uri'] } as Set

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: MARKER_FIELD + ':' + MARKER, limit: '20', facetFields: 'uri']), caller)

		then:
		// the expected shapes are the access check's own decision, not a copy of its rules
		accessCheckReadable as Set == readableShapes as Set
		conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] == expectedUris.size()
		childUrisOf(respJson) == expectedUris
		bucketsOf(respJson, 'uri') == expectedUris.collectEntries { [(it): 1] }
		// the access check on every row agreed with the filter, so no count included an unreadable entry
		getSolrStatus()['readFilterMismatches'] == mismatchesBefore

		where:
		caller             | readableShapes
		''                 | ['own-guest', 'ref-readable', 'ref-via-context-entry']
		PLAIN              | ['own-user', 'own-users', 'own-guest', 'own-user-write', 'context-write', 'ref-readable',
							  'ref-via-context-entry', 'self']
		MEMBER             | ['inherited', 'own-group', 'own-users', 'own-guest', 'ref-readable', 'ref-unreadable',
							  'ref-via-context-entry']
		// a context owner reads every entry of the context, whatever the entry's own ACL says
		OWNER              | SHAPES - ['self', 'context-write']
		'userInAdminGroup' | SHAPES
		'admin'            | SHAPES
		callerName = caller ?: 'guest'
	}

	def "GET /search?type=solr as guest should judge a document indexed before acl.explicit existed by its acl fields (#shape)"() {
		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: 'uri:"' + unmarkedUris[shape] + '"', limit: '10', facetFields: 'uri']), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] == (found ? 1 : 0)
		bucketsOf(respJson, 'uri') == (found ? [(unmarkedUris[shape]): 1] : [:])

		where:
		shape       | found
		// no acl field at all: no ACL of its own, so the guest-readable context decides
		'inherited' | true
		// any acl field means an ACL of its own, which replaces the context's and grants guest nothing
		'ownAcl'    | false
	}

	def "GET /search?type=solr as #callerName should find a local reference whose target was deleted only as an admin"() {
		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: 'uri:"' + deletedTargetReferenceUri + '"', limit: '10']), caller)

		then:
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['results'] == (found ? 1 : 0)

		where:
		caller  | found
		''      | false
		PLAIN   | false
		'admin' | true
		callerName = caller ?: 'guest'
	}

	def "GET /search?type=solr as #callerName should find a local reference in a context it cannot read only as an admin, though its target is public"() {
		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
			[type: 'solr', query: 'uri:"' + privateContextReferenceUri + '"', limit: '10', facetFields: 'context']),
			caller)

		then:
		conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] == (found ? 1 : 0)
		// the reference's own fields, such as its context, must not surface in facets either
		bucketsOf(respJson, 'context') == (found ? [(BASE + '/' + PRIVATE_CONTEXT): 1] : [:])

		where:
		caller  | found
		''      | false
		PLAIN   | false
		OWNER   | false
		'admin' | true
		callerName = caller ?: 'guest'
	}
}
