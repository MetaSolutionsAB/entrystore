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

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import org.entrystore.rest.it.util.UserUtil

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

/**
 * An {@code es:externalID} triple that anyone with metadata write access can store in an entry's metadata,
 * naming another user's resource URI and an address of their own, must not make password reset or sign-up
 * resolve that user (ENTRYSTORE-1303).
 */
class PlantedExternalIdIT extends BaseSpec {

	static def contextId = 'planted-external-id-ctx'
	static def externalIdPredicate = NameSpaceConst.ES_TERMS + 'externalID'
	static def password = 'newPass12345'
	static def greenMail = new GreenMail(SMTP)

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
		greenMail.start()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		greenMail.stop()
	}

	def "POST /auth/pwreset should not mail a link for an address planted as another user's external ID"() {
		given:
		def victim = UserUtil.createUser('plantedextidvictim@test.com')
		def plantedAddress = 'plantedextidreset@example.org'
		def entryId = createEntry(contextId, [:])
		assert plantExternalId(entryId, victim['resourceUri'].toString(), plantedAddress, 'admin') == HTTP_NO_CONTENT

		when: 'a reset for the planted address, then one for the victim as a control that mail is delivered'
		def resetConn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody(plantedAddress))
		def controlConn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody(victim['username'].toString()))

		then: 'only the control link is mailed'
		resetConn.getResponseCode() == HTTP_OK
		controlConn.getResponseCode() == HTTP_OK
		onlyMailWentTo(victim['username'].toString())
	}

	def "POST /auth/pwreset should not mail a link for the admin account when a non-admin planted its external ID"() {
		given:
		def adminUri = EntryStoreClient.baseUrl + '/_principals/resource/_admin'
		def plantedAddress = 'plantedextidadmin@example.org'
		def entryId = createEntry(contextId, [:])
		grantMetadataWrite(entryId, 'user')
		assert plantExternalId(entryId, adminUri, plantedAddress, 'user') == HTTP_NO_CONTENT
		def control = UserUtil.createUser('plantedextidadmincontrol@test.com')

		when: 'a reset for the planted address, then one for another user as a control that mail is delivered'
		def resetConn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody(plantedAddress))
		def controlConn = EntryStoreClient.postRequest('/auth/pwreset', pwResetBody(control['username'].toString()))

		then: 'only the control link is mailed'
		resetConn.getResponseCode() == HTTP_OK
		controlConn.getResponseCode() == HTTP_OK
		onlyMailWentTo(control['username'].toString())
	}

	def "GET /auth/pwreset should not reset another user's password through an address planted after the link was mailed"() {
		given: 'a reset link for an account whose name is the address, which is then deleted'
		def plantedAddress = 'plantedextidconfirm@test.com'
		def account = UserUtil.createUser(plantedAddress)
		assert EntryStoreClient.postRequest('/auth/pwreset', pwResetBody(plantedAddress)).getResponseCode() == HTTP_OK
		assert greenMail.waitForIncomingEmail(5000, 1)
		def token = extractConfirmationToken(greenMail)
		assert EntryStoreClient.deleteRequest('/_principals/entry/' + account['entryId']).getResponseCode() == HTTP_NO_CONTENT

		and: 'the address planted as the external ID of another user'
		def victim = UserUtil.createUser('plantedextidconfirmvictim@test.com')
		def entryId = createEntry(contextId, [:])
		assert plantExternalId(entryId, victim['resourceUri'].toString(), plantedAddress, 'admin') == HTTP_NO_CONTENT

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

		then: 'no account is found, so no password is set and no password-change mail goes out'
		confirmConn.getResponseCode() == HTTP_NOT_FOUND
		confirmConn.errorStream.text.contains('User with provided email address does not exist.')
		!greenMail.waitForIncomingEmail(1000, 2)
	}

	def "GET /auth/signup?confirm= should create the account for an address planted as another user's external ID"() {
		given:
		def victim = UserUtil.createUser('plantedextidsignupvictim@test.com')
		def plantedAddress = 'plantedextidsignup@test.com'
		def entryId = createEntry(contextId, [:])
		assert plantExternalId(entryId, victim['resourceUri'].toString(), plantedAddress, 'admin') == HTTP_NO_CONTENT
		assert EntryStoreClient.postRequest('/auth/signup', signupBody(plantedAddress)).getResponseCode() == HTTP_OK
		def token = extractConfirmationToken(greenMail)

		when:
		def confirmConn = EntryStoreClient.getRequest('/auth/signup?confirm=' + token)

		then:
		confirmConn.getResponseCode() == HTTP_CREATED
		confirmConn.inputStream.text.contains('Sign-up successful.')
	}

	/** Waits for the first mail, then checks that it is the only one and went to {@code address}. */
	private static boolean onlyMailWentTo(String address) {
		assert greenMail.waitForIncomingEmail(5000, 1)
		assert !greenMail.waitForIncomingEmail(2000, 2)
		def messages = greenMail.getReceivedMessages()
		assert messages.length == 1
		assert messages[0].getAllRecipients()*.toString() == [address]
		return true
	}

	/** Replaces the entry's metadata with {@code <userUri> es:externalID <mailto:address>}; returns the status. */
	private static int plantExternalId(String entryId, String userUri, String address, String asUser) {
		def metadata = [(userUri): [(externalIdPredicate): [[type: 'uri', value: 'mailto:' + address]]]]
		return EntryStoreClient.putRequest('/' + contextId + '/metadata/' + entryId, JsonOutput.toJson(metadata),
			asUser).getResponseCode()
	}

	private static void grantMetadataWrite(String entryId, String username) {
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
		def userUri = EntryStoreClient.createdEsUsers[username]['resourceUri']
		def acl = [(metadataUri): [(NameSpaceConst.TERM_WRITE): [[type: 'uri', value: userUri]]]]
		def conn = EntryStoreClient.putRequest('/' + contextId + '/entry/' + entryId, JsonOutput.toJson(acl))
		assert conn.getResponseCode() == HTTP_NO_CONTENT
	}

	private static String pwResetBody(String email) {
		JsonOutput.toJson([email: email, password: password, grecaptcharesponse: 'anything'])
	}

	private static String signupBody(String email) {
		JsonOutput.toJson([firstname: 'First', lastname: 'Last', email: email, password: password,
						   grecaptcharesponse: 'anything'])
	}
}
