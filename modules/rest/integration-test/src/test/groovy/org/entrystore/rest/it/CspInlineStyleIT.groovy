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
 * <p>Every HTML page EntryStore renders must style itself through a same-origin stylesheet rather
 * than a style="..." attribute: style attributes are governed by style-src-attr, which can only
 * ever be allowed by 'unsafe-inline'. Reintroducing one would render unstyled behind the hardened
 * CSP, which is invisible to every other test in this suite.
 *
 * <p>The stylesheet href is root-relative and prefixed with the path of
 * {@code entrystore.baseurl.folder}, because a reverse proxy may mount EntryStore under a prefix
 * and strip it before forwarding. This suite reproduces exactly that split: the base URL declares
 * {@code /store/} while the app under test is mounted at the root, so the expected href carries the
 * prefix and the stylesheet is fetched without it - the same translation the production rewrite
 * ({@code ^/store/(.+)$ -> backend/$1}) performs.
 *
 * <p>{@code confirm_form.html} renders at two different path depths and is covered by
 * {@code ZzzConfirmCredentialsIT}, which runs the non-legacy confirmation mode that reaches it.
 */
class CspInlineStyleIT extends BaseSpec {

	// Path prefix declared by entrystore.baseurl.folder in entrystore-it.properties.
	static final String BASE_PREFIX = '/store'
	static final String STYLESHEET_HREF = BASE_PREFIX + '/css/entrystore.css'

	static final List<String> STYLED_PAGES = ['/auth/signup', '/auth/pwreset', '/auth/signup?confirm=noSuchToken']

	@Unroll
	def 'GET #path must serve no inline style attribute'() {
		when:
		def connection = EntryStoreClient.getRequest(path, '', 'text/html')

		then:
		connection.getResponseCode() == expectedStatus
		connection.getContentType().contains('text/html')

		and: 'styling arrives via a stylesheet, never a style attribute'
		!htmlBody(connection).contains('style="')

		where:
		path                               | expectedStatus
		'/auth/login'                      | HTTP_OK
		'/auth/signup'                     | HTTP_OK
		'/auth/pwreset'                    | HTTP_OK
		'/auth/signup?confirm=noSuchToken' | HTTP_BAD_REQUEST
	}

	@Unroll
	def 'GET #path must link the stylesheet at the configured base path'() {
		when:
		def body = htmlBody(EntryStoreClient.getRequest(path, '', 'text/html'))

		then:
		body.contains('<link rel="stylesheet" href="' + STYLESHEET_HREF + '">')

		where:
		path << STYLED_PAGES
	}

	def 'the linked stylesheet must be served by EntryStore as CSS once the proxy prefix is stripped'() {
		when: 'the href is requested the way the proxy forwards it, with the base prefix removed'
		def connection = EntryStoreClient.getRequest(STYLESHEET_HREF.substring(BASE_PREFIX.length()), '', '')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/css')

		and: 'it carries the rules the pages depend on'
		def css = connection.inputStream.getText(StandardCharsets.UTF_8.name())
		css.contains('width: 500px')
		css.contains('.error')
	}

	private static String htmlBody(HttpURLConnection connection) {
		def stream = connection.getResponseCode() >= HTTP_BAD_REQUEST ? connection.errorStream : connection.inputStream
		return stream.getText(StandardCharsets.UTF_8.name())
	}
}
