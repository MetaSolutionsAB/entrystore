package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT

class UserResourceIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForInfo@test.com', newPassword)
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
	}

	def "GET /auth/user should return info about currently logged-in user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForInfo@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: 'newPass12345',
				language: 'SE'
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT

		when:
		def info = EntryStoreClient.getRequest('/auth/user', 'userForInfo@test.com')

		then:
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['id'] == entryId
		infoRespJson['user'] == 'userForInfo@test.com'.toLowerCase()
		infoRespJson['language'] == 'SE'
	}

}
