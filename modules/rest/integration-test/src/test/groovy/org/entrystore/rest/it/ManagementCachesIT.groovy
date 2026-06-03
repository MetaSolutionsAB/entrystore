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

class ManagementCachesIT extends BaseSpec {

	def "GET /management/caches as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/caches', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/caches as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/caches', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/caches as admin should list the registered Caffeine caches"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/caches')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		// The single CacheManager bean is registered under its @Bean method name 'cacheManager'.
		def caches = responseJson['cacheManagers']['cacheManager']['caches'] as Map
		caches != null
		// These four caches are owned by always-present beans (built unconditionally), so they are
		// registered regardless of optional-auth configuration.
		caches.containsKey('login-attempt-counters')
		caches.containsKey('login-lockouts')
		caches.containsKey('saml-auth-state')
		caches.containsKey('saml2-authn-requests')
	}

	def "GET /management/caches as userInAdminGroup should list the registered Caffeine caches"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/caches', 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		def caches = responseJson['cacheManagers']['cacheManager']['caches'] as Map
		caches.containsKey('saml-auth-state')
	}
}
