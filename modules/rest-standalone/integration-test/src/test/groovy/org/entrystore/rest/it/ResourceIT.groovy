package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class ResourceIT extends BaseSpec {

	def static contextId = '80'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/resource/{entry-id} should return created resource data"() {
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		assert entryRespJson['info'][entryUri] != null
		assert entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		assert entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		assert createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		when:
		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		// Response says content-type is JSON, but it returns a non-json String value, same string as was given in the request to create the entry
		def resourceRespText = resourceConn.getInputStream().text
		resourceRespText == someText
	}

	def "PUT /{context-id}/resource/{entry-id} should edit String-resource"() {
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert resourceConn.getInputStream().text == someText

		def newBody = 'new String set'

		when:
		// edit created resource
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, newBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		resourceConn2.getInputStream().text == newBody
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(resourceConn.getInputStream().text) == []

		when:
		// add minimal entry to the list
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, JsonOutput.toJson([minimalEntryId]))

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(resourceConn2.getInputStream().text) == [minimalEntryId]
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(resourceConn.getInputStream().text) == []

		when:
		// add non-existing entry to the list
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, JsonOutput.toJson(['some-random-entry-id']))

		then:
		editResourceConn.getResponseCode() == HTTP_BAD_REQUEST
		// fetch resource details again, should still be an empty list
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(resourceConn2.getInputStream().text) == []
	}

	def "PUT /{context-id}/resource/{entry-id} should edit name and password User-resource"() {
		given:
		// create a User entry
		def params = [graphtype: 'user']
		def requestResourceName = [name: 'Resource Test User name']
		def body = JsonOutput.toJson([resource: requestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.getInputStream().text)
		assert resourceJson['name'] == requestResourceName['name'].toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['customProperties'] == [:]

		// TODO: Verify this behaviour: if 'password' is set then no other properties (than 'password and 'name') are processed, due to logic for processing 'password' has a 'return' statement
		def requestBody = JsonOutput.toJson([
			name    : 'New name',
			language: 'PL',
			password: 'newPass123'
		])

		when:
		// edit user data
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.getInputStream().text)
		// Why the name is in the lower case, other than the data in the request
		resourceJson2['name'] == 'new name'
		resourceJson2['language'] == null    // Should be 'PL'
		resourceJson2['customProperties'] == [:]
	}

	def "PUT /{context-id}/resource/{entry-id} should not edit name if name is already in use"() {
		given:
		// create a User entry
		def params = [graphtype: 'user']
		def requestResourceName = [name: 'Resource Test User name 2']
		def body = JsonOutput.toJson([resource: requestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.getInputStream().text)
		assert resourceJson['name'] == requestResourceName['name'].toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['customProperties'] == [:]

		def requestBody = JsonOutput.toJson([
			name: 'New name'
		])

		when:
		// edit user data
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_BAD_REQUEST

		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.getInputStream().text)
		resourceJson2['name'] == requestResourceName['name'].toLowerCase()
		resourceJson2['language'] == null
		resourceJson2['customProperties'] == [:]
	}

	def "PUT /_principals/{entry-id} should add user to a group and the user should have the information in relations object"() {
		given:
		// create a User entry
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'UserPUT']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def groupRequestResourceName = [name: 'GroupPUT']
		def groupBody = JsonOutput.toJson([resource: groupRequestResourceName])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.getInputStream().text)['entryId'].toString()
		// fetch URI of created Group
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.getInputStream().text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([userEntryId])

		when:
		// add user to group
		def addUserToGroupConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, requestBody)

		then:
		addUserToGroupConn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceRespText = addUserToGroupConn.getInputStream().text
		addResourceRespText == ''
		// fetch Group details
		def groupResourceConn = EntryStoreClient.getRequest(groupResourceUri)
		assert groupResourceConn.getResponseCode() == HTTP_OK
		assert groupResourceConn.getContentType().contains('application/json')
		def groupResourceJson = JSON_PARSER.parseText(groupResourceConn.getInputStream().text)
		assert groupResourceJson['children'] instanceof List
		def groupMembers = groupResourceJson['children'].collect()
		groupMembers.size() == 1
		groupMembers[0]['name'] == 'userput'
		// fetch User details
		def userResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + "?includeAll")
		assert userResourceConn.getResponseCode() == HTTP_OK
		assert userResourceConn.getContentType().contains('application/json')
		def userResourceJson = JSON_PARSER.parseText(userResourceConn.getInputStream().text)
		assert userResourceJson['relations'] instanceof Map
		def relations = userResourceJson['relations']
		def userGroupRelation = relations[groupResourceUri] // Normally, a LazyMap should be populated now
		assert  userGroupRelation != null
	}

	def "PUT /_principals/{entry-id} should add user to 2 groups and the user should have the information in relations object"() {
		given:
		// create a User entry
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'UserPUTInto2groups']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def group1RequestResourceName = [name: 'GroupPUT1']
		def group1Body = JsonOutput.toJson([resource: group1RequestResourceName])
		def group1Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), group1Body)
		def group1EntryId = JSON_PARSER.parseText(group1Connection.getInputStream().text)['entryId'].toString()
		// fetch URI of created Group
		def group1EntryConn = EntryStoreClient.getRequest('/_principals/entry/' + group1EntryId)
		def group1EntryRespJson = JSON_PARSER.parseText(group1EntryConn.getInputStream().text)
		def group1EntryRespJsonKeys = (group1EntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def group1ResourceUri = group1EntryRespJsonKeys.find { it -> it.contains('resource') }

		// create a Group entry
		def group2RequestResourceName = [name: 'GroupPUT1']
		def group2Body = JsonOutput.toJson([resource: group2RequestResourceName])
		def group2Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), group2Body)
		def group2EntryId = JSON_PARSER.parseText(group2Connection.getInputStream().text)['entryId'].toString()
		// fetch URI of created Group
		def group2EntryConn = EntryStoreClient.getRequest('/_principals/entry/' + group2EntryId)
		def group2EntryRespJson = JSON_PARSER.parseText(group2EntryConn.getInputStream().text)
		def group2EntryRespJsonKeys = (group2EntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def group2ResourceUri = group2EntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([userEntryId])

		when:
		// add user to group
		def addUserToGroup1Conn = EntryStoreClient.putRequest('/_principals/resource/' + group1EntryId, requestBody)
		def addUserToGroup2Conn = EntryStoreClient.putRequest('/_principals/resource/' + group2EntryId, requestBody)

		then:
		addUserToGroup1Conn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceResp1Text = addUserToGroup1Conn.getInputStream().text
		addResourceResp1Text == ''
		addUserToGroup2Conn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceResp2Text = addUserToGroup2Conn.getInputStream().text
		addResourceResp2Text == ''
		// fetch Group details
		def group1ResourceConn = EntryStoreClient.getRequest(group1ResourceUri)
		assert group1ResourceConn.getResponseCode() == HTTP_OK
		assert group1ResourceConn.getContentType().contains('application/json')
		def group1ResourceJson = JSON_PARSER.parseText(group1ResourceConn.getInputStream().text)
		assert group1ResourceJson['children'] instanceof List
		def group1Members = group1ResourceJson['children'].collect()
		group1Members.size() == 1
		group1Members[0]['name'] == 'userputinto2groups'
		def group2ResourceConn = EntryStoreClient.getRequest(group1ResourceUri)
		assert group2ResourceConn.getResponseCode() == HTTP_OK
		assert group2ResourceConn.getContentType().contains('application/json')
		def group2ResourceJson = JSON_PARSER.parseText(group2ResourceConn.getInputStream().text)
		assert group2ResourceJson['children'] instanceof List
		def group2Members = group1ResourceJson['children'].collect()
		group2Members.size() == 1
		group2Members[0]['name'] == 'userputinto2groups'
		// fetch User details
		def userResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId + "?includeAll")
		assert userResourceConn.getResponseCode() == HTTP_OK
		assert userResourceConn.getContentType().contains('application/json')
		def userResourceJson = JSON_PARSER.parseText(userResourceConn.getInputStream().text)
		assert userResourceJson['relations'] instanceof Map
		def relations = userResourceJson['relations']
		def userGroup1Relation = relations[group1ResourceUri]
		assert  userGroup1Relation != null
		def userGroup2Relation = relations[group2ResourceUri]
		assert  userGroup2Relation != null
	}

	def "PUT /_principals/{entry-id} should add 2 users to a group and users should have the information in relations object"() {
		given:
		// create a User entry
		def userParams = [graphtype: 'user']
		def user1RequestResourceName = [name: 'UserPUT1']
		def user1Body = JsonOutput.toJson([resource: user1RequestResourceName])
		def user1Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), user1Body)
		def user1EntryId = JSON_PARSER.parseText(user1Connection.getInputStream().text)['entryId'].toString()
		def user2RequestResourceName = [name: 'UserPUT2']
		def user2Body = JsonOutput.toJson([resource: user2RequestResourceName])
		def user2Connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), user2Body)
		def user2EntryId = JSON_PARSER.parseText(user2Connection.getInputStream().text)['entryId'].toString()

		// create a Group entry
		def groupParams = [graphtype: 'group']
		def groupRequestResourceName = [name: 'GroupPUTboth']
		def groupBody = JsonOutput.toJson([resource: groupRequestResourceName])
		def groupConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(groupParams), groupBody)
		def groupEntryId = JSON_PARSER.parseText(groupConnection.getInputStream().text)['entryId'].toString()
		// fetch URI of created Group
		def groupEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + groupEntryId)
		def groupEntryRespJson = JSON_PARSER.parseText(groupEntryConn.getInputStream().text)
		def groupEntryRespJsonKeys = (groupEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def groupResourceUri = groupEntryRespJsonKeys.find { it -> it.contains('resource') }

		def requestBody = JsonOutput.toJson([user1EntryId, user2EntryId])

		when:
		// add user to group
		def addUsersToGroupConn = EntryStoreClient.putRequest('/_principals/resource/' + groupEntryId, requestBody)

		then:
		addUsersToGroupConn.getResponseCode() == HTTP_NO_CONTENT
		def addResourceRespText = addUsersToGroupConn.getInputStream().text
		addResourceRespText == ''
		// fetch Group details
		def groupResourceConn = EntryStoreClient.getRequest(groupResourceUri)
		assert groupResourceConn.getResponseCode() == HTTP_OK
		assert groupResourceConn.getContentType().contains('application/json')
		def groupResourceJson = JSON_PARSER.parseText(groupResourceConn.getInputStream().text)
		assert groupResourceJson['children'] instanceof List
		def groupMembers = groupResourceJson['children'].collect()
		groupMembers.size() == 2
		groupMembers[0]['name'] == 'userput1'
		groupMembers[1]['name'] == 'userput2'
		// fetch User details
		def user1ResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + user1EntryId + "?includeAll")
		assert user1ResourceConn.getResponseCode() == HTTP_OK
		assert user1ResourceConn.getContentType().contains('application/json')
		def user1ResourceJson = JSON_PARSER.parseText(user1ResourceConn.getInputStream().text)
		assert user1ResourceJson['relations'] instanceof Map
		def relations1 = user1ResourceJson['relations']
		def user1GroupRelation = relations1[groupResourceUri]
		assert user1GroupRelation != null
		// fetch User details
		def user2ResourceConn = EntryStoreClient.getRequest('/_principals/entry/' + user2EntryId + "?includeAll")
		assert user2ResourceConn.getResponseCode() == HTTP_OK
		assert user2ResourceConn.getContentType().contains('application/json')
		def user2ResourceJson = JSON_PARSER.parseText(user2ResourceConn.getInputStream().text)
		assert user2ResourceJson['relations'] instanceof Map
		def relations2 = user1ResourceJson['relations']
		def user2GroupRelation = relations2[groupResourceUri]
		assert user2GroupRelation != null
	}

	def "PUT /{context-id}/resource/{entry-id} should edit other User-resource properties"() {
		given:
		// create a User entry
		def params = [graphtype: 'user']
		def requestResourceName = [name: 'Resource Test User name 3']
		def body = JsonOutput.toJson([resource: requestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		def entryId = responseJson['entryId'].toString()
		assert entryId.length() > 0

		// fetch URI of created resource
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceJson = JSON_PARSER.parseText(resourceConn.getInputStream().text)
		assert resourceJson['name'] == requestResourceName['name'].toLowerCase()
		assert resourceJson['language'] == null
		assert resourceJson['disabled'] == null
		assert resourceJson['customProperties'] == [:]

		def requestBody = JsonOutput.toJson([
			name            : 'Newer name',
			language        : 'PL',
			disabled        : 'true',
			customProperties: [disablingReason: 'Untruthful']
		])

		when:
		// edit user data
		def editResourceConn = EntryStoreClient.putRequest(resourceUri, requestBody)

		then:
		editResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = editResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		def resourceJson2 = JSON_PARSER.parseText(resourceConn2.getInputStream().text)
		// Why the name is in the lower case, other than the data in the request
		resourceJson2['name'] == 'newer name'
		resourceJson2['language'] == 'PL'
		resourceJson2['disabled'] == true
		resourceJson2['customProperties'] == [disablingreason: 'Untruthful']
	}

	def "DELETE /{context-id}/resource/{entry-id} should remove resource"() {
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect { it.toString() }
		def resourceUri = entryRespJsonKeys.find { it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.getInputStream().text)
		assert resourceRespJson == [minimalEntryId]

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourceUri)

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = deleteResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		resourceConn2.getInputStream().text == '[]'
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		assert resourceConn.getInputStream().text == someText

		when:
		def deleteResourceConn = EntryStoreClient.deleteRequest(resourceUri)

		then:
		deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
		def editResourceRespText = deleteResourceConn.getInputStream().text
		editResourceRespText == ''
		// fetch resource details again
		def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
		// TODO: Should return 404 or empty body, however currently ResourceResource class has implemented delete only for List entry type,
		// hence calling delete in this test, did not modify anything, even tho the delete call response is a non-error
		resourceConn2.getResponseCode() == HTTP_OK
		resourceConn2.getContentType().contains('application/json')
		resourceConn2.getInputStream().text == someText
	}

	def "POST /{context-id}/resource/{entry-id} should move entry between lists"() {
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
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def sourceEntryKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def sourceResourceUri = sourceEntryKeys.find { it -> it.contains('resource') }
		// fetch source resource, should contain above created entry
		def sourceResourceConn = EntryStoreClient.getRequest(sourceResourceUri)
		assert sourceResourceConn.getResponseCode() == HTTP_OK
		assert sourceResourceConn.getContentType().contains('application/json')
		def sourceResourceRespJson = JSON_PARSER.parseText(sourceResourceConn.getInputStream().text)
		assert sourceResourceRespJson == [givenEntryId]

		// fetch URI of created resource for the TARGET entry
		def targetEntryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + targetEntryId)
		assert targetEntryConn.getResponseCode() == HTTP_OK
		def targetEntryRespJson = JSON_PARSER.parseText(targetEntryConn.getInputStream().text)
		assert targetEntryRespJson['info'] != null
		def targetEntryKeys = (targetEntryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def targetResourceUri = targetEntryKeys.find { it -> it.contains('resource') }
		// fetch target resource, should be empty
		def targetResourceConn = EntryStoreClient.getRequest(targetResourceUri)
		assert targetResourceConn.getResponseCode() == HTTP_OK
		def targetResourceRespJson = JSON_PARSER.parseText(targetResourceConn.getInputStream().text)
		assert targetResourceRespJson == []

		def postParams = [moveEntry: contextId + '/entry/' + givenEntryId,
						  fromList : sourceResourceUri]

		when:
		// move entry from source to target list
		def editResourceConn = EntryStoreClient.postRequest(targetResourceUri + convertMapToQueryParams(postParams))

		then:
		editResourceConn.getResponseCode() == HTTP_OK
		editResourceConn.getContentType().contains('application/json')
		def editResourceJson = JSON_PARSER.parseText(editResourceConn.getInputStream().text)
		// POST on target list to move an entry from source list, returns moved entryUri for some reason, instead of the new state of POST item (target list)
		editResourceJson['entryURI'] == EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + givenEntryId

		// fetch target resource again, should contain moved entry
		def targetResourceConn2 = EntryStoreClient.getRequest(targetResourceUri)
		assert targetResourceConn2.getResponseCode() == HTTP_OK
		def targetResourceRespJson2 = JSON_PARSER.parseText(targetResourceConn2.getInputStream().text)
		assert targetResourceRespJson2 == [givenEntryId]

		// fetch source resource again, should be empty now
		def sourceResourceConn2 = EntryStoreClient.getRequest(sourceResourceUri)
		assert sourceResourceConn2.getResponseCode() == HTTP_OK
		def sourceResourceRespJson2 = JSON_PARSER.parseText(sourceResourceConn2.getInputStream().text)
		assert sourceResourceRespJson2 == []
	}
}
