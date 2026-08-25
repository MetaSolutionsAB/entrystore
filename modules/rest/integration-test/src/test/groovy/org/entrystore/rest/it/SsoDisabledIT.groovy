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

/**
 * The shared-app environment runs with all SSO mechanisms disabled — SAML and OIDC via explicit
 * {@code enabled=false} keys in entrystore-it.properties, CAS by its {@code @DefaultValue("false")}
 * in {@code CasCustomConfiguration} (no CAS key exists in the properties file) — so the initiation
 * endpoints must answer 404, guarding the enabled-property binding against regressions (e.g. a
 * renamed key silently defaulting to enabled, or a controller losing its enabled-guard).
 */
class SsoDisabledIT extends BaseSpec {

	def "GET #path should return 404 when the corresponding SSO mechanism is disabled"() {
		when:
		def connection = EntryStoreClient.getRequest(path, '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND

		where:
		path << ['/auth/oidc', '/auth/saml', '/auth/cas']
	}
}
