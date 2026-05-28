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

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static org.entrystore.rest.springboot.configuration.CacheControlFilter.CACHE_CONTROL_AUTHENTICATED

class CacheControlIT extends BaseSpec {

	def "GET /auth/user as authenticated user returns Cache-Control: private, no-store"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', 'admin', null)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
	}

	def "authenticated GET against a non-/auth API endpoint returns Cache-Control: private, no-store"() {
		when:
		def conn = EntryStoreClient.getRequest('/60/entry/randomEntryId/index', 'admin')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
	}

	def "GET /auth/user as anonymous guest does not set Cache-Control"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', null)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == null
	}

	def "anonymous GET carrying an unrelated cookie does not set Cache-Control"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', null, [Cookie: 'tracking_id=abc123'])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == null
	}

	def "GET with Authorization: Basic and no session cookie returns Cache-Control: private, no-store"() {
		when:
		def basicAuth = 'Basic ' + Base64.getEncoder().encodeToString('admin:adminpass'.getBytes())
		def conn = EntryStoreClient.getRequest('/auth/user', '', null, ['Authorization': basicAuth])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getHeaderField('Cache-Control') == CACHE_CONTROL_AUTHENTICATED
	}

	def "anonymous GET against permit-all controller endpoint /management/status does not receive aggressive cache headers"() {
		when:
		def conn = EntryStoreClient.getRequest('/management/status', '', 'text/plain')

		then:
		conn.getResponseCode() == HTTP_OK
		def cacheControl = conn.getHeaderField('Cache-Control')
		// Acceptance criterion from ENTRYSTORE-945: "public/unauthenticated responses may use
		// Cache-Control: public where appropriate" — what we want to rule out is Spring Security's
		// default 'no-cache, no-store, max-age=0, must-revalidate' (or any subset of those
		// no-cache/no-store/must-revalidate tokens) leaking onto a permit-all, non-static,
		// body-returning controller endpoint.
		cacheControl == null || (
				!cacheControl.contains('no-cache')
						&& !cacheControl.contains('no-store')
						&& !cacheControl.contains('must-revalidate'))
	}
}
