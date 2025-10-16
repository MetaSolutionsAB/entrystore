package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.entrystore.rest.it.BaseSpec

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class UserUtil {
	def static createUser(String username) {
		def user = [:]
		user['username'] = username

		// create a User entry
		def params = [graphtype: 'user']
		def body = [resource: [name: username]]
		def entryId = BaseSpec.createEntry('_principals', params, body)
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
