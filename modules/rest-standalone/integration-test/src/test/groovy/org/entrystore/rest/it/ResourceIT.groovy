package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

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
			name: 'New name',
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
		resourceJson2['language'] == null 	// Should be 'PL'
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
			name: 'Newer name',
			language: 'PL',
			disabled: 'true',
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
		def sourceEntryKeys = (entryRespJson['info'] as Map).keySet().collect( it -> it.toString())
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
		def targetEntryKeys = (targetEntryRespJson['info'] as Map).keySet().collect( it -> it.toString())
		def targetResourceUri = targetEntryKeys.find { it -> it.contains('resource') }
		// fetch target resource, should be empty
		def targetResourceConn = EntryStoreClient.getRequest(targetResourceUri)
		assert targetResourceConn.getResponseCode() == HTTP_OK
		def targetResourceRespJson = JSON_PARSER.parseText(targetResourceConn.getInputStream().text)
		assert targetResourceRespJson == []

		def postParams = [moveEntry: contextId + '/entry/' + givenEntryId,
					  fromList: sourceResourceUri]

		when:
		// move entry from source to target list
		def editResourceConn = EntryStoreClient.postRequest(targetResourceUri + convertMapToQueryParams(postParams))

		then:
		editResourceConn.getResponseCode() == HTTP_OK
		editResourceConn.getContentType().contains('application/json')
		def editResourceJson = JSON_PARSER.parseText(editResourceConn.getInputStream().text)
		// POST on target list to move an entry from source list, returns moved entryUri for some reason, instead of the new state of POST item (target list)
		editResourceJson['entryURI'] == 'http://localhost:8181/store/' + contextId + '/entry/' + givenEntryId

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
