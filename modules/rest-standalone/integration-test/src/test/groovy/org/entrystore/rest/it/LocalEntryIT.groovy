package org.entrystore.rest.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class LocalEntryIT extends BaseSpec {

	def contextId = '20'

	def "POST /{context-id}?graphtype=string should create by default a local entry of type String"() {
		given:
		getOrCreateContext([contextId: contextId])
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
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
		resourceConn.getContentType().contains('application/json')
		// Response says content-type is JSON, but it returns a non-json String value, same string as was given in the request to create the entry
		def resourceRespText = resourceConn.getInputStream().text
		resourceRespText == someText
	}

	def "POST /{context-id}?graphtype=user should not create a user entry inside regular context"() {
		given:
		getOrCreateContext([contextId: contextId])
		def resourceName = [name: 'Test User name']
		def params = [graphtype: 'user']
		def body = JsonOutput.toJson([resource: resourceName])

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getErrorStream().text)
		responseJson['entryId'] == null
		responseJson['error'] != null
		responseJson['error'].toString().contains('Regular context only support Lists, ResultLists and None as BuiltinTypes')
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
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		// Entry type not being under /entry/{entry-id}, but under /resource/{resource-id}

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
		def resourceRespJson = (new JsonSlurper()).parseText(resourceConn.getInputStream().text)
		resourceRespJson != null
		resourceRespJson['customProperties'] == [:]
		resourceRespJson['name'] == requestResourceName['name'].toLowerCase() // Why the returned username is in lower case, different than in request?
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
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_principals/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		// Entry type not being under /entry/{entry-id}, but under /resource/{resource-id}

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
		def resourceRespJson = (new JsonSlurper()).parseText(resourceConn.getInputStream().text)
		resourceRespJson != null
		resourceRespJson['children'] == []
		resourceRespJson['name'] == requestResourceName['name'].toLowerCase() // Why the returned group name is in lower case, different than in the request?
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
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/_contexts/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['name'] == requestResourceName['name']
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/_contexts/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		// Entry type not being under /entry/{entry-id}, but under /resource/{resource-id}

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
		def resourceRespJson = (new JsonSlurper()).parseText(resourceConn.getInputStream().text)
		resourceRespJson != null
		// resourceRespJson is empty - GET (createdResourceUri) returns empty list

		// fetch created metadata
		def metadataConn = EntryStoreClient.getRequest(entryMetadataUrl)
		metadataConn.getResponseCode() == HTTP_OK
		metadataConn.getContentType().contains('application/json')
		def metadataRespJson = (new JsonSlurper()).parseText(metadataConn.getInputStream().text)
		metadataRespJson != null
		(metadataRespJson as Map).keySet().size() == 0
		// metadataRespJson is empty - GET (entryMetadataUrl) returns empty map
	}
}
