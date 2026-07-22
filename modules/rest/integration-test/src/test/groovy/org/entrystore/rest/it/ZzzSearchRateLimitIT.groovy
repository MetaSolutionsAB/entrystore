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
			'--entrystore.solr.search.rate.limit.window=10m',
			'--entrystore.trust.x-forwarded-for=true'
		])
	}

	// Intentionally no cleanupSpec — matches the canonical pattern of ZzzCasLoginIT,
	// ZzzSamlLoginIT, ZzzMultipartSizeLimitIT, and ZzzServerHeader*IT. The next
	// lifecycle-owning IT's stopPreexistingAppIfRunning() closes our appInstance;
	// resetting appInstance=null or appStarted=false here would violate BaseSpec
	// invariant #2 (see BaseSpec.groovy:64-72) and force an unnecessary re-init of
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
}
