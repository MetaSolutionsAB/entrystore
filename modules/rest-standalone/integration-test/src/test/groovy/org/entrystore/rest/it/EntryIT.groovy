package org.entrystore.rest.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class EntryIT extends BaseSpec {

	def "POST /{context-id} should create a new link entry"() {
		given:
		def resourceUrl = 'https://bbc.co.uk'
		def contextId = '10'
		def groupId = createContext([name: 'someName10', contextId: contextId])
		def body = JsonOutput.toJson([:])
		def params = [entrytype: 'link', resource: resourceUrl]

		when:
		def connection = client.postRequest('/' + contextId + convertMapToQueryParams(params), body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch entries under the context
		// extract to separate test?
		def contextConn = client.getRequest('/' + contextId, 'admin')
		contextConn.getResponseCode() == HTTP_OK
		contextConn.getContentType().contains('application/json')
		def contextRespJson = (new JsonSlurper()).parseText(contextConn.getInputStream().text).collect()
		contextRespJson.size() == 1
		contextRespJson[0] != null
		!contextRespJson[0].toString().isEmpty()
		def contextEntryId = contextRespJson[0]
		contextEntryId == entryId

		// fetch created entry
		def entryConn = client.getRequest('/' + contextId + '/entry/' + entryId, 'admin')
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE] != null
		def entryTypes = entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE].collect()
		entryTypes.size() == 1
		entryTypes[0]['type'] == 'uri'
		entryTypes[0]['value'] == NameSpaceConst.TERM_LINK

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] == resourceUrl
	}

	def "POST /{context-id} should create a new entry with specific id"() {
		given:
		def resourceUrl = 'https://bbc.co.uk'
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def body = JsonOutput.toJson([:])
		def entryId = 'myEntryId'
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]

		when:
		def connection = client.postRequest('/' + contextId + convertMapToQueryParams(params), body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		responseJson['entryId'] == entryId

		// fetch created entry
		def entryConn = client.getRequest('/' + contextId + '/entry/' + entryId, 'admin')
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE] != null
		def entryTypes = entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE].collect()
		entryTypes.size() == 1
		entryTypes[0]['type'] == 'uri'
		entryTypes[0]['value'] == NameSpaceConst.TERM_LINK

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] == resourceUrl
	}

	def "POST /{context-id} should create a new entry with metadata"() {
		given:
		def resourceUrl = 'https://bbc.co.uk'
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def body = JsonOutput.toJson([metadata: ['dcterms:title': 'Some Title']])
		def params = [entrytype: 'link', resource: resourceUrl]

		when:
		def connection = client.postRequest('/' + contextId + convertMapToQueryParams(params), body, 'admin')

		then:
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = (new JsonSlurper()).parseText(connection.getInputStream().text)
		responseJson['entryId'] != null
		responseJson['entryId'].toString().length() > 0
		def entryId = responseJson['entryId'].toString()

		// fetch created entry
		def entryConn = client.getRequest('/' + contextId + '/entry/' + entryId, 'admin')
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		entryRespJson['entryId'] == entryId
		entryRespJson['info'] != null
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		entryRespJson['info'][entryUri] != null

		entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE] != null
		def entryTypes = entryRespJson['info'][entryUri][NameSpaceConst.RDF_TYPE].collect()
		entryTypes.size() == 1
		entryTypes[0]['type'] == 'uri'
		entryTypes[0]['value'] == NameSpaceConst.TERM_LINK

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] == resourceUrl

		// TODO: add metadata check
	}

	def "POST /{context-id} should throw unauthorized for non-admin user"() {
		given:
		def resourceUrl = 'https://bbc.co.uk'
		def contextId = '11'
		def groupId = createContext([name: 'someName11', contextId: contextId])
		def body = JsonOutput.toJson([:])
		def params = [entrytype: 'link', resource: resourceUrl]

		when:
		def connection = client.postRequest('/' + contextId + convertMapToQueryParams(params), body)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}
}
