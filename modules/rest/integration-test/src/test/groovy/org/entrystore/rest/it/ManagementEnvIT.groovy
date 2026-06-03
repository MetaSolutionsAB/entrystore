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

class ManagementEnvIT extends BaseSpec {

	def "GET /management/env as guest should reply with Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/env', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/env as non-admin user should reply with Forbidden"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/env', 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		connection.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/env as admin should list property sources"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/env')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		(responseJson['propertySources'] as List).size() > 0
	}

	def "GET /management/env for the admin-password override should return a masked value"() {
		when:
		// entrystore.auth.adminpw is set in entrystore-it.properties; its key ends in 'adminpw', which
		// none of the conventional credential patterns catch — so this exercises the custom sanitizer.
		def connection = EntryStoreClient.getRequest('/management/env/entrystore.auth.adminpw')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['property']['value'] ==~ /\*+/
		responseJson['property']['value'] != 'adminpass'
	}

	def "GET /management/env for a URL-valued non-credential key should redact the userinfo"() {
		when:
		// entrystore.test.backend-url is a URL-valued, non-credential-shaped key (see entrystore-it.properties),
		// so only urlCredentialSanitizer acts on it — redacting user:pass@ while leaving the rest visible.
		def connection = EntryStoreClient.getRequest('/management/env/entrystore.test.backend-url')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['property']['value'] == 'https://******@backend.test/db'
	}

	def "GET /management/env for a non-secret property should return its real value"() {
		when:
		// java.version is always present as a system property and is not sensitive, so WHEN_AUTHORIZED
		// reveals it to the admin — proving values are shown and the masking above is selective.
		def connection = EntryStoreClient.getRequest('/management/env/java.version')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		def version = responseJson['property']['value'] as String
		version ==~ /\d.*/
	}

	def "GET /management/env as userInAdminGroup should list property sources"() {
		when:
		def connection = EntryStoreClient.getRequest('/management/env', 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		(responseJson['propertySources'] as List).size() > 0
	}
}
