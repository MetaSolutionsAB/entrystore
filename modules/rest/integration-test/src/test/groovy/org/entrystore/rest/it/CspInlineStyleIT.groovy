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
import spock.lang.Unroll

import java.nio.charset.StandardCharsets

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_OK

/**
 * Guards the CSP contract that lets the reverse proxy serve /store/ with a plain
 * {@code style-src 'self'} instead of {@code style-src-attr 'unsafe-inline'} (ENTRYSTORE-1100).
 *
 * <p>Every HTML page EntryStore renders must style itself through a same-origin stylesheet
 * rather than a style="..." attribute: style attributes are governed by style-src-attr, which
 * can only ever be allowed by 'unsafe-inline'. Re-introducing one would render unstyled behind
 * the hardened CSP, which is invisible to every other test in this suite.
 *
 * <p>The stylesheet is linked with a relative href because a reverse proxy may strip a path
 * prefix before forwarding. That only resolves while the pages stay one segment deep, so the
 * link is resolved against each page URL here and fetched, rather than merely asserted present.
 */
class CspInlineStyleIT extends BaseSpec {

	// The auth pages that carry styling; auth.html is reached through its error path.
	private static final List<String> STYLED_PAGES = ['/auth/signup', '/auth/pwreset', '/auth/signup?confirm=noSuchToken']

	@Unroll
	def 'GET #path must serve no inline style attribute'() {
		when:
		def connection = EntryStoreClient.getRequest(path, '', 'text/html')

		then:
		connection.getResponseCode() == expectedStatus
		connection.getContentType().contains('text/html')

		and: 'styling arrives via a style element, never a style attribute'
		!htmlBody(connection).contains('style="')

		where:
		path                               | expectedStatus
		'/auth/login'                      | HTTP_OK
		'/auth/signup'                     | HTTP_OK
		'/auth/pwreset'                    | HTTP_OK
		'/auth/signup?confirm=noSuchToken' | HTTP_BAD_REQUEST
	}

	def 'every styled auth page must link a stylesheet that resolves against its own URL and is served by EntryStore'() {
		expect:
		STYLED_PAGES.each { path ->
			def pageBody = htmlBody(EntryStoreClient.getRequest(path, '', 'text/html'))
			def link = pageBody =~ /<link rel="stylesheet" href="([^"]+)">/
			assert link.find(): "no stylesheet link served on ${path}"

			// Resolving against the page URL is the point: it proves the relative href still
			// points at the stylesheet from the depth this page is actually served at.
			def stylesheetUri = URI.create(EntryStoreClient.origin + path).resolve(link.group(1))
			def cssConnection = (HttpURLConnection) stylesheetUri.toURL().openConnection()

			assert cssConnection.getResponseCode() == HTTP_OK: "stylesheet ${stylesheetUri} is not served (linked from ${path})"
			assert cssConnection.getContentType().contains('text/css')
			assert cssConnection.inputStream.getText(StandardCharsets.UTF_8.name()).contains('width: 500px')
		}
	}

	private static String htmlBody(HttpURLConnection connection) {
		def stream = connection.getResponseCode() >= HTTP_BAD_REQUEST ? connection.errorStream : connection.inputStream
		return stream.getText(StandardCharsets.UTF_8.name())
	}
}
