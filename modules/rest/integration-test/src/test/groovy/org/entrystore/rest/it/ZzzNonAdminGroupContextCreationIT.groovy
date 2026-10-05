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

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * Covers EntryScape's context creation flow for a non-admin with {@code entrystore.nonadmin.group-context-creation}
 * enabled: probe the name with HEAD (only 404 means free), then create the group and context.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzNonAdminGroupContextCreationIT extends BaseSpec {

	def setupSpec() {
		stopPreexistingAppIfRunning()
		// the value documented in the KB, rather than 'true'
		startOwnedApp(['--entrystore.nonadmin.group-context-creation=on'])
		createCommonUserAccounts()
	}

	def "non-admin user should find a free context name and ID, create the group and context, and then find both taken"() {
		given:
		def name = 'nonAdminProject'
		def contextId = 'non-admin-project'

		when: 'the name and ID are probed before creation'
		def nameProbe = EntryStoreClient.headRequest('/' + name, 'user')
		def idProbe = EntryStoreClient.headRequest('/' + contextId, 'user')

		then:
		nameProbe.getResponseCode() == HTTP_NOT_FOUND
		idProbe.getResponseCode() == HTTP_NOT_FOUND

		when:
		def created = EntryStoreClient.postRequest('/_principals/groups?name=' + name + '&contextId=' + contextId,
			null, 'user')

		then:
		created.getResponseCode() == HTTP_CREATED

		when: 'the name and ID are probed after creation'
		def nameProbeAfter = EntryStoreClient.headRequest('/' + name, 'user')
		def idProbeAfter = EntryStoreClient.headRequest('/' + contextId, 'user')

		then:
		nameProbeAfter.getResponseCode() == HTTP_FORBIDDEN
		idProbeAfter.getResponseCode() == HTTP_FORBIDDEN
	}
}
