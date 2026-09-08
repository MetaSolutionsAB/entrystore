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

	// Owned by unconditional beans, so registered regardless of optional-auth configuration.
	static final List<String> ALWAYS_PRESENT_CACHES = [
		'login-attempt-counters', 'login-lockouts',
		'saml-auth-state', 'saml2-authn-requests',
		'oidc-auth-state', 'oauth2-authz-requests',
		'signup-tokens',
	]

	// Present only because entrystore-it.properties enables their owners:
	// entrystore.auth.http-basic.enabled=true and entrystore.message.rate.limit.max=3.
	static final List<String> CONFIG_ENABLED_CACHES = ['password-verification', 'rate-limit-message']

	// A limiter disabled by configuration (max=0 in entrystore-it.properties) holds no cache and must
	// not be listed — this pins the disabled-limiter contract of CaffeineCacheSource end to end.
	static final List<String> CONFIG_DISABLED_CACHES = ['rate-limit-signup', 'rate-limit-password-reset', 'rate-limit-search']

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
		caches.keySet().containsAll(ALWAYS_PRESENT_CACHES)
		caches.keySet().containsAll(CONFIG_ENABLED_CACHES)
		caches.keySet().disjoint(CONFIG_DISABLED_CACHES)
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
