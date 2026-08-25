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

// Companion to ZzzServerHeaderDefaultIT — same pattern, exercises version-precision=none so a
// regression that swaps composeDefault's empty-suffix branch for the slash-version branch (e.g.
// emits 'EntryStore/' or 'EntryStore/<runtime>' under precision=none) is caught in CI.
class ZzzServerHeaderNoneIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()

		startOwnedApp([
			'--entrystore.http.header.server=',
			'--entrystore.http.header.server.version-precision=none'
		])
	}

	def "with blank explicit override and version-precision=none, Server header is bare 'EntryStore'"() {
		when:
		def conn = EntryStoreClient.getRequest('/auth/user', '', null)

		then:
		conn.getResponseCode() == HTTP_OK
		def serverHeader = conn.getHeaderField('Server')
		serverHeader == 'EntryStore'
	}
}
