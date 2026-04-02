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

import static java.net.HttpURLConnection.HTTP_ACCEPTED
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class SolrManagementIT extends BaseSpec {

	def static contextId = 'solrMgmtTestCtx'
	def static contextEntryUri = EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "POST /management/solr full reindex as guest should respond with 401 Unauthorized"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /management/solr full reindex as non-admin user should respond with 403 Forbidden"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /management/solr full reindex as admin should respond with 202 Accepted"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_ACCEPTED

		cleanup:
		waitForSolrProcessing()
	}

	def "POST /management/solr with unknown command should respond with 400 Bad Request"() {
		given:
		def body = JsonOutput.toJson([command: 'unknownCommand'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /management/solr with missing command should respond with 400 Bad Request"() {
		given:
		def body = JsonOutput.toJson([:])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /management/solr per-context reindex as admin should respond with 202 Accepted"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex', context: contextEntryUri])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_ACCEPTED

		cleanup:
		waitForSolrProcessing()
	}

	def "POST /management/solr per-context reindex as guest should respond with 401 Unauthorized"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex', context: contextEntryUri])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /management/solr per-context reindex with invalid context URI should respond with 400 Bad Request"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex', context: 'not a valid uri'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /management/solr per-context reindex with non-existent context should respond with 400 Bad Request"() {
		given:
		def body = JsonOutput.toJson([command: 'reindex', context: EntryStoreClient.baseUrl + '/_contexts/entry/nonexistent999'])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'admin')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /management/solr per-context reindex as non-admin with Administer access should respond with 202 Accepted"() {
		given: 'grant Administer access (es:write on entry URI) to non-admin user on context'
		def userResourceUri = EntryStoreClient.createdEsUsers['user']['resourceUri']
		def aclBody = JsonOutput.toJson([(contextEntryUri): [
				(NameSpaceConst.TERM_WRITE): [[type: 'uri', value: userResourceUri]]
		]])
		def aclConn = EntryStoreClient.putRequest('/_contexts/entry/' + contextId, aclBody, 'admin')
		assert aclConn.getResponseCode() == HTTP_NO_CONTENT

		def body = JsonOutput.toJson([command: 'reindex', context: contextEntryUri])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'user')

		then:
		conn.getResponseCode() == HTTP_ACCEPTED

		cleanup:
		waitForSolrProcessing()
	}

	def "POST /management/solr per-context reindex as non-admin without Administer access should respond with 403 Forbidden"() {
		given: 'remove Administer access from non-admin user on context'
		def aclBody = JsonOutput.toJson([(contextEntryUri): [:]])
		def aclConn = EntryStoreClient.putRequest('/_contexts/entry/' + contextId, aclBody, 'admin')
		assert aclConn.getResponseCode() == HTTP_NO_CONTENT

		def body = JsonOutput.toJson([command: 'reindex', context: contextEntryUri])

		when:
		def conn = EntryStoreClient.postRequest('/management/solr', body, 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}
}
