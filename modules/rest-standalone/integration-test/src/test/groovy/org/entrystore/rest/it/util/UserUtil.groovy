package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.entrystore.rest.it.BaseSpec

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class UserUtil {
	def static createUser(String username) {
		def user = [:]
		user['username'] = username

		// create a User entry
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: [name: username]])
		def connection = EntryStoreClient.postRequest('/_principals' + BaseSpec.convertMapToQueryParams(params), body)
		assert connection.getResponseCode() == HTTP_CREATED
		assert connection.getContentType().contains('application/json')
		def responseJson = BaseSpec.JSON_PARSER.parseText(connection.getInputStream().text)
		assert responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0
		user['entryId'] = entryId

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = BaseSpec.JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		user['resourceUri'] = resourceUri

		return user
	}

	def static setUserPassword(String resourceUri, String password = 'newPass12345') {
		def requestBody = JsonOutput.toJson([
				password: password
		])
		def setPasswordConn = EntryStoreClient.putRequest(resourceUri, requestBody)
		assert setPasswordConn.getResponseCode() == HTTP_NO_CONTENT
	}
}
