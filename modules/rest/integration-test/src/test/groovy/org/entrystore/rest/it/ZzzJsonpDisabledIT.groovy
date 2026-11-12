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

// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzJsonpDisabledIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startOwnedApp(['--entrystore.jsonp=false'])
	}

	// Intentionally no cleanupSpec — see ZzzCsrfDisabledIT.

	def "GET with ?callback= and a session cookie should be authenticated and not wrapped when JSONP is off"() {
		given:
		def login = EntryStoreClient.loginIsolated('admin')

		when:
		def conn = EntryStoreClient.getRequest('/auth/user?callback=cb', '', 'application/json',
				[Cookie: login.authCookie])

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().startsWith('application/json')
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'admin'
	}
}
