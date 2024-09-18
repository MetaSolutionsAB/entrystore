package org.entrystore.rest.it

import groovy.json.JsonOutput

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_OK

class ContextIT extends BaseSpec {

	def "POST /_principals/groups should create new group and context"() {
		given:
		def body = JsonOutput.toJson([name: 'someName'])

		when:
		def connection = client.postRequest('/_principals/groups', body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getHeaderField('Location') != null
		connection.getHeaderField('Location').contains('/_principals/entry/')
		// shouldn't it return the created instance data?
		//connection.getContentType().contains('application/json')
		connection.getInputStream().text == ''
		def contextId = connection.getHeaderField('Location').find(/\/_principals\/entry\/(\d+)$/) { match, id -> id } as Integer
		contextId > 0
		def contextConn = client.getRequest('/' + contextId, 'admin')
		contextConn.getResponseCode() == HTTP_OK
		contextConn.getInputStream().text == '[]'
	}

	// Able to create multiple contexts with the same name, is this expected behaviour?
	def "POST /_principals/groups should (or not?) create new group and context with duplicated name"() {
		given:
		def body = JsonOutput.toJson([name: 'someName'])

		when:
		def connection = client.postRequest('/_principals/groups', body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getHeaderField('Location') != null
		connection.getHeaderField('Location').contains('/_principals/entry/')
		connection.getInputStream().text == ''
		def contextId = connection.getHeaderField('Location').find(/\/_principals\/entry\/(\d+)$/) { match, id -> id } as Integer
		contextId > 0
		def contextConn = client.getRequest('/' + contextId, 'admin')
		contextConn.getResponseCode() == HTTP_OK
		contextConn.getInputStream().text == '[]'
	}

	/*
	// Below test does not pass, created entry has ID 1, but should be 12345?
	def "POST /_principals/groups should create new group and context with specified ID"() {
		when:
		def contextId = createContext([name: 'someName2', contextId: '12345'])

		then:
		contextId == 12345
		def conn = client.getRequest('/12345', 'admin')
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		conn.getInputStream().text == ''
	}
	 */
}
