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
		// The body is the raw string as submitted, not JSON-quoted/escaped.
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

	def "POST /_principals?graphtype=user with malformed RDFJSON in info field should return 400 and not leave an orphan entry"() {
		given:
		// 'info' is a valid JSON string, but contains a subject URI ending in '.' which RDFJSON
		// rejects with RDFParseException. The failure fires AFTER the entry skeleton is created
		// and AFTER setResource succeeds — the orphan rollback must run, AND the client must see
		// a 400 (malformed user input is a client-side fault, not a server fault).
		def malformedInfo = '{"http://example.com/s.": {"http://example.com/p": [{"type": "uri", "value": "http://example.com/o"}]}}'
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: [name: 'mdRollbackUser'], info: malformedInfo])

		def beforeConn = EntryStoreClient.getRequest('/_principals')
		assert beforeConn.getResponseCode() == HTTP_OK
		def entriesBefore = (JSON_PARSER.parseText(beforeConn.inputStream.text) as List).toSet()

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		def responseJson = JSON_PARSER.parseText(connection.errorStream.text)
		responseJson['error'].toString().contains('Cannot create an entry with provided JSON/RDF')

		// Orphan rollback must still run for the RDFParseException branch — entry set unchanged.
		def afterConn = EntryStoreClient.getRequest('/_principals')
		afterConn.getResponseCode() == HTTP_OK
		def entriesAfter = (JSON_PARSER.parseText(afterConn.inputStream.text) as List).toSet()
		(entriesAfter - entriesBefore).isEmpty()
		(entriesBefore - entriesAfter).isEmpty()
	}

	def "POST /_principals?graphtype=user with malformed JSON in metadata field should create the entry with empty local metadata"() {
		given:
		// Unlike a malformed 'resource' (Jackson -> 400) or a valid-JSON-but-bad-RDF 'info'
		// (RDFParseException -> 400), a 'metadata' field whose value is syntactically unparseable JSON
		// makes EntryService.setLocalMetadataGraph's `new org.json.JSONObject(...)` throw
		// org.json.JSONException, which is caught-and-warned (matching its setCachedMetadataGraph /
		// setEntryGraph siblings). The entry is still created, with no local metadata. Regression guard
		// for ENTRYSTORE-1068: the catch was previously jakarta.json.JsonException, which never matched
		// org.json.JSONException, so this path used to return 500 instead of 201.
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: [name: 'malformedMetadataUser'], metadata: '{not-json'])

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then: 'the entry is created (201), not rejected with a 500'
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def entryId = JSON_PARSER.parseText(connection.inputStream.text)['entryId'].toString()
		entryId.length() > 0

		and: 'the malformed metadata was silently dropped — the local metadata graph is empty'
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId + '?includeAll')
		entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		entryRespJson['metadata'] != null
		entryRespJson['metadata'] as Map == [:]
	}

	def "POST /_principals?graphtype=user with malformed JSON in info field should create the entry with the default entry info"() {
		given:
		// Same swallow contract as the malformed-'metadata' case above, but through setEntryGraph:
		// a syntactically unparseable 'info' value throws org.json.JSONException, which is
		// caught-and-logged, so the entry keeps its system-generated info graph. Guards against a
		// future revert of the catch to jakarta.json.JsonException (never thrown there), which would
		// turn this request into a 500 (ENTRYSTORE-1068).
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: [name: 'malformedInfoUser'], info: '{not-json'])

		when:
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(params), body)

		then: 'the entry is created (201), not rejected with a 500'
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def entryId = JSON_PARSER.parseText(connection.inputStream.text)['entryId'].toString()
		entryId.length() > 0

		and: 'the malformed info was silently dropped — the system-generated entry info is intact'
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = JSON_PARSER.parseText(entryConn.inputStream.text)
		def entryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + entryId
		entryRespJson['info'][entryUri] != null
		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
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
