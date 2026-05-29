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

import static java.net.HttpURLConnection.HTTP_OK

/**
 * Exercises the happy paths through {@code SetUserURIAfterAuthenticationFilter} via the public
 * REST surface. The contamination guarantee (unconditional reset to guest URI before each request)
 * is pinned deterministically by the unit tests in {@code SetUserURIAfterAuthenticationFilterTest};
 * IT level can only observe HTTP-side effects, and Jetty's thread pool may or may not reuse the
 * same worker across our two requests, so the cross-request scenario here is a best-effort
 * regression net rather than a deterministic proof.
 *
 * Error-path branches (unknown principal type, deleted cookie user, missing-URI user) are not
 * exercised here because the auth providers currently wired in {@code SecurityConfig} only
 * produce {@code Saml2Authentication}, {@code CasAuthenticationToken}, or
 * {@code UsernamePasswordAuthenticationToken} with an {@code ESUserSessionDetails} principal —
 * none of which reach those branches from a real HTTP request. If a new auth provider is wired
 * in, this IT should grow a matching scenario.
 */
class SetUserURIAfterAuthenticationFilterIT extends BaseSpec {

	def "GET /auth/user without login returns the guest user (unconditional reset path)"() {
		when:
		def connection = EntryStoreClient.getRequest('/auth/user', '')

		then:
		connection.getResponseCode() == HTTP_OK
		def body = JSON_PARSER.parseText(connection.inputStream.getText())
		body['id'] == '_guest'
		body['user'] == 'guest'
	}

	def "GET /auth/user as admin returns admin, then guest request on the next call returns guest"() {
		given: "an authenticated admin call lands on a Jetty worker that sets the admin URI"
		def adminConnection = EntryStoreClient.getRequest('/auth/user', 'admin')

		expect: "the admin call resolves to admin"
		adminConnection.getResponseCode() == HTTP_OK
		def adminBody = JSON_PARSER.parseText(adminConnection.inputStream.getText())
		adminBody['id'] != '_guest'
		adminBody['user'] == 'admin'

		when: "a follow-up guest call hits the same shared app (possibly on the same worker thread)"
		def guestConnection = EntryStoreClient.getRequest('/auth/user', '')

		then: "the guest call resolves to guest — never inherits admin from the previous request's ThreadLocal"
		guestConnection.getResponseCode() == HTTP_OK
		def guestBody = JSON_PARSER.parseText(guestConnection.inputStream.getText())
		guestBody['id'] == '_guest'
		guestBody['user'] == 'guest'
	}
}
