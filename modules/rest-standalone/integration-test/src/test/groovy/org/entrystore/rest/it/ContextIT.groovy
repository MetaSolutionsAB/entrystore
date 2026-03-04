package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class ContextIT extends BaseSpec {

	def "POST /_principals/groups as guest should respond with UNAUTHORIZED 401"() {
		given:
		def contextName = 'someName'

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=' + contextName, null, '')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
		// Restlet has it as FORBIDDEN, was changed in Spring
		//connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /_principals/groups as non-admin user should respond with FORBIDDEN 403"() {
		given:
		def contextName = 'someName'

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=' + contextName, null, 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /_principals/groups as member of admin group should create new group and context"() {
		given:
		def contextName = 'someNamezzz'

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=' + contextName, null, 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_CREATED
	}

	def "POST /_principals/groups as admin should create new group and context"() {
		given:
		def contextName = 'someName'

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=' + contextName)

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getHeaderField('Location') != null
		connection.getHeaderField('Location').contains('/_principals/entry/')
		// shouldn't it return the created instance data?
		//connection.getContentType().contains('application/json')
		connection.getInputStream().text == ''
		def groupId = connection.getHeaderField('Location').find(/\/_principals\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
		groupId.length() > 0

		def principalConn = EntryStoreClient.getRequest('/_principals/entry/' + groupId)
		principalConn.getResponseCode() == HTTP_OK
		principalConn.getContentType().contains('application/json')
		def principalJson = JSON_PARSER.parseText(principalConn.getInputStream().text)
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

		def contextConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextId)
		contextConn.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(contextConn.getInputStream().text)
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

	def "POST /_principals/groups as admin should not create group and context with a duplicated name"() {
		given:
		def contextName = 'someName'

		when:
		def connection = EntryStoreClient.postRequest('/_principals/groups?name=' + contextName)

		then:
		connection.getResponseCode() == HTTP_CONFLICT
		def responseBody = connection.getErrorStream().text
		responseBody != null
		responseBody.length() > 10
	}

	def "POST /_principals/groups as admin should create new group and context with specified ID"() {
		given:
		def contextId = '12345'
		def params = [contextId: contextId, name: 'someName2']

		when:
		def groupId = createContext(params)

		then:
		groupId != null
		groupId.length() > 0
		def conn = EntryStoreClient.getRequest('/_contexts/entry/' + contextId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(conn.getInputStream().text)
		responseJson['entryId'] == contextId
		responseJson['name'] == 'someName2'
		responseJson['info'] != null
	}

	def "POST /_contexts?id={id} as guest should respond with Unauthorized 401"() {
		given:
		def contextId = 'new-context-2'
		def params = [id: contextId, graphtype: 'context', name: 'someName3']

		when:
		def connection = EntryStoreClient.postRequest('/_contexts' + convertMapToQueryParams(params), '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /_contexts?id={id} as non-admin user should respond with Forbidden"() {
		given:
		def contextId = 'new-context-2'
		def params = [id: contextId, graphtype: 'context', name: 'someName3']

		when:
		def connection = EntryStoreClient.postRequest('/_contexts' + convertMapToQueryParams(params), '', 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		// Restlet has it as UNAUTHORIZED, was changed in Spring
		//conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /_contexts?id={id} as admin should create a new context with specified ID"() {
		given:
		def contextId = 'new-context'
		def params = [id: contextId, graphtype: 'context', name: 'someName3']

		when:
		def connection = EntryStoreClient.postRequest('/_contexts' + convertMapToQueryParams(params))

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['entryId'] == contextId

		def getConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextId + '?includeAll')
		getConn.getResponseCode() == HTTP_OK
		getConn.getContentType().contains('application/json')
		def getResponseJson = JSON_PARSER.parseText(getConn.getInputStream().text)
		getResponseJson['entryId'] == contextId
		getResponseJson['info'] != null
	}

	def "GET /{context-id} as guest should respond with UNAUTHORIZED 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
	}

	def "GET /_contexts as non-admin user should respond with FORBIDDEN 403"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts', 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		// Restlet has it as UNAUTHORIZED, was changed in Spring
		//connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "GET /{context-id} as member of admin group should return context entries"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts', 'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson.collect().contains('_contexts')
		responseJson.collect().contains('_principals')
	}

	def "GET /{context-id} as admin should return context entries"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson.collect().contains('_contexts')
		responseJson.collect().contains('_principals')
	}

	// TODO: Fix the vuln of Information Disclosure via Error Messages - guest gets 404 on non-existing context-id and 401 on existing context-id
	def "GET /{context-id} as guest for non-existing context should return UNAUTHORIZED 401"() {
		when:
		def connection = EntryStoreClient.getRequest('/222-random-name-222', '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		// Restlet has it as NOT_FOUND, was changed in Spring
		//conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	// TODO: Verify below behaviour: for a non-admin user and a valid context-id we get 401, but for invalid context-id we get 404
	def "GET /{context-id} as non-admin user for non-existing context should return FORBIDDEN 403"() {
		when:
		def connection = EntryStoreClient.getRequest('/222-random-name-222', 'user')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		// Restlet has it as NOT_FOUND, was changed in Spring
		//conn.getResponseCode() == HTTP_FORBIDDEN
	}

	def "GET /{context-id} as admin for non-existing context should return NOT_FOUND 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/222-random-name-234')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'].toString().contains('The requested context ID does not exist')
	}

	def "GET /{context-id}?deleted as admin should return empty list of entries"() {

		when:
		def connection = EntryStoreClient.getRequest('/_contexts?deleted')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson.collect().size() == 0
	}

	def "GET /{context-id}?entryname=non-existing-entry-name as admin should return an empty list"() {

		when:
		def connection = EntryStoreClient.getRequest('/_contexts?entryname=non-existing-entry-name')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson.collect() == []
	}

	def "GET /_contexts?entryname=some-random-name as guest should respond with UNAUTHORIZED"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts?entryname=some-random-name', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
	}

	def "GET /_contexts?entryname=some-random-name as non-admin user should respond with FORBIDDEN"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts?entryname=some-random-name', 'user')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
		// Restlet has it as UNAUTHORIZED, was changed in Spring
		//connection.getResponseCode() == HTTP_FORBIDDEN
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
	}
}
