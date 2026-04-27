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
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementShutdownIT extends BaseSpec {

	def "POST /management/shutdown as guest should respond with 401 Unauthorized"() {
		when:
		def conn = EntryStoreClient.postRequest('/management/shutdown', null, '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /management/shutdown as non-admin user should respond with 403 Forbidden"() {
		when:
		def conn = EntryStoreClient.postRequest('/management/shutdown', null, 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	// The admin-success case is intentionally not tested here because it would
	// shut down the shared test server and break all subsequent integration tests.
	// The underlying shutdown behavior is provided by Spring Boot Actuator.
}
