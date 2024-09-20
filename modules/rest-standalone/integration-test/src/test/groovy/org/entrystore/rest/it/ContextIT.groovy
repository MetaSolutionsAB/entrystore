package org.entrystore.rest.it

import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class ContextIT extends BaseSpec {

	def "POST /_principals/groups should create new group and context"() {
		given:
		def contextName = 'someName'

		when:
		def connection = client.postRequest('/_principals/groups?name=' + contextName, '', 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getHeaderField('Location') != null
		connection.getHeaderField('Location').contains('/_principals/entry/')
		// shouldn't it return the created instance data?
		//connection.getContentType().contains('application/json')
		connection.getInputStream().text == ''
		def contextId = connection.getHeaderField('Location').find(/\/_principals\/entry\/(\d+)$/) { match, id -> id } as Integer
		contextId > 0

		def contextConn = client.getRequest('/_contexts/entry/' + contextId, 'admin')
//		def contextConn = client.getRequest('/' + contextId, 'admin')
		contextConn.getResponseCode() == HTTP_OK
		def responseJson = new JsonSlurper().parseText(contextConn.getInputStream().text)
		responseJson['entryId'] == contextId.toString()
		responseJson['name'] == contextName
		responseJson['info'] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['type'] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['type'].collect().size() == 1
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['type'].collect()[0] == 'uri'
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['value'] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['value'].collect().size() == 1
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RdfType]['value'].collect()[0] == NameSpaceConst.EsContext
	}

	def "POST /_principals/groups should not create group and context with a duplicated name"() {
		given:
		def contextName = 'someName'

		when:
		def connection = client.postRequest('/_principals/groups?name=' + contextName, '', 'admin')

		then:
		connection.getResponseCode() == HTTP_CONFLICT
		def responseBody = connection.getErrorStream().text
		responseBody != null
		responseBody.length() > 10
	}

	def "POST /_principals/groups should create new group and context with specified ID"() {
		given:
		def contextId = '12345'
		def params = [contextId: contextId, name: 'someName2']

		when:
		def groupId = createContext(params)

		then:
		groupId > 0
		def conn = client.getRequest('/_contexts/entry/' + contextId, 'admin')
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = new JsonSlurper().parseText(conn.getInputStream().text)
		responseJson['entryId'] == contextId
		responseJson['name'] == 'someName2'
		responseJson['info'] != null
	}
}
