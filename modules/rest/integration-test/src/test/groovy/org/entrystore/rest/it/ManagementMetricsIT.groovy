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

import static java.net.HttpURLConnection.HTTP_BAD_METHOD
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementMetricsIT extends BaseSpec {

	def setupSpec() {
		// Ensure the http.server.requests timer has been sampled at least once before
		// the specs that assert on its presence in the metrics listing.
		def warmup = EntryStoreClient.getRequest('/management/status', '', 'text/plain')
		assert warmup.getResponseCode() == HTTP_OK
	}

	def "GET /management/metrics as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/metrics as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/metrics as admin should reply with list of meter names"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['names'] != null
		def namesList = responseJson['names'] as List
		namesList.size() > 0
		namesList.contains('http.server.requests')
		namesList.contains('jvm.memory.used')
	}

	def "GET /management/metrics/http.server.requests as admin should reply with meter details"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics/http.server.requests')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['name'] == 'http.server.requests'
		responseJson['measurements'] != null
		(responseJson['measurements'] as List).size() > 0
		responseJson['measurements'].any { it['statistic'] == 'COUNT' }
		responseJson['availableTags'] != null
	}

	def "GET /management/metrics/http.server.requests as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics/http.server.requests', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/metrics/http.server.requests as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics/http.server.requests', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/metrics as userInAdminGroup should reply with list of meter names"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics', 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['names'] != null
		(responseJson['names'] as List).size() > 0
	}

	def "POST /management/metrics as admin should reply with 405 Method Not Allowed"() {
		when:
		def connection = EntryStoreClient.postRequest('/management/metrics', null)

		then:
		connection.getResponseCode() == HTTP_BAD_METHOD
	}

	def "GET /management/metrics/nonexistent.metric as admin should reply with 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/metrics/nonexistent.metric')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /management as admin should reply with 404 (actuator discovery disabled)"() {
		when:
		def connection = EntryStoreClient.getRequest('/management')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /management/health as admin should reply with 404 (actuator endpoint not exposed)"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/health')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /management/env as admin should reply with 404 (actuator endpoint not exposed)"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/env')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}
}
