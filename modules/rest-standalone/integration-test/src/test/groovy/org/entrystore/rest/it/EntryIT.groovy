package org.entrystore.rest.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class EntryIT extends BaseSpec {

	def "POST /{context-id} should create new entry in the context"() {
		given:
		def contextId = '10'
		def groupId = createContext([name: 'someName10', contextId: contextId])
		def body = JsonOutput.toJson([entrytype: 'link'])

		when:
		def connection = client.postRequest('/' + contextId, body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED

		// fetch created entry
		def contextConn = client.getRequest('/' + contextId, 'admin')
		contextConn.getResponseCode() == HTTP_OK
		def responseJsonList = (new JsonSlurper()).parseText(contextConn.getInputStream().text).collect()
		responseJsonList.size() == 1
		responseJsonList[0] != null
		!responseJsonList[0].toString().isEmpty()
		def entryId = responseJsonList[0] as Integer
		entryId > 0

		def entryConn = client.getRequest('/' + contextId + '/entry/' + entryId, 'admin')
		entryConn.getResponseCode() == HTTP_OK
		// TODO: assert some fields of the entry are returned, either here or as a separate test
	}

	def "POST /{context-id} should throw unauthorized for non-admin user"() {
		given:
		def contextId = '11'
		def groupId = createContext([name: 'someName11', contextId: contextId])
		def body = JsonOutput.toJson([entrytype: 'local'])

		when:
		def connection = client.postRequest('/' + contextId, body)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}
}
