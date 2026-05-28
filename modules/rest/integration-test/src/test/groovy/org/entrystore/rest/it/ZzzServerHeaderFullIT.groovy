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
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication

import static java.net.HttpURLConnection.HTTP_OK

// Companion to ZzzServerHeaderDefaultIT — same pattern, exercises version-precision=full so a
// regression where ServerHeaderCustomizer ignored precision or fell through to bare 'EntryStore'
// for the unconstrained-precision branch is caught in CI.
class ZzzServerHeaderFullIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()

		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.http.header.server=',
			'--entrystore.http.header.server.version-precision=full'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	def "with blank explicit override and version-precision=full, Server header preserves the runtime version verbatim"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', null)

		then:
		conn.getResponseCode() == HTTP_OK
		def serverHeader = conn.getHeaderField('Server')
		serverHeader != null
		// FULL mode passes the runtime version through verbatim. Asserting the shape (digits +
		// optional dotted segments + optional Maven qualifier) keeps the test resilient to version
		// bumps while still catching a regression that collapses to bare 'EntryStore'.
		serverHeader ==~ /EntryStore\/\d+(\.\d+)*([-+]\S+)?/
		serverHeader != 'EntryStore'
	}
}
