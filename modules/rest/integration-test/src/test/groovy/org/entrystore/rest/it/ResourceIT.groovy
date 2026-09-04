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

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import org.entrystore.rest.it.util.UserUtil
import org.springframework.http.HttpMethod

import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_ACCEPTABLE
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class ResourceIT extends BaseSpec {

	def static contextId = '80'
	static def password = 'newPass1234'


	def setupSpec() {
		getOrCreateContext([contextId: contextId])
		EntryStoreClient.snapshotCreds()
		EntryStoreClient.creds.put('userChangePassword@test.com', password)
		EntryStoreClient.creds.put('userChangePasswordBadCurrentPassword@test.com', password)
		EntryStoreClient.creds.put('userChangePasswordNoCurrentPassword@test.com', password)
		EntryStoreClient.creds.put('userChangePasswordBadNewPassword@test.com', password)
		EntryStoreClient.creds.put('userAdminChangePassword@test.com', password)
		EntryStoreClient.creds.put('resourceTestUserName@test.com', password)
		EntryStoreClient.creds.put('resDelOther@test.com', password)
		EntryStoreClient.creds.put('resDelProxyOther@test.com', password)
	}

	def cleanupSpec() {
		EntryStoreClient.restoreCreds()
	}

	def "GET /{context-id}/resource/{entry-id} as guest on String graph should respond with Not Found 404 to avoid entry-existence disclosure"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0
		// fetch URI of created resource for the local entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		assert entryRespJson['info'][entryUri] != null
		assert entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		assert entryResources.size() == 1
		assert entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		assert createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		when:
		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri, '')

		then:
		// Guests get 404 (not 401) so they cannot distinguish "entry exists but is private" from "entry does not exist"
		resourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/resource/{entry-id} as admin on String graph should return the text data"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0
		// fetch URI of created resource for the local entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		assert entryRespJson['info'][entryUri] != null
		assert entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		assert entryResources.size() == 1
		assert entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		assert createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		when:
		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('text/plain')
		// The body is the raw string as submitted, not JSON-quoted/escaped — matches legacy Restlet behavior.
		resourceConn.inputStream.text == someText
	}

	def "GET /{context-id}/resource/{entry-id} as guest on List graph should respond with Not Found 404"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		// create list entry
		def resourceVal = [givenEntryId, 'non-existing-id']
		def params = [graphtype: 'list']
		def body = [resource: resourceVal]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0
		// fetch URI of created resource for the local entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		assert entryRespJson['info'][entryUri] != null
		assert entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		assert entryResources.size() == 1
		assert entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		assert createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		when: "we fetch created resource"
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri, '')

		then:
		resourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/resource/{entry-id} as admin on List graph should return the entries list"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		// create list entry
		def resourceVal = [givenEntryId, 'non-existing-id']
		def params = [graphtype: 'list']
		def body = [resource: resourceVal]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0
		// fetch URI of created resource for the local entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		assert entryRespJson['info'][entryUri] != null
		assert entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		assert entryResources.size() == 1
		assert entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		assert createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		when: "we fetch created resource"
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		// entries list should not contain the 'non-existing-id'
		resourceResp == [givenEntryId]
	}

	// TODO: Verify behaviour - Guest can access entries list for any context
	def "GET /_contexts/resource/{entry-id} as guest on Context graph should return the entries list that are in the context"() {
		given:
		// create minimal entry in the context
		def givenEntryId = createEntry(contextId, [:])

		when:
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId, '')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp.collect().contains(givenEntryId)
	}

	def "GET /_contexts/resource/{entry-id} as admin on Context graph should return the entries list that are in the context"() {
		given:
		// create minimal entry in the context
		def givenEntryId = createEntry(contextId, [:])

		when:
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp.collect().contains(givenEntryId)
	}

	def "GET /{context-id}/resource/{entry-id} as guest on a User graph should return empty response"() {
		given:
		def user = EntryStoreClient.createdEsUsers['user']
		def entryId = user['entryId'].toString()

		when:
		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId, '')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp['name'] == null
		resourceResp['language'] == null
		resourceResp['customProperties'] == null
		(resourceResp as Map).keySet().size() == 0
	}

	def "GET /{context-id}/resource/{entry-id} as admin on a User graph should return data about the user"() {
		given:
		def user = EntryStoreClient.createdEsUsers['user']
		def entryId = user['entryId'].toString()

		when:
		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp['name'] == user['username'].toString().toLowerCase()
		resourceResp['language'] == null
		resourceResp['customProperties'] == [:]
	}

	def "GET /{context-id}/resource/{entry-id} as guest on Group graph should return data about the group"() {
		given:
		def requestResourceName = [name: 'Test Grouppppen']
		def params = [graphtype: 'group']
		def body = JsonOutput.toJson([resource: requestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)
		assert connection.getResponseCode() == HTTP_CREATED
		assert connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		assert responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId, '')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp['name'] == null
		resourceResp['children'] == null
		(resourceResp as Map).keySet().size() == 0
	}

	def "GET /{context-id}/resource/{entry-id} as admin on Group graph should return data about the group"() {
		given:
		def requestResourceName = [name: 'Test Grouppppen2']
		def params = [graphtype: 'group']
		def body = JsonOutput.toJson([resource: requestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)
		assert connection.getResponseCode() == HTTP_CREATED
		assert connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		assert responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/_principals/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceResp = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceResp['name'] == requestResourceName['name'].toLowerCase()
		resourceResp['children'] == []
	}

	def "GET /{context-id}/resource/{entry-id} as guest on None graph should respond with Not Found 404"() {
		given:
		def requestResourceName = [name: 'None graph entryyyy']
		def body = [resource: requestResourceName]
		def entryId = createEntry(contextId, [id: 'None-graph'], body)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId, '')

		then:
		resourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/resource/{entry-id} as admin on None graph should return 204 (No Content) when file was not sent with the entry"() {
		given:
		def requestResourceName = [name: 'None graph entryyyy2']
		def body = [resource: requestResourceName]
		def entryId = createEntry(contextId, [id: 'None-graph2'], body)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_NO_CONTENT
		resourceConn.inputStream.text == ''
	}

	def "GET /{context-id}/resource/{entry-id} as guest on None graph entry with octet-stream file should respond with Not Found 404"() {
		given:
		def requestResourceName = [name: 'None graph entry']
		def body = [resource: requestResourceName]
		def entryId = getOrCreateEntry(contextId, [id: 'noneId'], body)
		assert entryId.length() > 0

		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('test', '.bin', ([0xDE, 0xAD, 0xBE, 0xEF] + "Hello".bytes.toList()) as byte[])
		def sendFileConn = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId, '')

		then:
		resourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/resource/{entry-id} as admin on None graph entry with octet-stream file should return the entry's file"() {
		given:
		def requestResourceName = [name: 'None graph entry']
		def body = [resource: requestResourceName]
		def entryId = getOrCreateEntry(contextId, [id: 'noneId'], body)
		assert entryId.length() > 0

		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('test', '.bin', ([0xDE, 0xAD, 0xBE, 0xEF] + "Hello".bytes.toList()) as byte[])
		def sendFileConn = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType() == 'application/octet-stream'
		resourceConn.getInputStream().readAllBytes() == testBinFile.getBytes()
	}

	def "GET /{context-id}/resource/{entry-id} on None graph entry with multi-part file should return the entry's file"() {
		given:
		def requestResourceName = [name: 'None graph entry']
		def body = [resource: requestResourceName]
		def entryId = getOrCreateEntry(contextId, [id: 'noneId'], body)
		assert entryId.length() > 0

		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('test', '.bin', ([0xDE, 0xAD, 0xBE, 0xEF] + "Hello-again".bytes.toList()) as byte[])
		def sendFileConn = EntryStoreClient.putRequestMultiPart('/' + contextId + '/resource/' + entryId, testBinFile)
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType() == 'application/octet-stream'
		resourceConn.getInputStream().readAllBytes() == testBinFile.getBytes()
	}

	def "GET /{context-id}/resource/{entry-id} on None graph entry with file should answer inline Content-Disposition and a sha-256 Digest header"() {
		given:
		def body = [resource: [name: 'Digest header entry']]
		def entryId = getOrCreateEntry(contextId, [id: 'digestNoneId'], body)
		assert entryId.length() > 0
		def payload = ([0xCA, 0xFE] + "Digest me".bytes.toList()) as byte[]
		// .txt is not on FileUtil's dangerous-extension list, so the stored filename stays unchanged
		def testBinFile = createTempBinaryFile('digest', '.txt', payload)
		def sendFileConn = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED
		def expectedDigest = MessageDigest.getInstance('SHA-256').digest(payload).encodeHex().toString()

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getHeaderField('Content-Disposition') == 'inline; filename="' + testBinFile.getName() + '"'
		resourceConn.getHeaderField('Digest') == 'sha-256=' + expectedDigest
	}

	def "GET /{context-id}/resource/{entry-id}?download on None graph entry with file should answer attachment Content-Disposition"() {
		given:
		def body = [resource: [name: 'Download entry']]
		def entryId = getOrCreateEntry(contextId, [id: 'downloadNoneId'], body)
		assert entryId.length() > 0
		def testBinFile = createTempBinaryFile('download', '.txt', "Save me".bytes)
		def sendFileConn = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?download')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getHeaderField('Content-Disposition') == 'attachment; filename="' + testBinFile.getName() + '"'
		resourceConn.getInputStream().readAllBytes() == "Save me".bytes
	}

	def "PUT /{context-id}/resource/{entry-id} multipart upload under the configured cap should succeed"() {
		given:
		def requestResourceName = [name: 'Large file entry']
		def body = [resource: requestResourceName]
		def entryId = getOrCreateEntry(contextId, [id: 'largeFileId'], body)
		assert entryId.length() > 0

		def payload = new byte[13 * 1024 * 1024]
		new Random(42).nextBytes(payload)
		def largeBinFile = createTempBinaryFile('large', '.bin', payload)

		when:
		def sendFileConn = EntryStoreClient.putRequestMultiPart('/' + contextId + '/resource/' + entryId, largeBinFile)

		then:
		sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getInputStream().readAllBytes() == payload
	}

	def "PUT /{context-id}/resource/{entry-id} as guest should respond with Not Found 404"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [id: 'editResourceEntryId', graphtype: 'string']
		def body = [resource: someText]
		def entryId = getOrCreateEntry(contextId, params, body)
		assert entryId.length() > 0

		// fetch URI of created resource for the String entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('text/plain')
		assert resourceConn.inputStream.text == someText

		def newBody = 'new String set'

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, newBody, '')

		then:
		editResourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "PUT /{context-id}/resource/{entry-id} as admin should edit String-resource"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [id: 'editResourceEntryId', graphtype: 'string']
		def body = [resource: someText]
		def entryId = getOrCreateEntry(contextId, params, body)
		assert entryId.length() > 0

		// fetch URI of created resource for the String entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('text/plain')
		assert resourceConn.inputStream.text == someText

		def newBody = 'new String set'

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, newBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('text/plain')
		resourceConn2.inputStream.text == newBody
	}

	def "PUT /{context-id}/resource/{entry-id} should edit List-resource"() {
		given:
		// create minimal entry to be added to the list in the "when" section
		def minimalEntryId = createEntry(contextId, [:])

		// create an empty list
		def params = [graphtype: 'list']
		def entryId = createEntry(contextId, params, [resource: []])
		assert entryId.length() > 0

		// fetch URI of created resource for the list
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(resourceConn.inputStream.text) == []

		when:
		// add minimal entry to the list
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, JsonOutput.toJson([minimalEntryId]))

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(resourceConn2.inputStream.text) == [minimalEntryId]
	}

	def "PUT /{context-id}/resource/{entry-id} should not edit List-resource if requested entry does not exist"() {
		given:
		// create an empty list
		def params = [graphtype: 'list']
		def entryId = createEntry(contextId, params, [resource: []])
		assert entryId.length() > 0

		// fetch URI of created resource for the list
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(resourceConn.inputStream.text) == []

		when:
		// add non-existing entry to the list
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, JsonOutput.toJson(['some-random-entry-id']))

		then:
		editResourceConn.getResponseCode() == HTTP_BAD_REQUEST
		// fetch resource details again, should still be an empty list
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(resourceConn2.inputStream.text) == []
	}

	def "PUT /{context-id}/resource/{entry-id} should edit name and password of User-resource"() {
		given:
		def username = 'Resource Test User name 2'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		def entryId = user['entryId'].toString()

		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceJson['name'] == username.toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['customProperties'] == [:]

		def newUsername = 'resourceTestUserName@test.com'
		def requestBody = JsonOutput.toJson([
			name    : newUsername,
			language: 'PL',
			password: password
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.inputStream.text)
		resourceJson2['name'] == newUsername.toLowerCase()
		resourceJson2['language'] == 'PL'
		resourceJson2['customProperties'] == [:]
		def info = EntryStoreClient.getRequest('/auth/user', newUsername)
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		entryId == infoRespJson['id']
	}

	def "PUT /{context-id}/resource/{entry-id} should not edit name if name is already in use"() {
		given:
		def existingUsername = 'existingUser@test.com'
		UserUtil.createUser(existingUsername)
		def username = 'Resource Test User name 20'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceJson['name'] == username.toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['customProperties'] == [:]

		def requestBody = JsonOutput.toJson([
			name: existingUsername
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_BAD_REQUEST

		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.inputStream.text)
		resourceJson2['name'] == username.toLowerCase()
		resourceJson2['language'] == null
		resourceJson2['customProperties'] == [:]
	}

	def "PUT /_principals/{entry-id} should add user to a group and the user should have the information in relations object"() {
		given:
		def username = 'UserPUT'
		def user = UserUtil.createUser(username)
		def userEntryId = user['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUT']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()
		// fetch URI of created Group
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.inputStream.text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([userEntryId])

		when:
		// add user to group
		def addUserToGroupConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, requestBody)

		then:
		addUserToGroupConn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceRespText = addUserToGroupConn.inputStream.text
		addResourceRespText == ''
		// fetch Group details
		def groupResourceConn = EntryStoreClient.getRequest(groupResourceUri)
		assert groupResourceConn.getResponseCode() == HTTP_OK
		assert groupResourceConn.getContentType().contains('application/json')
		def groupResourceJson = JSON_PARSER.parseText(groupResourceConn.inputStream.text)
		assert groupResourceJson['children'] instanceof List
		def groupMembers = groupResourceJson['children'].collect()
		groupMembers.size() == 1
		groupMembers[0]['name'] == username.toLowerCase()
		// fetch User details
		def userResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + "?includeAll")
		assert userResourceConn.getResponseCode() == HTTP_OK
		assert userResourceConn.getContentType().contains('application/json')
		def userResourceJson = JSON_PARSER.parseText(userResourceConn.inputStream.text)
		assert userResourceJson['relations'] instanceof Map
		def relations = userResourceJson['relations']
		def userGroupRelation = relations[groupResourceUri]
		assert userGroupRelation != null
	}

	def "PUT /_principals/{entry-id} should add user to 2 groups and the user should have the information in relations object"() {
		given:
		def username = 'UserPUTInto2groups'
		def user = UserUtil.createUser(username)
		def userEntryId = user['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def group1RequestResourceName = [name: 'GroupPUT1']
		def group1Body = JsonOutput.toJson([resource: group1RequestResourceName])
		def group1Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), group1Body)
		def group1EntryId = JSON_PARSER.parseText(group1Connection.inputStream.text)['entryId'].toString()
		// fetch URI of created Group
		def group1EntryConn = EntryStoreClient.getRequest('/_principals/entry/' + group1EntryId)
		def group1EntryRespJson = JSON_PARSER.parseText(group1EntryConn.inputStream.text)
		def group1EntryRespJsonKeys = (group1EntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def group1ResourceUri = group1EntryRespJsonKeys.find { it -> it.contains('resource') }

		// create a Group entry
		def group2RequestResourceName = [name: 'GroupPUT1']
		def group2Body = JsonOutput.toJson([resource: group2RequestResourceName])
		def group2Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), group2Body)
		def group2EntryId = JSON_PARSER.parseText(group2Connection.inputStream.text)['entryId'].toString()
		// fetch URI of created Group
		def group2EntryConn = EntryStoreClient.getRequest('/_principals/entry/' + group2EntryId)
		def group2EntryRespJson = JSON_PARSER.parseText(group2EntryConn.inputStream.text)
		def group2EntryRespJsonKeys = (group2EntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def group2ResourceUri = group2EntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([userEntryId])

		when:
		// add user to group
		def addUserToGroup1Conn = EntryStoreClient.putRequest('/_principals/resource/' + group1EntryId, requestBody)
		def addUserToGroup2Conn = EntryStoreClient.putRequest('/_principals/resource/' + group2EntryId, requestBody)

		then:
		addUserToGroup1Conn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceResp1Text = addUserToGroup1Conn.inputStream.text
		addResourceResp1Text == ''
		addUserToGroup2Conn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceResp2Text = addUserToGroup2Conn.inputStream.text
		addResourceResp2Text == ''
		// fetch Group details
		def group1ResourceConn = EntryStoreClient.getRequest(group1ResourceUri)
		assert group1ResourceConn.getResponseCode() == HTTP_OK
		assert group1ResourceConn.getContentType().contains('application/json')
		def group1ResourceJson = JSON_PARSER.parseText(group1ResourceConn.inputStream.text)
		assert group1ResourceJson['children'] instanceof List
		def group1Members = group1ResourceJson['children'].collect()
		group1Members.size() == 1
		group1Members[0]['name'] == username.toLowerCase()
		def group2ResourceConn = EntryStoreClient.getRequest(group2ResourceUri)
		assert group2ResourceConn.getResponseCode() == HTTP_OK
		assert group2ResourceConn.getContentType().contains('application/json')
		def group2ResourceJson = JSON_PARSER.parseText(group2ResourceConn.inputStream.text)
		assert group2ResourceJson['children'] instanceof List
		def group2Members = group2ResourceJson['children'].collect()
		group2Members.size() == 1
		group2Members[0]['name'] == username.toLowerCase()

		// fetch User details
		def userResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + "?includeAll")
		assert userResourceConn.getResponseCode() == HTTP_OK
		assert userResourceConn.getContentType().contains('application/json')
		def userResourceJson = JSON_PARSER.parseText(userResourceConn.inputStream.text)
		assert userResourceJson['relations'] instanceof Map
		def relations = userResourceJson['relations']
		def userGroup1Relation = relations[group1ResourceUri]
		assert userGroup1Relation != null
		def userGroup2Relation = relations[group2ResourceUri]
		assert userGroup2Relation != null
	}

	def "PUT /_principals/{entry-id} should add 2 users to a group and users should have the information in relations object"() {
		given:
		def username1 = 'UserPUT1'
		def user1 = UserUtil.createUser(username1)
		def user1EntryId = user1['entryId'].toString()
		def username2 = 'UserPUT2'
		def user2 = UserUtil.createUser('UserPUT2')
		def user2EntryId = user2['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def groupRequestResourceName = [name: 'GroupPUTboth']
		def groupBody = JsonOutput.toJson([resource: groupRequestResourceName])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()
		// fetch URI of created Group
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.inputStream.text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([user1EntryId, user2EntryId])

		when:
		// add user to group
		def addUsersToGroupConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, requestBody)

		then:
		addUsersToGroupConn.getResponseCode() == HTTP_NO_CONTENT
		addUsersToGroupConn.inputStream.text == ''

		// fetch Group details
		def groupResourceConn = EntryStoreClient.getRequest(groupResourceUri)
		groupResourceConn.getResponseCode() == HTTP_OK
		groupResourceConn.getContentType().contains('application/json')
		def groupResourceJson = JSON_PARSER.parseText(groupResourceConn.inputStream.text)
		groupResourceJson['children'] instanceof List
		def groupMembers = groupResourceJson['children'].collect()
		groupMembers.size() == 2
		groupMembers[0]['name'] == username1.toLowerCase()
		groupMembers[1]['name'] == username2.toLowerCase()

		// fetch User1 details
		def user1ResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + user1EntryId + "?includeAll")
		user1ResourceConn.getResponseCode() == HTTP_OK
		user1ResourceConn.getContentType().contains('application/json')
		def user1ResourceJson = JSON_PARSER.parseText(user1ResourceConn.inputStream.text)
		user1ResourceJson['relations'] instanceof Map
		user1ResourceJson['relations'][groupResourceUri] != null

		// fetch User2 details
		def user2ResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + user2EntryId + "?includeAll")
		user2ResourceConn.getResponseCode() == HTTP_OK
		user2ResourceConn.getContentType().contains('application/json')
		def user2ResourceJson = JSON_PARSER.parseText(user2ResourceConn.inputStream.text)
		user2ResourceJson['relations'] instanceof Map
		user2ResourceJson['relations'][groupResourceUri] != null
	}

	def "PUT /_principals/resource/{entry-id} should return 400 for malformed JSON on Group resource"() {
		given:
		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTMalformed']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()

		def malformedBody = '{ this is not a valid json, mate }'

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, malformedBody)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /_principals/resource/{entry-id} should remove members from a group when given a subset"() {
		given:
		def user1 = UserUtil.createUser('UserPUTRemove1')
		def user1EntryId = user1['entryId'].toString()
		def user2 = UserUtil.createUser('UserPUTRemove2')
		def user2EntryId = user2['entryId'].toString()

		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTRemove']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.inputStream.text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		// Add both users
		def addBothBody = JsonOutput.toJson([user1EntryId, user2EntryId])
		def addBothConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, addBothBody)
		assert addBothConn.getResponseCode() == HTTP_NO_CONTENT

		// Verify both are members
		def groupResourceConn1 = EntryStoreClient.getRequest(groupResourceUri)
		def groupResourceJson1 = JSON_PARSER.parseText(groupResourceConn1.inputStream.text)
		assert groupResourceJson1['children'].size() == 2

		when:
		// Set only user1 as member (removes user2)
		def removeBody = JsonOutput.toJson([user1EntryId])
		def removeConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, removeBody)

		then:
		removeConn.getResponseCode() == HTTP_NO_CONTENT
		def groupResourceConn2 = EntryStoreClient.getRequest(groupResourceUri)
		def groupResourceJson2 = JSON_PARSER.parseText(groupResourceConn2.inputStream.text)
		groupResourceJson2['children'] instanceof List
		groupResourceJson2['children'].size() == 1
		groupResourceJson2['children'][0]['name'] == 'userputremove1'
	}

	def "PUT /_principals/resource/{entry-id} should clear all members from a group when given empty array"() {
		given:
		def user1 = UserUtil.createUser('UserPUTClear1')
		def user1EntryId = user1['entryId'].toString()

		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTClear']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.inputStream.text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		// Add user
		def addBody = JsonOutput.toJson([user1EntryId])
		def addConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, addBody)
		assert addConn.getResponseCode() == HTTP_NO_CONTENT

		// Verify member was added
		def groupResourceConn1 = EntryStoreClient.getRequest(groupResourceUri)
		def groupResourceJson1 = JSON_PARSER.parseText(groupResourceConn1.inputStream.text)
		assert groupResourceJson1['children'].size() == 1

		when:
		// Set empty array (removes all members)
		def clearConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, '[]')

		then:
		clearConn.getResponseCode() == HTTP_NO_CONTENT
		def groupResourceConn2 = EntryStoreClient.getRequest(groupResourceUri)
		def groupResourceJson2 = JSON_PARSER.parseText(groupResourceConn2.inputStream.text)
		groupResourceJson2['children'] instanceof List
		groupResourceJson2['children'].size() == 0
	}

	def "PUT /_principals/resource/{entry-id} should return 400 for non-existent child entry on Group resource"() {
		given:
		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTBadChild']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, JsonOutput.toJson(['non-existent-entry-id']))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /_principals/resource/{entry-id} should return 400 for non-User entry added to Group"() {
		given:
		def group1Params = [graphtype: 'group']
		def group1Body = JsonOutput.toJson([resource: [name: 'GroupPUTTarget']])
		def group1Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(group1Params), group1Body)
		def group1EntryId = JSON_PARSER.parseText(group1Connection.inputStream.text)['entryId'].toString()

		// Create another group (non-User entry) to try adding as member
		def group2Params = [graphtype: 'group']
		def group2Body = JsonOutput.toJson([resource: [name: 'GroupPUTNonUser']])
		def group2Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(group2Params), group2Body)
		def group2EntryId = JSON_PARSER.parseText(group2Connection.inputStream.text)['entryId'].toString()

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + group1EntryId, JsonOutput.toJson([group2EntryId]))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /_principals/resource/{entry-id} should update Group members via Turtle RDF"() {
		given:
		def user1 = UserUtil.createUser('UserPUTTurtle1')
		def user1EntryId = user1['entryId'].toString()
		def user2 = UserUtil.createUser('UserPUTTurtle2')
		def user2EntryId = user2['entryId'].toString()

		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTTurtle']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.inputStream.text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		// Get user entry URIs (needed for the RDF triples)
		def user1EntryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + user1EntryId
		def user2EntryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + user2EntryId

		def turtleBody = """\
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
<${groupResourceUri}> rdf:type rdf:Seq ;
    rdf:_1 <${user1EntryUri}> ;
    rdf:_2 <${user2EntryUri}> .
"""

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, turtleBody, 'admin', 'text/turtle')

		then:
		conn.getResponseCode() == HTTP_NO_CONTENT
		def groupResourceConn = EntryStoreClient.getRequest(groupResourceUri)
		groupResourceConn.getResponseCode() == HTTP_OK
		def groupResourceJson = JSON_PARSER.parseText(groupResourceConn.inputStream.text)
		groupResourceJson['children'] instanceof List
		groupResourceJson['children'].size() == 2
		groupResourceJson['children'][0]['name'] == 'userputturtle1'
		groupResourceJson['children'][1]['name'] == 'userputturtle2'
	}

	def "PUT /_principals/resource/{entry-id} should return 409 for duplicate user entries in Group"() {
		given:
		def user1 = UserUtil.createUser('UserPUTDup1')
		def user1EntryId = user1['entryId'].toString()

		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTDuplicate']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, JsonOutput.toJson([user1EntryId, user1EntryId]))

		then:
		conn.getResponseCode() == HTTP_CONFLICT
	}

	def "PUT /_principals/resource/{entry-id} should return 400 for malformed Turtle RDF on Group resource"() {
		given:
		def groupParams = [graphtype: 'group']
		def groupBody = JsonOutput.toJson([resource: [name: 'GroupPUTMalformedTurtle']])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.inputStream.text)['entryId'].toString()

		def malformedTurtle = '@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n<urn:test> rdf:type rdf:Seq ;\n    rdf:_1 INVALID_URI_HERE'

		when:
		def conn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, malformedTurtle, 'admin', 'text/turtle')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /{context-id}/resource/{entry-id} on a User should answer 400 not 500 for #description"() {
		given: 'a user resource'
		def username = 'malformedBody' + description.hashCode().abs() + '@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		def resourceUri = (entryRespJson['info'] as Map).keySet()
			.collect(it -> it.toString()).find { it -> it.contains('resource') }

		when:
		def conn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then: 'a client error — these bodies are the caller\'s mistake, not a server fault'
		conn.getResponseCode() == HTTP_BAD_REQUEST

		where:
		description                                | requestBody
		'the JSON literal null'                    | 'null'
		'a number where a string belongs'          | '{"language":5}'
		'an explicit null where a string belongs'  | '{"language":null}'
		'a disabled that is not a boolean'         | '{"disabled":"yes"}'
		'an array where an object belongs'         | '{"customProperties":[]}'
	}

	def "PUT /{context-id}/resource/{entry-id} should edit other User-resource properties"() {
		given:
		def username = 'something@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceJson['name'] == username.toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['disabled'] == null
		assert resourceJson['customProperties'] == [:]

		def newUsername = 'Newer name'
		def requestBody = JsonOutput.toJson([
			name            : newUsername,
			language        : 'PL',
			disabled        : 'true',
			customProperties: [disablingReason: 'Untruthful']
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.inputStream.text)
		resourceJson2['name'] == newUsername.toLowerCase()
		resourceJson2['language'] == 'PL'
		resourceJson2['disabled'] == true
		resourceJson2['customProperties'] == [disablingreason: 'Untruthful']
	}

	def "PUT /{context-id}/resource/{entry-id} should change own password with providing current password"() {
		given:
		def username = 'userChangePassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		def entryId = user['entryId'].toString()
		UserUtil.setUserPassword(resourceUri, password)
		def newPassword = 'somePass1234'

		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : newPassword,
			currentPassword: password
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, username)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT

		def loginBody = createFormBody([auth_username: username, auth_password: newPassword])
		def loginConnection = EntryStoreClient.postRequest('/auth/cookie', loginBody, '', 'application/x-www-form-urlencoded')
		loginConnection.getResponseCode() == HTTP_OK
		def info = EntryStoreClient.getRequest('/auth/user', username)
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.inputStream.text)
		entryId == infoRespJson['id']
	}

	def "PUT /{context-id}/resource/{entry-id} should not change own admin-group user password without providing current password"() {
		given:
		def username = 'userAdminChangePassword@test.com'
		def user = UserUtil.createUser(username, null, true)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)

		def passwordChangeRequestBody = JsonOutput.toJson([
				password: 'somePass1234'
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, username)

		then:
		editResourceConn.getResponseCode() == HTTP_FORBIDDEN
		editResourceConn.getContentType().contains('application/json')
		def editRespJson = JSON_PARSER.parseText(editResourceConn.errorStream.text)
		editRespJson['error'] == 'Current password is required'
	}

	def "PUT /{context-id}/resource/{entry-id} should not change own password without providing current password"() {
		given:
		def username = 'userChangePasswordNoCurrentPassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)

		def passwordChangeRequestBody = JsonOutput.toJson([
			password: 'somePass1234'
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, username)

		then:
		editResourceConn.getResponseCode() == HTTP_FORBIDDEN
		editResourceConn.getContentType().contains('application/json')
		def editRespJson = JSON_PARSER.parseText(editResourceConn.errorStream.text)
		editRespJson['error'] == 'Current password is required'
	}

	def "PUT /{context-id}/resource/{entry-id} should not change own password with providing incorrect current password"() {
		given:
		def username = 'userChangePasswordBadCurrentPassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)

		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : 'somePass1234',
			currentPassword: 'badPassword1234'
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, username)

		then:
		editResourceConn.getResponseCode() == HTTP_FORBIDDEN
		editResourceConn.getContentType().contains('application/json')
		def editRespJson = JSON_PARSER.parseText(editResourceConn.errorStream.text)
		editRespJson['error'] == 'No password set or incorrect current password provided'
	}

	def "PUT /{context-id}/resource/{entry-id} should not change own password when new password does not meet requirements"() {
		given:
		def username = 'userChangePasswordBadNewPassword@test.com'
		def user = UserUtil.createUser(username)
		def resourceUri = user['resourceUri'].toString()
		UserUtil.setUserPassword(resourceUri, password)

		def passwordChangeRequestBody = JsonOutput.toJson([
			password       : 'abcd',
			currentPassword: password
		])

		when:
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, passwordChangeRequestBody, username)

		then:
		editResourceConn.getResponseCode() == HTTP_BAD_REQUEST
		editResourceConn.getContentType().contains('application/json')
		def editRespJson = JSON_PARSER.parseText(editResourceConn.errorStream.text)
		editRespJson['error'] == 'Password must conform to configured rules.'
	}

	def "DELETE /{context-id}/resource/{entry-id} should delete user"() {
		given:
		def username = 'userDelete@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()
		def resourceUri = user['resourceUri'].toString()
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceRespJson['name'] == username.toLowerCase()

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/' + entryId)

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = deleteResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_NOT_FOUND
	}

	def "DELETE /{context-id}/resource/{entry-id} as guest should respond with Not Found 404"() {
		given:
		// create minimal entry to be used in the list
		def minimalEntryId = createEntry(contextId, [:])
		// create a list with the minimal entry
		def params = [graphtype: 'list']
		def body = [resource: [minimalEntryId]]
		def entryId = createEntry(contextId, params, body)

		// fetch URI of the created resource
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect { it.toString() }
		def resourceUri = entryRespJsonKeys.find { it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceRespJson == [minimalEntryId]

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourceUri, '[]', '')

		then:
		// 404 (not 401) so guests cannot distinguish existing-private from missing
		deleteResourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "DELETE /{context-id}/resource/{entry-id} as admin should remove resource"() {
		given:
		// create minimal entry to be used in the list
		def minimalEntryId = createEntry(contextId, [:])
		// create a list with the minimal entry
		def params = [graphtype: 'list']
		def body = [resource: [minimalEntryId]]
		def entryId = createEntry(contextId, params, body)

		// fetch URI of the created resource
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect { it.toString() }
		def resourceUri = entryRespJsonKeys.find { it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		assert resourceRespJson == [minimalEntryId]

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourceUri)

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = deleteResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		resourceConn2.inputStream.text == '[]'
	}

	def "DELETE /{context-id}/resource/{entry-id} as guest on non-existing entry should return 404 Not-Found"() {
		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/somethingNonExisting', '')

		then:
		deleteResourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "DELETE /{context-id}/resource/{entry-id} as admin on non-existing entry should return 404 Not-Found"() {
		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/somethingNonExisting')

		then:
		deleteResourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "DELETE /{context-id}/resource/{entry-id} on resource with file should remove the file"() {
		given:
		def requestResourceName = [name: 'None graph entry']
		def body = [resource: requestResourceName]
		def entryId = getOrCreateEntry(contextId, [id: 'noneId'], body)
		assert entryId.length() > 0

		// create a test binary file with some data
		def testBinFile = createTempBinaryFile('test', '.bin', ([0xDE, 0xAD, 0xBE, 0xEF] + "Hello".bytes.toList()) as byte[])
		def sendFileConn = EntryStoreClient.putRequestFile('/' + contextId + '/resource/' + entryId, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.getResponseCode() == HTTP_NO_CONTENT
		resourceConn.inputStream.text == ''

		def resourceConn2 = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)
		resourceConn2.getResponseCode() == HTTP_NO_CONTENT
		resourceConn2.inputStream.text == ''
	}

	def "DELETE /{context-id}/resource/{entry-id} on file resource as guest should respond with 404 Not Found and not delete the file"() {
		given:
		// create None-graph entry as admin and PUT a small binary file into it
		def fileEntryId = createEntry(contextId, [:], [resource: [name: 'Guest delete target']])
		def expectedBytes = [0xDE, 0xAD, 0xBE, 0xEF] as byte[]
		def testBinFile = createTempBinaryFile('test-guest-delete', '.bin', expectedBytes)
		def resourcePath = '/' + contextId + '/resource/' + fileEntryId
		def sendFileConn = EntryStoreClient.putRequestFile(resourcePath, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED

		when:
		// attempt delete as guest (empty asUser => no auth cookie)
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourcePath, '', '')

		then:
		// CWE-204 enumeration mask: anonymous gets 404, not 401
		deleteResourceConn.getResponseCode() == HTTP_NOT_FOUND
		// file is still on disk — admin can still read its bytes back
		def readBackConn = EntryStoreClient.getRequest(resourcePath)
		readBackConn.getResponseCode() == HTTP_OK
		readBackConn.inputStream.bytes == expectedBytes
	}

	def "DELETE /{context-id}/resource/{entry-id} on file resource as authenticated non-owner should respond with 403 Forbidden and not delete the file"() {
		given:
		// create None-graph entry as admin and PUT a small binary file into it
		def fileEntryId = createEntry(contextId, [:], [resource: [name: 'Non-owner delete target']])
		def expectedBytes = [0xDE, 0xAD, 0xBE, 0xEF] as byte[]
		def testBinFile = createTempBinaryFile('test-nonowner-delete', '.bin', expectedBytes)
		def resourcePath = '/' + contextId + '/resource/' + fileEntryId
		def sendFileConn = EntryStoreClient.putRequestFile(resourcePath, testBinFile,
			'admin', 'application/octet-stream')
		assert sendFileConn.getResponseCode() == HTTP_CREATED
		// create a regular user with no ACL on the entry
		def otherUsername = 'resDelOther@test.com'
		def otherUser = UserUtil.createUser(otherUsername)
		UserUtil.setUserPassword(otherUser['resourceUri'].toString(), password)

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourcePath, '', otherUsername)

		then:
		deleteResourceConn.getResponseCode() == HTTP_FORBIDDEN
		// file is still on disk — admin can still read its bytes back
		def readBackConn = EntryStoreClient.getRequest(resourcePath)
		readBackConn.getResponseCode() == HTTP_OK
		readBackConn.inputStream.bytes == expectedBytes
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true on link entry as guest should respond with 404 Not Found and not issue an outbound request"() {
		given:
		// register a WireMock stub for the would-be proxy target; if the auth check fails to fire,
		// the outbound DELETE will land here and the verify(0, ...) below will catch the regression
		def stubPath = '/it/proxy-delete-guest'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + stubPath
		// create the link entry as admin
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true', '', '')

		then:
		deleteResourceConn.getResponseCode() == HTTP_NOT_FOUND
		// the auth check must fire before the outbound proxy — no DELETE must reach the external URL
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true on link entry as authenticated non-owner should respond with 403 Forbidden and not issue an outbound request"() {
		given:
		def stubPath = '/it/proxy-delete-nonowner'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])
		// create a regular user with no ACL on the entry
		def otherUsername = 'resDelProxyOther@test.com'
		def otherUser = UserUtil.createUser(otherUsername)
		UserUtil.setUserPassword(otherUser['resourceUri'].toString(), password)

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true', '', otherUsername)

		then:
		deleteResourceConn.getResponseCode() == HTTP_FORBIDDEN
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true on link entry as admin should issue exactly one outbound DELETE and return 204"() {
		given:
		// Positive-path counterpart to the two guest/non-owner proxy specs above.
		// Locks in that WriteResource (not a stricter property like Administer) is the gate,
		// and that the auth check does not break the legitimate proxy-delete flow.
		def stubPath = '/it/proxy-delete-admin'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true blocks IPv4-literal SSRF target with 403 and no outbound"() {
		// IPv4 literal must be caught by the SSRF blacklist regex even though the WireMock
		// localhost origin is whitelisted — a literal IP is a different origin.
		given:
		def stubPath = '/it/proxy-delete-ssrf-ipv4'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://127.0.0.1:1' + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_FORBIDDEN
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true blocks non-http scheme with 400 and no outbound"() {
		// Scheme allowlist must reject ftp:// before any outbound attempt.
		given:
		def stubPath = '/it/proxy-delete-ssrf-scheme'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'ftp://example.org' + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true blocks same-host-different-port SSRF with 403"() {
		// Different port on the same host is a different origin: blacklist must fire even though
		// the WireMock origin (http://localhost:<wireMockPort>) is whitelisted.
		given:
		def stubPath = '/it/proxy-delete-ssrf-port'
		def stubAtWireMockOrigin = '/it/proxy-delete-ssrf-port-wm-side'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubAtWireMockOrigin))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:1' + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_FORBIDDEN
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(stubAtWireMockOrigin)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true re-validates redirect target and blocks 127.0.0.1 redirect"() {
		// On each redirect hop the validator re-runs; the redirect to 127.0.0.1 must hit the
		// blacklist and prevent any DELETE to the hostile target.
		given:
		def initialStub = '/it/proxy-delete-redirect-to-ipv4'
		def hostileStub = '/it/proxy-delete-redirect-to-ipv4-hostile'
		wireMockServer.stubFor(delete(urlPathEqualTo(initialStub))
			.willReturn(aResponse().withStatus(302).withHeader('Location', 'http://127.0.0.1:1' + hostileStub)))
		wireMockServer.stubFor(delete(urlPathEqualTo(hostileStub))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + initialStub
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_FORBIDDEN
		// First hop landed once at the WireMock; second hop must NOT fire.
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(initialStub)))
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo(hostileStub)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true re-validates redirect target and blocks ftp:// redirect"() {
		// Scheme allowlist re-applies on redirect.
		given:
		def initialStub = '/it/proxy-delete-redirect-to-ftp'
		wireMockServer.stubFor(delete(urlPathEqualTo(initialStub))
			.willReturn(aResponse().withStatus(302).withHeader('Location', 'ftp://example.org/x')))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + initialStub
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(initialStub)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true handles redirect without Location header"() {
		// 3xx without Location is a malformed upstream response — surface as 502, do not retry.
		given:
		def stubPath = '/it/proxy-delete-redirect-no-location'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(302))) // no Location header
		def targetUrl = 'http://localhost:' + wireMockServer.port() + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_BAD_GATEWAY
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true surfaces upstream 5xx as 502"() {
		// Upstream returns a non-redirect error (500) — EntryStore must surface this as 502 to the
		// client without relaying the upstream body (which may leak internal details).
		given:
		def stubPath = '/it/proxy-delete-upstream-500'
		wireMockServer.stubFor(delete(urlPathEqualTo(stubPath))
			.willReturn(aResponse().withStatus(500).withBody('internal upstream state — must not leak')))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + stubPath
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_BAD_GATEWAY
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(stubPath)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true aborts after MAX_REDIRECTS hops with 502"() {
		// Chain of MAX_REDIRECTS+1 stubs: the 17th hop must trip the cap before any
		// further outbound connection.
		given:
		def chainLength = 16
		(0..<chainLength).each { i ->
			wireMockServer.stubFor(delete(urlPathEqualTo('/it/proxy-delete-redir-loop-' + i))
				.willReturn(aResponse().withStatus(302).withHeader('Location',
					'http://localhost:' + wireMockServer.port() + '/it/proxy-delete-redir-loop-' + (i + 1))))
		}
		def targetUrl = 'http://localhost:' + wireMockServer.port() + '/it/proxy-delete-redir-loop-0'
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_BAD_GATEWAY
		(0..<chainLength).each { i ->
			wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo('/it/proxy-delete-redir-loop-' + i)))
		}
		wireMockServer.verify(0, deleteRequestedFor(urlPathEqualTo('/it/proxy-delete-redir-loop-' + chainLength)))
	}

	def "DELETE /{context-id}/resource/{entry-id}?proxy=true follows relative redirect to second hop and returns 204"() {
		// Relative Location header must resolve against the original URL, not be treated as an origin jump.
		given:
		def initialStub = '/it/proxy-delete-rel-redir-1'
		def secondStub = '/it/proxy-delete-rel-redir-2'
		wireMockServer.stubFor(delete(urlPathEqualTo(initialStub))
			.willReturn(aResponse().withStatus(302).withHeader('Location', secondStub)))
		wireMockServer.stubFor(delete(urlPathEqualTo(secondStub))
			.willReturn(aResponse().withStatus(204)))
		def targetUrl = 'http://localhost:' + wireMockServer.port() + initialStub
		def linkEntryId = createEntry(contextId, [entrytype: 'link', resource: targetUrl])

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest('/' + contextId + '/resource/' + linkEntryId + '?proxy=true')

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(initialStub)))
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(secondStub)))
	}

	def "DELETE /{context-id}/resource/{entry-id} does not delete resource if it has type String"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		// fetch URI of created resource for the String entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('text/plain')
		assert resourceConn.inputStream.text == someText

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourceUri)

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = deleteResourceConn.inputStream.text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		// DELETE is a silent no-op for String resources: ResourceService.deleteLocalResource only
		// handles GraphType.List and GraphType.None, so the resource stays readable after a 204.
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('text/plain')
		resourceConn2.inputStream.text == someText
	}

	def "POST /{context-id}/resource/{entry-id} as guest should respond with Not Found 404"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		// create source list with the minimal entry
		def params = [graphtype: 'list']
		def sourceEntryId = createEntry(contextId, params, [resource: [givenEntryId]])
		// create target list with no entries
		def targetEntryId = createEntry(contextId, params, [resource: []])

		// fetch URI of created resource for the SOURCE entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + sourceEntryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def sourceEntryKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def sourceResourceUri = sourceEntryKeys.find { it -> it.contains('resource') }
		// fetch source resource, should contain above created entry
		def sourceResourceConn = EntryStoreClient.getRequest(sourceResourceUri)
		assert sourceResourceConn.getResponseCode() == HTTP_OK
		assert sourceResourceConn.getContentType().contains('application/json')
		def sourceResourceRespJson = JSON_PARSER.parseText(sourceResourceConn.inputStream.text)
		assert sourceResourceRespJson == [givenEntryId]

		// fetch URI of created resource for the TARGET entry
		def targetEntryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + targetEntryId)
		assert targetEntryConn.getResponseCode() == HTTP_OK
		def targetEntryRespJson = JSON_PARSER.parseText(targetEntryConn.inputStream.text)
		assert targetEntryRespJson['info'] != null
		def targetEntryKeys = (targetEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def targetResourceUri = targetEntryKeys.find { it -> it.contains('resource') }
		// fetch target resource, should be empty
		def targetResourceConn = EntryStoreClient.getRequest(targetResourceUri)
		assert targetResourceConn.getResponseCode() == HTTP_OK
		def targetResourceRespJson = JSON_PARSER.parseText(targetResourceConn.inputStream.text)
		assert targetResourceRespJson == []

		def postParams = [moveEntry: contextId + '/entry/' + givenEntryId,
						  fromList : sourceResourceUri]

		when:
		// move entry from source to target list
		def editResourceConn = EntryStoreClient.postRequest(targetResourceUri + convertMapToQueryParams(postParams), '', '')

		then:
		editResourceConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/resource/{entry-id} as admin should move entry between lists"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		// create source list with the minimal entry
		def params = [graphtype: 'list']
		def sourceEntryId = createEntry(contextId, params, [resource: [givenEntryId]])
		// create target list with no entries
		def targetEntryId = createEntry(contextId, params, [resource: []])

		// fetch URI of created resource for the SOURCE entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + sourceEntryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def sourceEntryKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def sourceResourceUri = sourceEntryKeys.find { it -> it.contains('resource') }
		// fetch source resource, should contain above created entry
		def sourceResourceConn = EntryStoreClient.getRequest(sourceResourceUri)
		assert sourceResourceConn.getResponseCode() == HTTP_OK
		assert sourceResourceConn.getContentType().contains('application/json')
		def sourceResourceRespJson = JSON_PARSER.parseText(sourceResourceConn.inputStream.text)
		assert sourceResourceRespJson == [givenEntryId]

		// fetch URI of created resource for the TARGET entry
		def targetEntryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + targetEntryId)
		assert targetEntryConn.getResponseCode() == HTTP_OK
		def targetEntryRespJson = JSON_PARSER.parseText(targetEntryConn.inputStream.text)
		assert targetEntryRespJson['info'] != null
		def targetEntryKeys = (targetEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def targetResourceUri = targetEntryKeys.find { it -> it.contains('resource') }
		// fetch target resource, should be empty
		def targetResourceConn = EntryStoreClient.getRequest(targetResourceUri)
		assert targetResourceConn.getResponseCode() == HTTP_OK
		def targetResourceRespJson = JSON_PARSER.parseText(targetResourceConn.inputStream.text)
		assert targetResourceRespJson == []

		def postParams = [moveEntry: contextId + '/entry/' + givenEntryId,
						  fromList : sourceResourceUri]

		when:
		// move entry from source to target list
		def editResourceConn = EntryStoreClient.postRequest(targetResourceUri + convertMapToQueryParams(postParams))

		then:
		editResourceConn.getResponseCode() == HTTP_OK
		editResourceConn.getContentType().contains('application/json')
		def editResourceJson = JSON_PARSER.parseText(editResourceConn.inputStream.text)
		// POST on target list to move an entry from source list, returns moved entryUri for some reason, instead of the new state of POST item (target list)
		editResourceJson['entryURI'] == EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + givenEntryId

		// fetch target resource again, should contain moved entry
		def targetResourceConn2 = EntryStoreClient.getRequest(targetResourceUri)
		targetResourceConn2.getResponseCode() == HTTP_OK
		def targetResourceRespJson2 = JSON_PARSER.parseText(targetResourceConn2.inputStream.text)
		targetResourceRespJson2 == [givenEntryId]

		// fetch source resource again, should be empty now
		def sourceResourceConn2 = EntryStoreClient.getRequest(sourceResourceUri)
		sourceResourceConn2.getResponseCode() == HTTP_OK
		def sourceResourceRespJson2 = JSON_PARSER.parseText(sourceResourceConn2.inputStream.text)
		sourceResourceRespJson2 == []
	}

	def "POST /{context-id}/resource/{entry-id}?import with ZIP containing RDF file should return 501 Not Implemented"() {
		given:
		def importContextId = 'rdf-import-ctx'
		getOrCreateContext([contextId: importContextId])
		def listEntryId = getOrCreateEntry(importContextId, [id: 'rdf-import-list', graphtype: 'list'])

		// create a ZIP file containing a .rdf file
		def baos = new ByteArrayOutputStream()
		def zos = new ZipOutputStream(baos)
		zos.putNextEntry(new ZipEntry('test-data.rdf'))
		zos.write('<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"></rdf:RDF>'.bytes)
		zos.closeEntry()
		zos.close()
		def zipBytes = baos.toByteArray()

		when:
		def conn = EntryStoreClient.sendRequestAsStream(
				HttpMethod.POST,
				'/' + importContextId + '/resource/' + listEntryId + '?import=true',
				new ByteArrayInputStream(zipBytes),
				'admin',
				'application/zip')

		then:
		conn.getResponseCode() == HTTP_NOT_IMPLEMENTED
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.errorStream.text)
		respJson['error'] != null
		respJson['error'].toString().contains('RDF resource import is not yet implemented')
	}

	def "GET /{context-id}/resource/{entry-id} with unsupported rdfFormat on Graph resource should return 406 Not Acceptable"() {
		given:
		def params = [graphtype: 'graph']
		def entryId = createEntry(contextId, params)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=text/html')

		then:
		resourceConn.getResponseCode() == HTTP_NOT_ACCEPTABLE
	}

	def "GET /{context-id}/resource/{entry-id} with rdfFormat=text/turtle on Graph resource should return 200 with Turtle content"() {
		given:
		def params = [graphtype: 'graph']
		def entryId = createEntry(contextId, params)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=text/turtle')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('text/turtle')
		def responseBody = resourceConn.inputStream.text
		responseBody.length() > 0
	}

	def "GET /{context-id}/resource/{entry-id} with unsupported rdfFormat on None (binary) resource should return normally"() {
		given:
		def body = [resource: [name: 'None graph entry for format test']]
		def entryId = createEntry(contextId, [:], body)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=text/html')

		then:
		resourceConn.getResponseCode() == HTTP_NO_CONTENT
	}

	def "GET /{context-id}/resource/{entry-id} with unsupported rdfFormat on List resource should return 406 Not Acceptable"() {
		given:
		def givenEntryId = createEntry(contextId, [:])
		def params = [graphtype: 'list']
		def body = [resource: [givenEntryId]]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=text/html')

		then:
		resourceConn.getResponseCode() == HTTP_NOT_ACCEPTABLE
	}

	def "GET /{context-id}/resource/{entry-id} with rdfFormat=application/rdf+xml on Graph resource should return 200 with RDF/XML content"() {
		given:
		def params = [graphtype: 'graph']
		def entryId = createEntry(contextId, params)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=application/rdf%2Bxml')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rdf+xml')
		def responseBody = resourceConn.inputStream.text
		responseBody.length() > 0
	}

	def "GET /{context-id}/resource/{entry-id} with rdfFormat=text/turtle on List resource should return 200 with Turtle content"() {
		given:
		def givenEntryId = createEntry(contextId, [:])
		def params = [graphtype: 'list']
		def body = [resource: [givenEntryId]]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId + '?rdfFormat=text/turtle')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('text/turtle')
		def responseBody = resourceConn.inputStream.text
		responseBody.length() > 0
	}

	def "GET /{context-id}/resource/{entry-id} with Accept header containing supported type among unsupported ones on Graph resource should return 200"() {
		given:
		def params = [graphtype: 'graph']
		def entryId = createEntry(contextId, params)
		assert entryId.length() > 0

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId, 'admin', 'text/html, text/turtle')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('text/turtle')
	}

	def "PUT /{context-id}/resource/{entry-id} with malformed Turtle on Graph resource should return Bad-Request 400"() {
		given:
		def params = [graphtype: 'graph']
		def entryId = createEntry(contextId, params)
		assert entryId.length() > 0
		def resourceUri = '/' + contextId + '/resource/' + entryId

		when:
		def putResourceConn = EntryStoreClient.putRequest(resourceUri,
			'this is not valid turtle <<<>>>', 'admin', 'text/turtle')

		then:
		putResourceConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /{context-id}/resource/{entry-id} with #shape filename in Content-Disposition persists #expectedStored"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		when:
		def putConn = EntryStoreClient.putRequest(
			'/' + contextId + '/resource/' + entryId,
			'',
			'admin',
			'application/octet-stream',
			['Content-Disposition': 'attachment; filename="' + filenameInHeader + '"']
		)

		then:
		putConn.responseCode == HTTP_CREATED

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def resourceUri = entryJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE][0]['value'].toString()
		def rdfsLabel = 'http://www.w3.org/2000/01/rdf-schema#label'

		then:
		entryConn.responseCode == HTTP_OK
		entryJson['info'][resourceUri][rdfsLabel][0]['value'] == expectedStored

		// Only ASCII-safe header shapes are exercised here — non-ASCII bytes (raw NBSP,
		// raw ZWSP) in HTTP header values have ambiguous encoding across HttpURLConnection
		// / Jetty. The sanitization of decoded non-ASCII code points is covered at the
		// unit level by FileUtilTest; the encoded shapes below survive ASCII transmission
		// and exercise decode + strip end-to-end through the controller.
		where:
		shape                | filenameInHeader        || expectedStored
		'%20 (encoded space)'| 'datei.exe%20'          || 'datei.exe_dangerous'
		'trailing dot'       | 'datei.exe.'            || 'datei.exe_dangerous'
		'%00 (null byte)'    | 'datei.exe%00'          || 'datei.exe_dangerous'
		'truncated %2'       | 'datei.exe%2'           || 'datei.exe%2_dangerous'
		'double-encoded %20' | 'datei.exe%2520'        || 'datei.exe_dangerous'
		'encoded forward-/'  | 'datei.exe%2F'          || 'datei.exe_dangerous'
		'encoded ZWSP'       | 'datei.exe%E2%80%8B'    || 'datei.exe_dangerous'
	}

	def "PUT multipart /{context-id}/resource/{entry-id} with malicious filename persists _dangerous suffix"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		// Create a temp file whose name carries the malicious characters; multipart envelope
		// reuses File.getName() in EntryStoreClient.buildMultipartContent.
		def tempDir = Files.createTempDirectory('entrystore-mp-it').toFile()
		def maliciousFile = new File(tempDir, 'datei.exe%20')
		maliciousFile.bytes = [0xDE, 0xAD, 0xBE, 0xEF] as byte[]
		// Distinct per-part Content-Type — pins that the multipart code path was exercised.
		// A routing regression to the raw PUT handler would store the request-level
		// 'multipart/form-data; boundary=...' instead of this marker.
		def partContentType = 'application/x-test-multipart-marker'

		when:
		def putConn = EntryStoreClient.putRequestMultiPart(
			'/' + contextId + '/resource/' + entryId,
			maliciousFile,
			'admin',
			[:],
			partContentType
		)

		then:
		putConn.responseCode == HTTP_CREATED

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def resourceUri = entryJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE][0]['value'].toString()
		def rdfsLabel = 'http://www.w3.org/2000/01/rdf-schema#label'

		then:
		entryConn.responseCode == HTTP_OK
		entryJson['info'][resourceUri][rdfsLabel][0]['value'] == 'datei.exe_dangerous'
		entryJson['info'][resourceUri][NameSpaceConst.DC_TERM_FORMAT][0]['value'] == partContentType

		cleanup:
		maliciousFile?.delete()
		tempDir?.deleteDir()
	}

	def "PUT multipart /{context-id}/resource/{entry-id} with a part not named 'file' stores the file"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		def uploadedFile = File.createTempFile('partName', '.html')
		uploadedFile.deleteOnExit()
		uploadedFile.bytes = '<html>Hello</html>'.bytes

		when:
		// Clients that build the body by iterating a FileList send indexed part names such as "0"
		def putConn = EntryStoreClient.putRequestMultiPart(
			'/' + contextId + '/resource/' + entryId,
			uploadedFile,
			'admin',
			[:],
			'text/html',
			'0'
		)

		then:
		putConn.responseCode == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.responseCode == HTTP_OK
		resourceConn.contentType.contains('text/html')
		resourceConn.inputStream.readAllBytes() == uploadedFile.bytes

		and: 'the stored filename comes from the part filename, never from the part name'
		fileNameOf(contextId, entryId) == uploadedFile.name

		cleanup:
		uploadedFile?.delete()
	}

	def "PUT multipart /{context-id}/resource/{entry-id} finds the real upload behind a placeholder of the same name"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		// Two <input type="file" name="file"> where the first is unfilled, i.e. two parts sharing a name
		def fileParts = [
			[name: 'file', filename: '', contentType: 'application/octet-stream', bytes: new byte[0]],
			[name: 'file', filename: 'real.html', contentType: 'text/html', bytes: '<html>Real</html>'.bytes]
		]

		when:
		def putConn = EntryStoreClient.putRequestMultiPartParts('/' + contextId + '/resource/' + entryId, fileParts)

		then:
		putConn.responseCode == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.responseCode == HTTP_OK
		resourceConn.inputStream.readAllBytes() == '<html>Real</html>'.bytes
		fileNameOf(contextId, entryId) == 'real.html'
	}

	def "PUT multipart /{context-id}/resource/{entry-id} uses the real upload, not an unfilled file input"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		// An unfilled <input type="file"> is submitted as a zero-byte part with an empty filename,
		// and the servlet layer reports it as a file part like any other
		def fileParts = [
			[name: 'file', filename: '', contentType: 'application/octet-stream', bytes: new byte[0]],
			[name: '0', filename: 'real.html', contentType: 'text/html', bytes: '<html>Real</html>'.bytes]
		]

		when:
		def putConn = EntryStoreClient.putRequestMultiPartParts('/' + contextId + '/resource/' + entryId, fileParts)

		then:
		putConn.responseCode == HTTP_CREATED

		when:
		def resourceConn = EntryStoreClient.getRequest('/' + contextId + '/resource/' + entryId)

		then:
		resourceConn.responseCode == HTTP_OK
		resourceConn.inputStream.readAllBytes() == '<html>Real</html>'.bytes
		fileNameOf(contextId, entryId) == 'real.html'
	}

	def "PUT multipart /{context-id}/resource/{entry-id} with only an unfilled file input should respond with Bad Request 400"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		def fileParts = [[name: '0', filename: '', contentType: 'application/octet-stream', bytes: new byte[0]]]

		when:
		def putConn = EntryStoreClient.putRequestMultiPartParts('/' + contextId + '/resource/' + entryId, fileParts)

		then:
		putConn.responseCode == HTTP_BAD_REQUEST
		JSON_PARSER.parseText(putConn.errorStream.text)['error'] == 'Multipart request contains no file part'
	}

	def "PUT multipart /{context-id}/resource/{entry-id} with a part carrying no filename falls back to the entry id"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		def named = [[name: 'file', filename: 'first.html', contentType: 'text/html', bytes: '<html>First</html>'.bytes]]
		assert EntryStoreClient.putRequestMultiPartParts('/' + contextId + '/resource/' + entryId, named).responseCode == HTTP_CREATED
		assert fileNameOf(contextId, entryId) == 'first.html'

		when: 'the entry is re-uploaded through a part that carries content but no filename'
		def unnamed = [[name: 'file', filename: '', contentType: 'text/plain', bytes: 'second'.bytes]]
		def putConn = EntryStoreClient.putRequestMultiPartParts('/' + contextId + '/resource/' + entryId, unnamed)

		then:
		putConn.responseCode == HTTP_CREATED

		and: 'the name of the previous upload does not survive onto the new content'
		fileNameOf(contextId, entryId) == entryId
	}

	def "PUT multipart /{context-id}/resource/{entry-id} without any file part should respond with Bad Request 400"() {
		given:
		def entryId = createEntry(contextId, [:])
		assert entryId.length() > 0

		when:
		def putConn = EntryStoreClient.putRequestMultiPartWithoutFile(
			'/' + contextId + '/resource/' + entryId,
			[mimeType: 'text/html']
		)

		then:
		putConn.responseCode == HTTP_BAD_REQUEST
		JSON_PARSER.parseText(putConn.errorStream.text)['error'] == 'Multipart request contains no file part'
	}

	/** Reads the filename stored for an entry, i.e. the rdfs:label of its resource. */
	def fileNameOf(String contextId, String entryId) {
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.responseCode == HTTP_OK
		def entryJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def resourceUri = entryJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE][0]['value'].toString()
		return entryJson['info'][resourceUri]['http://www.w3.org/2000/01/rdf-schema#label'][0]['value']
	}
}
