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

class FaviconIT extends BaseSpec {

	def "GET /favicon.ico returns 200 with image/x-icon, non-empty body, and Cache-Control (user=#user)"() {
		when:
		def connection = EntryStoreClient.getRequest('/favicon.ico', user, 'image/x-icon')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('image/x-icon')
		connection.inputStream.bytes.length > 0
		def cacheControl = connection.getHeaderField('Cache-Control')
		cacheControl != null
		cacheControl.contains('max-age=604800')
		cacheControl.contains('public')

		where:
		user << ['admin', '']
	}
}
