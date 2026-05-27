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

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
//
// All other Server-header ITs in ServerHeaderIT.groovy run against the shared-app lifecycle with
// entrystore.http.header.server=EntryStore/IntegrationTests pinned in entrystore-it.properties,
// which exercises the explicit-override branch of ServerHeaderCustomizer. This class boots the app
// with that override blanked out so the auto-default branch (read VERSION.txt, apply precision)
// is exercised end-to-end — covering the wiring between Spring property binding, the customizer,
// and the actual runtime version.
class ZzzServerHeaderDefaultIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()

		def args = [
			'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
			'--entrystore.http.header.server=',
			'--entrystore.http.header.server.version-precision=major'
		] as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
	}

	// Intentionally no cleanupSpec — matches the Zzz convention used by ZzzCasLoginIT etc.

	def "with blank explicit override and version-precision=major, Server header is EntryStore/<major>"() {
		when: 'guest request — Server header is stamped on every response regardless of auth status'
		def conn = EntryStoreClient.getRequest('/auth/user', '', null)

		then:
		conn.getResponseCode() == HTTP_OK
		def serverHeader = conn.getHeaderField('Server')
		serverHeader != null
		// MAJOR truncates to a single numeric segment (no qualifier, no dot). Asserting the shape
		// rather than a literal version keeps the test resilient to version bumps.
		serverHeader ==~ /EntryStore\/\d+/
	}
}
