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

import com.github.benmanes.caffeine.cache.Cache
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil
import org.entrystore.rest.springboot.security.ESUserDetailsService

import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Pins the eviction guarantees of the UserDetails TTL cache in ESUserDetailsService
 * (ENTRYSTORE-1090 B4). The cache amortises per-request repository loads, but every
 * security-relevant user mutation must take effect immediately — eviction, not TTL expiry,
 * is the guarantee, so all staleness assertions here run well within the 5-second TTL.
 * Logout invalidation of the session cookie itself is covered by LogoutIT.
 */
class UserDetailsCacheIT extends BaseSpec {

	static def password = 'newPass12345'

	def "PUT disabled=true should reject the disabled user's very next request on an existing session"() {
		given: 'a logged-in user whose identity has just been cached by a successful request'
		def username = 'userDetailsCacheDisable@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def loginBody = 'auth_username=' + username + '&auth_password=' + password
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', 'application/x-www-form-urlencoded')
		assert loginConn.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConn, 'auth_token')
		assert cookie != null
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK

		when: 'an admin disables the user'
		// setDisabled does not expire sessions — only the per-request reload of user details
		// protects the still-open session, so this test fails if the eviction is broken.
		assert EntryStoreClient.putRequest(resourceUri, JsonOutput.toJson([disabled: true]))
				.getResponseCode() == HTTP_NO_CONTENT

		and: 'the user immediately reuses the existing session, well within the cache TTL'
		def conn = EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie])

		then: 'the request is rejected — the disable evicted the cached identity, TTL played no part'
		conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /auth/cookie with the old password immediately after an admin password change should fail"() {
		given: 'a user whose identity (incl. password hash) has just been cached by a login'
		def username = 'userDetailsCachePwChange@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def oldPasswordLoginBody = 'auth_username=' + username + '&auth_password=' + password
		assert EntryStoreClient.postRequest('/auth/cookie', oldPasswordLoginBody, '', 'application/x-www-form-urlencoded')
				.getResponseCode() == HTTP_OK

		when: 'an admin changes the password'
		def newPassword = 'someNewPassword123'
		UserUtil.setUserPassword(resourceUri, newPassword)

		and: 'logins are attempted immediately with the old and the new password, well within the cache TTL'
		def oldPasswordLogin = EntryStoreClient.postRequest('/auth/cookie', oldPasswordLoginBody, '',
				'application/x-www-form-urlencoded')
		def newPasswordLogin = EntryStoreClient.postRequest('/auth/cookie',
				'auth_username=' + username + '&auth_password=' + newPassword, '', 'application/x-www-form-urlencoded')

		then: 'the old password is rejected — the change evicted the cached hash — and the new one accepted'
		oldPasswordLogin.getResponseCode() == HTTP_UNAUTHORIZED
		newPasswordLogin.getResponseCode() == HTTP_OK
	}

	def "repeated requests on one session within the TTL should be served from the cache"() {
		given: 'a logged-in user with a session cookie'
		def username = 'userDetailsCacheTtl@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def loginBody = 'auth_username=' + username + '&auth_password=' + password
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', 'application/x-www-form-urlencoded')
		assert loginConn.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConn, 'auth_token')
		assert cookie != null

		and: 'the first request on the session populates the cache under the resource-URI key'
		// The app under test runs in this JVM (started by BaseSpec), so the cache map can be
		// inspected directly — a timing-insensitive alternative to asserting on debug logs.
		assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		def cache = appInstance.getBean(ESUserDetailsService).@userDetailsCache as Cache
		def cachedAfterFirstRequest = cache.getIfPresent(resourceUri.toLowerCase())
		assert cachedAfterFirstRequest != null

		when: 'four more requests are made in quick succession on the same session'
		4.times {
			assert EntryStoreClient.getRequest('/auth/user', null, null, [Cookie: cookie]).getResponseCode() == HTTP_OK
		}

		then: 'the cached identity is still the same instance — no request reloaded it from the repository'
		cache.getIfPresent(resourceUri.toLowerCase()).is(cachedAfterFirstRequest)
	}

	def "removing a user from the admins group should drop ROLE_ADMIN on the very next request"() {
		given: 'a logged-in admins-group member whose ROLE_ADMIN identity has just been cached'
		def username = 'userDetailsCacheDeElevate@test.com'
		def user = UserUtil.createUser(username, null, true)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def loginBody = 'auth_username=' + username + '&auth_password=' + password
		def loginConn = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', 'application/x-www-form-urlencoded')
		assert loginConn.getResponseCode() == HTTP_OK
		def cookie = EntryStoreClient.findSetCookie(loginConn, 'auth_token')
		assert cookie != null
		assert EntryStoreClient.getRequest('/management/status/extended', null, null, [Cookie: cookie])
				.getResponseCode() == HTTP_OK

		when: 'an admin replaces the admins-group membership without the user'
		// The bulk-membership PUT goes through ListImpl.setChildren, which fires only
		// RelationsUpdated (User source) and ResourceUpdated (Group source) — this pins that
		// group-sourced events evict the cached ROLE_ADMIN, not just direct User events.
		def membersConn = EntryStoreClient.getRequest('/_principals/resource/_admins')
		assert membersConn.getResponseCode() == HTTP_OK
		def members = JSON_PARSER.parseText(membersConn.inputStream.text)
		def remaining = (members['children'] as List)
				.findAll { it['entryId'] != user['entryId'] }
				.collect { it['entryId'] }
		assert EntryStoreClient.putRequest('/_principals/resource/_admins', JsonOutput.toJson(remaining))
				.getResponseCode() == HTTP_NO_CONTENT

		and: 'the de-elevated user immediately reuses the existing session, well within the cache TTL'
		def conn = EntryStoreClient.getRequest('/management/status/extended', null, null, [Cookie: cookie])

		then: 'ROLE_ADMIN is gone — the membership change evicted the cached identity, TTL played no part'
		conn.getResponseCode() == HTTP_FORBIDDEN
	}
}
