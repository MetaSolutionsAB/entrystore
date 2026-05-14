package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class LocalEntryIT extends BaseSpec {

	def static contextId = '20'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "POST /{context-id}?graphtype=string as guest should respond with Unauthorized 401"() {
		given:
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = JsonOutput.toJson([resource: someText])

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), body, '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}?graphtype=string as non-admin user should respond with Forbidden"() {
		given:
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = JsonOutput.toJson([resource: someText])

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), body, 'user')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /{context-id}?graphtype=string as admin should create by default a local entry of type String"() {
		given:
		def someText = 'Some text'
		def params = [id: 'test-string-entry-id', graphtype: 'string']
		def body = [resource: someText]

		when:
		def entryId = getOrCreateEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		// Entry type not being set automatically from graphType=String, however it is set under /resource/
//		entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE] != null
//		def entryTypes = entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE].collect()
//		entryTypes.size() == 1
//		entryTypes[0]['type'] == 'uri'
//		entryTypes[0]['value'] == NameSpaceConst.TERM_STRING

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_STRING

		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('text/plain')
		// Response says content-type is JSON, but it returns a non-json String value, same string as was given in the request to create the entry
		def resourceRespText = resourceConn.inputStream.text
		resourceRespText == someText
	}

	// This tests is for a bug found using js.test - entry's "resource" field is usually a json, but for String entry it is just a String,
	// for which when we call @JsonRawValue (needed for json values) turns "resource" into invalid json, e.g. {"resource": Some text}, which should be {"resource": "Some text"}
	def "GET /{context-id}/entry/{entry-id}?includeAll as admin for a string entry should return correct entry resource string"() {
		given:
		def entryId = 'test-string-entry-id'
		def someText = 'Some text'
		def params = [id: entryId, graphtype: 'string']
		def body = [resource: someText]
		getOrCreateEntry(contextId, params, body)

		when:
		def conn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(conn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_STRING
	}

	def "POST /{context-id}?graphtype=list should create by default a local entry of type List"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		def someText = [givenEntryId, 'non-existing-id']
		def params = [graphtype: 'list']
		def body = [resource: someText]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll')
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		// Entry type for some reason set under /{context-id}/resource/{entry-id}, and not under /{context-id}/entry/{entry-id}
		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/' + contextId + '/resource/')

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_LIST

		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson == [givenEntryId]
	}

	def "POST /{context-id}?graphtype=user should not create a user entry inside regular context"() {
		given:
		def resourceName = [name: 'Test User name']
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: resourceName])

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.errorStream.text)
		responseJson['entryId'] == null
		responseJson['error'] != null
		responseJson['error'].toString().contains('Regular context only support Lists, ResultLists and None as BuiltinTypes')
	}

	def "POST /_principals?graphtype=user with malformed JSON resource should return 400 and not leave an orphan entry"() {
		given:
		// Outer body is valid JSON, but the 'resource' field's value is a string of unparseable JSON.
		// Spring deserialises the outer envelope, then EntryService.setResource calls objectMapper.readValue
		// on the inner string and throws JsonProcessingException. The just-created entry skeleton must be
		// rolled back before re-throwing as BadRequestException — no orphan can remain on a 400 response.
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: '{not-json'])

		// Snapshot the principal-entry set before the failed POST so we can verify no orphan was left.
		// Compare as a Set (not just count) so a future paginated listing or reordered output won't mask
		// the case where a rollback regression leaves a new entry behind while removing an unrelated one.
		def beforeConn = EntryStoreClient.getRequest('/_principals')
		assert beforeConn.getResponseCode() == HTTP_OK
		def entriesBefore = (JSON_PARSER.parseText(beforeConn.inputStream.text) as List).toSet()

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.errorStream.text)
		responseJson['error'].toString().contains('Cannot create an entry with provided JSON')

		// Orphan rollback: the principal-entry set must be unchanged after the failed POST — no new
		// orphan added, AND no unrelated entry accidentally removed (e.g. by a buggy rollback that
		// computes the wrong URI). Both directions must be empty for the rollback to be correct.
		def afterConn = EntryStoreClient.getRequest('/_principals')
		afterConn.getResponseCode() == HTTP_OK
		def entriesAfter = (JSON_PARSER.parseText(afterConn.inputStream.text) as List).toSet()
		(entriesAfter - entriesBefore).isEmpty()
		(entriesBefore - entriesAfter).isEmpty()
	}

	def "POST /_principals?graphtype=user with malformed RDFJSON in info field should not leave an orphan entry"() {
		given:
		// 'info' is a valid JSON string, but contains a subject URI ending in '.' which RDFJSON
		// rejects with RDFParseException (a RuntimeException). The failure fires AFTER the entry
		// skeleton is created and AFTER setResource succeeds — i.e. a post-creation RuntimeException
		// must still trigger the orphan rollback, not just the JsonProcessingException path.
		def malformedInfo = '{"http://example.com/s.": {"http://example.com/p": [{"type": "uri", "value": "http://example.com/o"}]}}'
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: [name: 'mdRollbackUser'], info: malformedInfo])

		def beforeConn = EntryStoreClient.getRequest('/_principals')
		assert beforeConn.getResponseCode() == HTTP_OK
		def entriesBefore = (JSON_PARSER.parseText(beforeConn.inputStream.text) as List).toSet()

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then:
		// RDFParseException propagates as the original RuntimeException type via the broadened
		// catch — AppExceptionHandler.handleGenericException maps it to a sanitised 500.
		connection.getResponseCode() == HTTP_INTERNAL_ERROR

		// Orphan rollback must run for the RuntimeException branch too — entry set must be unchanged.
		def afterConn = EntryStoreClient.getRequest('/_principals')
		afterConn.getResponseCode() == HTTP_OK
		def entriesAfter = (JSON_PARSER.parseText(afterConn.inputStream.text) as List).toSet()
		(entriesAfter - entriesBefore).isEmpty()
		(entriesBefore - entriesAfter).isEmpty()
	}

	def "POST /_principals?graphtype=user should create a local entry of type User"() {
		given:
		def requestResourceName = [name: 'Test User name']
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: requestResourceName])

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + entryId
		entryRespJson['info'][entryUri] != null
		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/_principals/resource/')

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_USER

		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson != null
		resourceRespJson['customProperties'] == [:]
		resourceRespJson['name'] == requestResourceName['name'].toLowerCase()
	}

	def "POST /_principals?graphtype=group should create a local entry of type Group"() {
		given:
		def requestResourceName = [name: 'Test Group']
		def params = [graphtype: 'group']
		def body = JsonOutput.toJson([resource: requestResourceName])

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + entryId
		entryRespJson['info'][entryUri] != null
		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl + '/_principals/resource/')

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_GROUP

		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson != null
		resourceRespJson['children'] == []
		resourceRespJson['name'] == requestResourceName['name'].toLowerCase()
	}

	def "POST /_contexts?graphtype=context should create a local entry of type Context"() {
		given:
		def requestResourceName = [name: 'Test Context']
		def params = [graphtype: 'context']
		def body = JsonOutput.toJson([resource: requestResourceName])

		when:
		def connection = EntryStoreClient.postRequest('/_contexts' + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.inputStream.text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_contexts/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['entryId'] == entryId
		entryRespJson['name'] == requestResourceName['name']
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_contexts/entry/' + entryId
		entryRespJson['info'][entryUri] != null
		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] != null
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		entryMetadata.size() == 1
		entryMetadata[0]['type'] == 'uri'
		entryMetadata[0]['value'] != null
		def entryMetadataUrl = entryMetadata[0]['value'].toString()
		entryMetadataUrl.startsWith(EntryStoreClient.baseUrl + '/_contexts/metadata/')

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] != null
		def createdResourceUri = entryResources[0]['value'].toString()
		createdResourceUri.startsWith(EntryStoreClient.baseUrl)

		entryRespJson['info'][createdResourceUri] != null
		entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE] != null
		def resourceTypes = entryRespJson['info'][createdResourceUri][NameSpaceConst.RDF_TYPE].collect()
		resourceTypes.size() == 1
		resourceTypes[0]['type'] == 'uri'
		resourceTypes[0]['value'] == NameSpaceConst.TERM_CONTEXT

		// fetch created resource
		def resourceConn = EntryStoreClient.getRequest(createdResourceUri)
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/json')
		def resourceRespJson = JSON_PARSER.parseText(resourceConn.inputStream.text)
		resourceRespJson != null

		// fetch created metadata
		def metadataConn = EntryStoreClient.getRequest(entryMetadataUrl)
		metadataConn.getResponseCode() == HTTP_OK
		metadataConn.getContentType().contains('application/json')
		def metadataRespJson = JSON_PARSER.parseText(metadataConn.inputStream.text)
		metadataRespJson != null
		(metadataRespJson as Map).keySet().size() == 0
	}
}
