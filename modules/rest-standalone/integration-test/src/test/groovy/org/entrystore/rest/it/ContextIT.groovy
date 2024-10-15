package org.entrystore.rest.it

import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class ContextIT extends BaseSpec {

	// TODO: Add tests to create only a group (without context) and only a context (without group)

	def "POST /_principals/groups should create new group and context"() {
		given:
		def contextName = 'someName'

		when:
		def connection = client.postRequest('/_principals/groups?name=' + contextName)

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getHeaderField('Location') != null
		connection.getHeaderField('Location').contains('/_principals/entry/')
		// shouldn't it return the created instance data?
		//connection.getContentType().contains('application/json')
		connection.getInputStream().text == ''
		def groupId = connection.getHeaderField('Location').find(/\/_principals\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
		groupId.length() > 0

		def principalConn = client.getRequest('/_principals/entry/' + groupId)
		principalConn.getResponseCode() == HTTP_OK
		principalConn.getContentType().contains('application/json')
		def principalJson = new JsonSlurper().parseText(principalConn.getInputStream().text)
		principalJson['info'] != null
		principalJson['info'][EntryStoreClient.baseUrl + '/_principals/resource/' + groupId] != null
		principalJson['info'][EntryStoreClient.baseUrl + '/_principals/resource/' + groupId][NameSpaceConst.TERM_HOME_CONTEXT] != null
		def homeContexts = principalJson['info'][EntryStoreClient.baseUrl + '/_principals/resource/' + groupId][NameSpaceConst.TERM_HOME_CONTEXT].collect()
		homeContexts.size() == 1
		homeContexts[0]['type'] == 'uri'
		homeContexts[0]['value'] != null
		homeContexts[0]['value'].toString().startsWith(EntryStoreClient.baseUrl + '/_contexts/entry/')
		def contextId = homeContexts[0]['value'].toString().find(/\/_contexts\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
		contextId.length() > 0

		def contextConn = client.getRequest('/_contexts/entry/' + contextId)
		contextConn.getResponseCode() == HTTP_OK
		def responseJson = new JsonSlurper().parseText(contextConn.getInputStream().text)
		responseJson['entryId'] == contextId.toString()
		responseJson['name'] == contextName
		responseJson['info'] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId] != null
		responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RDF_TYPE] != null
		def contextTypes = responseJson['info'][EntryStoreClient.baseUrl + '/' + contextId][NameSpaceConst.RDF_TYPE].collect()
		contextTypes.size() == 1
		contextTypes[0]['type'] == 'uri'
		contextTypes[0]['value'] == NameSpaceConst.TERM_CONTEXT
	}

	def "POST /_principals/groups should not create group and context with a duplicated name"() {
		given:
		def contextName = 'someName'

		when:
		def connection = client.postRequest('/_principals/groups?name=' + contextName)

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
		groupId != null
		groupId.length() > 0
		def conn = client.getRequest('/_contexts/entry/' + contextId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = new JsonSlurper().parseText(conn.getInputStream().text)
		responseJson['entryId'] == contextId
		responseJson['name'] == 'someName2'
		responseJson['info'] != null
	}
}
