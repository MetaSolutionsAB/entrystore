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
import spock.lang.Stepwise

import static java.net.HttpURLConnection.HTTP_OK

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
@Stepwise
class ZzzSearchRateLimitIT extends BaseSpec {

	static final String QUERY = '/search?type=solr&query=description.pl:opissearch'

	def setupSpec() {
		stopPreexistingAppIfRunning()

		startOwnedApp([
			'--entrystore.auth.recaptcha.url=' + getRecaptchaStubUrl(),
			'--entrystore.solr.search.rate.limit.max=3',
			'--entrystore.solr.search.rate.limit.window=10m'
		])
	}

	// Intentionally no cleanupSpec — matches the canonical pattern of ZzzCasLoginIT,
	// ZzzSamlLoginIT and ZzzServerHeader*IT. The next
	// lifecycle-owning IT's stopPreexistingAppIfRunning() closes our appInstance;
	// resetting appInstance=null or appStarted=false here would violate BaseSpec
	// invariant #2 (see the invariant comment above appStarted in BaseSpec) and force an unnecessary re-init of
	// the shared application, whose Jetty rebind to port 8181 is racy with the OS
	// socket-release timer (the cause of ZzzPasswordResetRateLimiterIT flakiness
	// before this fix).

	def "GET /search — first request from a guest IP is allowed"() {
		when:
		def conn = EntryStoreClient.getRequest(QUERY, '')

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "GET /search — second request from same guest IP is allowed"() {
		when:
		def conn = EntryStoreClient.getRequest(QUERY, '')

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "GET /search — third request from same guest IP is allowed (window cap reached)"() {
		when:
		def conn = EntryStoreClient.getRequest(QUERY, '')

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "GET /search — fourth request from same guest IP is rate-limited (429)"() {
		when:
		def conn = EntryStoreClient.getRequest(QUERY, '')

		then:
		conn.getResponseCode() == HTTP_TOO_MANY_REQUESTS
	}

	def "GET /search — request from a different X-Forwarded-For IP gets its own budget"() {
		when:
		def conn = EntryStoreClient.getRequest(QUERY, '', 'application/json',
			['X-Forwarded-For': '203.0.113.42'])

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "GET /search — the same X-Forwarded-For IP is rate-limited once its budget is used"() {
		when:
		def codes = (1..3).collect {
			EntryStoreClient.getRequest(QUERY, '', 'application/json',
				['X-Forwarded-For': '203.0.113.42']).getResponseCode()
		}

		then:
		codes == [HTTP_OK, HTTP_OK, HTTP_TOO_MANY_REQUESTS]
	}

	def "GET /search — client addresses that are not IP addresses share one budget"() {
		when:
		def codes = searchCodes([
			['Forwarded': 'for=unknown'],
			['Forwarded': 'for=unknown'],
			['X-Forwarded-For': 'not-an-ip'],
			['Forwarded': 'for=_hidden']
		])

		then:
		codes == [HTTP_OK, HTTP_OK, HTTP_OK, HTTP_TOO_MANY_REQUESTS]
	}

	def "GET /search — an IPv6 client has its own budget, shared across Forwarded and X-Forwarded-For"() {
		when:
		def codes = searchCodes([
			['Forwarded': 'for="[2001:db8::1]:4711"'],
			['Forwarded': 'for="[2001:db8::1]:4711"'],
			['X-Forwarded-For': '2001:DB8:0:0:0:0:0:1'],
			['X-Forwarded-For': '2001:DB8:0:0:0:0:0:1']
		])

		then: 'the bucket for addresses that are not IP addresses is used up, so a first 200 needs a budget of its own'
		codes == [HTTP_OK, HTTP_OK, HTTP_OK, HTTP_TOO_MANY_REQUESTS]
	}

	def "GET /search — an X-Forwarded-For address with a port has the budget of its IP address"() {
		when: 'each request comes from another port, as behind Azure Application Gateway'
		def codes = searchCodes([
			['X-Forwarded-For': '203.0.113.50:51001'],
			['X-Forwarded-For': '203.0.113.50:51002'],
			['X-Forwarded-For': '203.0.113.50:51003'],
			['X-Forwarded-For': '203.0.113.50:51004']
		])

		then:
		codes == [HTTP_OK, HTTP_OK, HTTP_OK, HTTP_TOO_MANY_REQUESTS]
	}

	/** One guest search per header map, in order; returns the response codes. */
	private static List<Integer> searchCodes(List<Map<String, String>> headersPerRequest) {
		headersPerRequest.collect { EntryStoreClient.getRequest(QUERY, '', 'application/json', it).getResponseCode() }
	}
}
