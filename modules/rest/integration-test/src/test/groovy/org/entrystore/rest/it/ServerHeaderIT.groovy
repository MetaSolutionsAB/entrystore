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

class ServerHeaderIT extends BaseSpec {

	def static contextId = '60'

	def "GET /auth/user should include Server header starting with EntryStore/"() {
		when:
		def info = EntryStoreClient.getRequest('/auth/user', 'user', null)

		then:
		info.getResponseCode() == HTTP_OK
		def serverHeader = info.getHeaderField('Server')
		serverHeader != null
		serverHeader.startsWith('EntryStore/IntegrationTests')
	}

	def "GET /foo should include Server header even for 404 response"() {
		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/entry/randomEntryId/index', '')

		then:
		conn.getResponseCode() == HTTP_NOT_FOUND
		def serverHeader = conn.getHeaderField('Server')
		serverHeader != null
		serverHeader.startsWith('EntryStore/IntegrationTests')
	}
}
