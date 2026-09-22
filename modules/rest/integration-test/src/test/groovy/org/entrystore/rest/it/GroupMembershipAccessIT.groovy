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

import static java.net.HttpURLConnection.*

/**
 * Pins the no-staleness contract of the user-to-groups authorization cache through the REST API: a group grant
 * must take effect, and be revoked, on the very next request after each membership mutation.
 */
class GroupMembershipAccessIT extends BaseSpec {

	def static contextId = 'group-membership-access-ctx'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "group read grant is honoured and revoked on the very next request after each membership change"() {
		given: 'an entry whose own ACL grants metadata read only to a freshly created group'
		def entryId = createEntry(contextId, [graphtype: 'string'], [resource: 'members only'])
		def entryPath = '/' + contextId + '/entry/' + entryId
		// The entry-info graph itself is served to everyone; ReadMetadata guards the metadata route.
		def metadataPath = '/' + contextId + '/metadata/' + entryId
		def metadataUri = EntryStoreClient.baseUrl + metadataPath
		def userEntryId = EntryStoreClient.createdEsUsers['user']['entryId'].toString()
		def readAsUser = { EntryStoreClient.getRequest(metadataPath, 'user').getResponseCode() }

		def groupEntryId = createEntry('_principals', [graphtype: 'group'],
				[resource: [name: 'groupMembershipAccessIT']])
		def groupResourcePath = '/_principals/resource/' + groupEntryId
		def groupResourceUri = EntryStoreClient.baseUrl + groupResourcePath

		def aclBody = JsonOutput.toJson([(metadataUri): [
				(NameSpaceConst.TERM_READ): [[type: 'uri', value: groupResourceUri]]
		]])
		assert EntryStoreClient.putRequest(entryPath, aclBody).getResponseCode() == HTTP_NO_CONTENT
		// The entry ACL now overrides whatever the context grants. Not a member yet: denied, and this
		// decision is the one that caches the user's group set (only the built-in _users group).
		assert readAsUser() == HTTP_FORBIDDEN

		when: 'the user is added to the group'
		def addConn = EntryStoreClient.putRequest(groupResourcePath, JsonOutput.toJson([userEntryId]))

		then:
		addConn.getResponseCode() == HTTP_NO_CONTENT
		readAsUser() == HTTP_OK

		when: 'the user is removed from the group'
		def removeConn = EntryStoreClient.putRequest(groupResourcePath, JsonOutput.toJson([]))

		then:
		removeConn.getResponseCode() == HTTP_NO_CONTENT
		readAsUser() == HTTP_FORBIDDEN

		when: 'the user is added again'
		def reAddConn = EntryStoreClient.putRequest(groupResourcePath, JsonOutput.toJson([userEntryId]))

		then:
		reAddConn.getResponseCode() == HTTP_NO_CONTENT
		readAsUser() == HTTP_OK

		when: 'the granting group is deleted'
		def deleteConn = EntryStoreClient.deleteRequest('/_principals/entry/' + groupEntryId)

		then:
		deleteConn.getResponseCode() == HTTP_NO_CONTENT
		readAsUser() == HTTP_FORBIDDEN
	}
}
