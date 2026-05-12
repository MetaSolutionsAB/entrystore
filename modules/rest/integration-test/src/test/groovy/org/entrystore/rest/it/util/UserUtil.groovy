package org.entrystore.rest.it.util

import groovy.json.JsonOutput
import org.entrystore.rest.it.BaseSpec

import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class UserUtil {

	static def createUser(String username, String homecontext = null, boolean isAdmin = false) {
		def user = [:]
		user['username'] = username
		user['entryId'] = createUserEntry(username, homecontext, isAdmin ? EntryStoreClient.adminsGroupUri : null)

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + user['entryId'])
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = BaseSpec.JSON_PARSER.parseText(entryConn.inputStream.text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		user['resourceUri'] = resourceUri

		return user
	}

	static def createUserEntry(String username, String homecontext = null, String groupURI = null) {
		def params = [graphtype: 'user']
		def body = [resource: [name: username]]
		if (homecontext) {
			body.resource.homecontext = homecontext
		}
		if (groupURI) {
			params.groupURI = groupURI
		}
		return BaseSpec.createEntry('_principals', params, body)
	}

	static def setUserPassword(String resourceUri, String password) {
		def requestBody = JsonOutput.toJson([
			password: password
		])
		def setPasswordConn = EntryStoreClient.putRequest(resourceUri, requestBody)
		assert setPasswordConn.getResponseCode() == HTTP_NO_CONTENT
	}
}
