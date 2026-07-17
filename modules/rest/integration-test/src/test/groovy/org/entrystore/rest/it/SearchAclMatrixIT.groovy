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
import spock.lang.Shared

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

/**
 * ENTRYSTORE-1088 (A4): the Solr principal fq pre-filter must produce exactly the same visible
 * result sets as the application-level ACL check alone. This matrix seeds one entry per ACL
 * shape — no entry ACL (context-inherited), direct user grant, group grant, foreign grant,
 * guest grant, and LinkReferences pinning both sides of the reference conjunction (self and
 * target readable → visible; target denied → hidden) — and pins the exact visibility per
 * caller, cross-checked against the metadata endpoint (the authorization ground truth). Any
 * under-inclusion by the pre-filter fails here.
 */
class SearchAclMatrixIT extends BaseSpec {

	def static contextId = '92'
	def static ownedContextId = '93'
	def static MARKER = 'aclmatrixmarker'

	@Shared def entries = [:]         // name -> entryId
	@Shared String groupResourceUri

	def setupSpec() {
		getOrCreateContext([contextId: contextId])

		def userResourceUri = EntryStoreClient.createdEsUsers['user']['resourceUri'].toString()
		def userEntryId = EntryStoreClient.createdEsUsers['user']['entryId'].toString()
		def guestResourceUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'

		// a principal the caller 'user' does NOT hold
		def otherUser = UserUtil.createUser('aclmatrixother', null, false)
		def otherResourceUri = otherUser['resourceUri'].toString()

		// a group with 'user' as its only member
		def groupConn = EntryStoreClient.postRequest('/_principals?graphtype=group',
			JsonOutput.toJson([resource: [name: 'aclmatrixgroup']]))
		def groupEntryId = JSON_PARSER.parseText(groupConn.inputStream.text)['entryId'].toString()
		groupResourceUri = EntryStoreClient.baseUrl + '/_principals/resource/' + groupEntryId
		def memberConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId,
			JsonOutput.toJson([userEntryId]))
		assert memberConn.getResponseCode() == HTTP_NO_CONTENT

		entries.eNone = createMarkedEntry('none')
		entries.eUser = createMarkedEntry('directuser')
		entries.eGroup = createMarkedEntry('groupgrant')
		entries.eOther = createMarkedEntry('foreigngrant')
		entries.eGuest = createMarkedEntry('guestgrant')

		grantReadMetadata(contextId, entries.eUser as String, userResourceUri)
		grantReadMetadata(contextId, entries.eGroup as String, groupResourceUri)
		grantReadMetadata(contextId, entries.eOther as String, otherResourceUri)
		grantReadMetadata(contextId, entries.eGuest as String, guestResourceUri)

		// LinkReferences wrapping LOCAL metadata: search requires ReadMetadata on the referring
		// entry itself (ContextManagerImpl.getEntry) AND on the referenced target (the
		// LocalMetadataWrapper branch of the backstop). Pin both sides of that conjunction:
		// eRefBoth — self readable by 'user', target (eUser) readable by 'user' -> visible;
		// eRefTargetDenied — self readable by 'user', target (eOther) not readable -> hidden.
		entries.eRefBoth = createLocalLinkReference(entries.eUser as String, 'refboth')
		grantReadMetadata(contextId, entries.eRefBoth as String, userResourceUri)
		entries.eRefTargetDenied = createLocalLinkReference(entries.eOther as String, 'reftargetdenied')
		grantReadMetadata(contextId, entries.eRefTargetDenied as String, userResourceUri)

		// Context-owner bypass: 'user' administers context 93, so every entry there is readable
		// for 'user' regardless of entry-level ACLs — a pre-filter without the administered-
		// contexts clause would wrongly exclude eOwned for 'user'
		getOrCreateContext([contextId: ownedContextId])
		def ownedCtxEntryUri = EntryStoreClient.baseUrl + '/_contexts/entry/' + ownedContextId
		def ctxEntryConn = EntryStoreClient.getRequest('/_contexts/entry/' + ownedContextId)
		assert ctxEntryConn.getResponseCode() == HTTP_OK
		def ctxInfo = JSON_PARSER.parseText(ctxEntryConn.inputStream.text)['info'] as Map
		def ctxSubject = (ctxInfo[ownedCtxEntryUri] ?: [:]) as Map
		def ctxWrites = (ctxSubject[NameSpaceConst.TERM_WRITE] ?: []) as List
		ctxWrites << [type: 'uri', value: userResourceUri]
		ctxSubject[NameSpaceConst.TERM_WRITE] = ctxWrites
		ctxInfo[ownedCtxEntryUri] = ctxSubject
		def ownerAclConn = EntryStoreClient.putRequest('/_contexts/entry/' + ownedContextId,
			JsonOutput.toJson(ctxInfo))
		assert ownerAclConn.getResponseCode() == HTTP_NO_CONTENT
		def ownedResourceUrl = 'https://aclmatrix.example.com/owned'
		entries.eOwned = createEntry(ownedContextId, [entrytype: 'link', resource: ownedResourceUrl],
			[metadata: [(ownedResourceUrl): [(NameSpaceConst.DC_TERM_TITLE):
				[[type: 'literal', value: MARKER + ' owned']]]]])
		grantReadMetadata(ownedContextId, entries.eOwned as String, otherResourceUri)

		Thread.sleep(100)
		waitForSolrProcessing()
		Thread.sleep(1500)
	}

	private static String createLocalLinkReference(String targetEntryId, String suffix) {
		def targetMdUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + targetEntryId
		def targetResourceUri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + targetEntryId
		def refParams = [entrytype: 'linkreference', resource: targetResourceUri, 'cached-external-metadata': targetMdUri]
		def refBody = [metadata: [(targetResourceUri): [(NameSpaceConst.DC_TERM_TITLE):
			[[type: 'literal', value: MARKER + ' ' + suffix]]]]]
		return createEntry(contextId, refParams, refBody)
	}

	private static String createMarkedEntry(String suffix) {
		// the Solr title field is extracted from metadata triples whose subject is the entry's
		// RESOURCE URI — for links that is the external target URL, not the local resource URI
		def resourceUrl = 'https://aclmatrix.example.com/' + suffix
		def params = [entrytype: 'link', resource: resourceUrl]
		def body = [metadata: [(resourceUrl): [(NameSpaceConst.DC_TERM_TITLE):
			[[type: 'literal', value: MARKER + ' ' + suffix]]]]]
		return createEntry(contextId, params, body)
	}

	private static void grantReadMetadata(String inContextId, String entryId, String principalResourceUri) {
		// PUT /entry replaces the whole entry-info graph, so fetch it and add the ACL triple to
		// the existing graph instead of overwriting structural triples with an ACL-only body
		def entryConn = EntryStoreClient.getRequest('/' + inContextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def info = JSON_PARSER.parseText(entryConn.inputStream.text)['info'] as Map
		def mdUri = EntryStoreClient.baseUrl + '/' + inContextId + '/metadata/' + entryId
		def subject = (info[mdUri] ?: [:]) as Map
		def reads = (subject[NameSpaceConst.TERM_READ] ?: []) as List
		reads << [type: 'uri', value: principalResourceUri]
		subject[NameSpaceConst.TERM_READ] = reads
		info[mdUri] = subject
		def aclConn = EntryStoreClient.putRequest('/' + inContextId + '/entry/' + entryId, JsonOutput.toJson(info))
		assert aclConn.getResponseCode() == HTTP_NO_CONTENT
	}

	private Set<String> visibleEntryIds(String asUser) {
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=title:' + MARKER, asUser)
		assert conn.getResponseCode() == HTTP_OK
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		// entry ids repeat across contexts (eNone and eOwned are both '1'), so qualify with context
		return (respJson['resource']['children'].collect { it['contextId'].toString() + '/' + it['entryId'] }) as Set
	}

	private static String key(String inContextId, Object entryId) {
		return inContextId + '/' + entryId
	}

	private static boolean canReadMetadata(String inContextId, String entryId, String asUser) {
		def conn = EntryStoreClient.getRequest('/' + inContextId + '/metadata/' + entryId, asUser)
		return conn.getResponseCode() == HTTP_OK
	}

	def "admin sees the whole matrix"() {
		expect:
		visibleEntryIds('admin') == (entries.collect { name, id ->
			key(name == 'eOwned' ? ownedContextId : contextId, id)
		} as Set)
	}

	def "admin-group member sees the whole matrix like admin"() {
		expect:
		// The pre-filter exempts admin-group members entirely (buildAclPreFilterQueryForCaller
		// returns null). If that exemption regresses, the fq excludes foreign-ACL entries at the
		// Solr level and the app-level backstop can never restore them — a permanent
		// under-inclusion for admin-group superusers.
		visibleEntryIds('userInAdminGroup') == (entries.collect { name, id ->
			key(name == 'eOwned' ? ownedContextId : contextId, id)
		} as Set)
	}

	def "guest sees only the guest-granted entry"() {
		expect:
		visibleEntryIds('') == ([key(contextId, entries.eGuest)] as Set)
	}

	def "user sees direct, group, guest, owned-context grants and the reference whose self and target are both readable"() {
		expect:
		visibleEntryIds('user') == ([key(contextId, entries.eUser), key(contextId, entries.eGroup),
									 key(contextId, entries.eGuest), key(contextId, entries.eRefBoth),
									 key(ownedContextId, entries.eOwned)] as Set)
	}

	def "search visibility exactly matches the authorization ground truth"() {
		given:
		// for every (caller, entry) pair, search shows exactly what the caller may read: plain
		// entries by their own ReadMetadata; references by ReadMetadata on BOTH the reference
		// itself and its wrapped local target (the getEntry + LocalMetadataWrapper conjunction)
		def mismatches = []
		['', 'user', 'admin', 'userInAdminGroup'].each { caller ->
			def visible = visibleEntryIds(caller)
			entries.each { name, id ->
				def entryContext = name == 'eOwned' ? ownedContextId : contextId
				def direct = switch (name) {
					case 'eRefBoth' -> canReadMetadata(contextId, id as String, caller) &&
						canReadMetadata(contextId, entries.eUser as String, caller)
					case 'eRefTargetDenied' -> canReadMetadata(contextId, id as String, caller) &&
						canReadMetadata(contextId, entries.eOther as String, caller)
					case 'eOwned' -> canReadMetadata(ownedContextId, id as String, caller)
					default -> canReadMetadata(contextId, id as String, caller)
				}
				if (visible.contains(key(entryContext, id)) != direct) {
					mismatches << "caller='${caller}' ${name}(${key(entryContext, id)}): search=${visible.contains(key(entryContext, id))} direct=${direct}"
				}
			}
		}

		expect:
		mismatches == []
	}
}
