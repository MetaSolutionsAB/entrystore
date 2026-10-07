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

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementStatusIT extends BaseSpec {

	def "GET /management/status as guest should reply with text status, when text Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'text/plain')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/plain')
		connection.inputStream.text == 'UP'
	}

	def "GET /management/status as guest should reply with json status, when json Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', '', 'application/json')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['repositoryStatus'] == 'online'
		responseJson['version'] != null
		(responseJson['version'] as String).length() > 2
	}

	def "GET /management/status as admin should reply with json status 'online', when no Accept header is defined"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status', null, null)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['repositoryStatus'] == 'online'
		responseJson['version'] != null
		(responseJson['version'] as String).length() > 2
	}

	// Spring boot defaults to JSON when no content type is specified
	def "GET /management/status/extended as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(connection.errorStream.text)
		resp['status'] == 401
		resp['error'] == 'Not authorized'
		resp['path'] == EntryStoreClient.contextPath + '/management/status/extended'
		resp['timestamp'] != null
	}

	def "GET /management/status/extended as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(connection.errorStream.text)
		resp['status'] == 403
		resp['error'] == 'Not authorized'
		resp['path'] == EntryStoreClient.contextPath + '/management/status/extended'
		resp['timestamp'] != null
	}

	def "GET /management/status/extended as admin should reply with detailed status"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['version'] != null
		responseJson['jvm'] != null
		responseJson['baseURI'] != null
		responseJson['repositoryType'] == 'memory'
		responseJson['solr'] != null
		responseJson['solr']['enabled']
		responseJson['solr']['status'] == 'online'
		responseJson['startupTime'] != null
		responseJson['stats'] == null
		// Reported from the same EchoProperties bean EchoService enforces, so this pins both the wiring
		// and the unit: bytes, not megabytes, and no longer the -1 placeholder.
		responseJson['echoMaxEntitySize'] == 10 * 1024 * 1024
		// entrystore-it.properties enables both with "on", so this pins the relaxed binding that gates them
		responseJson['auth']['signup'] == true
		responseJson['auth']['passwordReset'] == true
	}

	def "GET /management/status/extended?include=countStats as admin should reply with detailed status and stats for admin user"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?include=countStats')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['version'] != null
		responseJson['jvm'] != null
		responseJson['baseURI'] != null
		responseJson['repositoryType'] == 'memory'
		responseJson['solr'] != null
		responseJson['solr']['enabled']
		responseJson['solr']['status'] == 'online'
		responseJson['startupTime'] != null
		responseJson['countStats'] != null
		responseJson['countStats']['groupCount'] != null
		responseJson['countStats']['userCount'] != null
		responseJson['countStats']['contextCount'] != null
		responseJson['countStats']['tripleCount'] != null
		responseJson['countStats']['namedGraphCount'] != null
	}

	def "GET /management/status/extended?includeStats as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/status/extended?includeStats', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(connection.errorStream.text)
		resp['status'] == 401
		resp['error'] == 'Not authorized'
		resp['path'] == EntryStoreClient.contextPath + '/management/status/extended'
		resp['timestamp'] != null
	}
}
