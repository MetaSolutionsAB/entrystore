package org.entrystore.rest.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class EntryIT extends BaseSpec {

	def "POST /{context-id} should create a new link entry"() {
		given:
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def resourceUrl = 'https://bbc.co.uk'
		def params = [entrytype: 'link', resource: resourceUrl]
		def body = JsonOutput.toJson([:])

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

	def "POST /{context-id} should create a new link-reference entry"() {
		given:
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def resourceUrl = 'https://bbc.co.uk'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'linkreference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def body = JsonOutput.toJson([:])

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
		entryTypes[0]['value'] == NameSpaceConst.TERM_LINK_REFERENCE

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] == resourceUrl

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_EXTERNAL_METADATA] != null
		def entryExtMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_EXTERNAL_METADATA].collect()
		entryExtMetadata.size() == 1
		entryExtMetadata[0]['type'] == 'uri'
		entryExtMetadata[0]['value'] == metadataUrl

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA] != null
		def entryCachedExtMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA].collect()
		entryCachedExtMetadata.size() == 1
		entryCachedExtMetadata[0]['type'] == 'uri'
		entryCachedExtMetadata[0]['value'] != null
		entryCachedExtMetadata[0]['value'].toString().contains('/store/' + contextId + '/cached-external-metadata/')
	}

	def "POST /{context-id} should create a new reference entry"() {
		given:
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def resourceUrl = 'https://bbc.co.uk'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'reference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def body = JsonOutput.toJson([:])

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
		entryTypes[0]['value'] == NameSpaceConst.TERM_REFERENCE

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE] != null
		def entryResources = entryRespJson['info'][entryUri][NameSpaceConst.TERM_RESOURCE].collect()
		entryResources.size() == 1
		entryResources[0]['type'] == 'uri'
		entryResources[0]['value'] == resourceUrl

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_EXTERNAL_METADATA] != null
		def entryExtMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_EXTERNAL_METADATA].collect()
		entryExtMetadata.size() == 1
		entryExtMetadata[0]['type'] == 'uri'
		entryExtMetadata[0]['value'] == metadataUrl

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA] != null
		def entryCachedExtMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA].collect()
		entryCachedExtMetadata.size() == 1
		entryCachedExtMetadata[0]['type'] == 'uri'
		entryCachedExtMetadata[0]['value'] != null
		entryCachedExtMetadata[0]['value'].toString().contains('/store/' + contextId + '/cached-external-metadata/')
	}

	def "POST /{context-id} should create a new entry with specific id"() {
		given:
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def resourceUrl = 'https://bbc.co.uk'
		def entryId = 'myEntryId'
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		def body = JsonOutput.toJson([:])

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
		def contextId = '10'
		getOrCreateContext([contextId: contextId])
		def resourceUrl = 'https://bbc.co.uk'
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = JsonOutput.toJson([metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 "type" : "literal",
												 "value": "Cool entry"
											 ]],
		]]])

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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] != null
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		entryMetadata.size() == 1
		entryMetadata[0]['type'] == 'uri'
		entryMetadata[0]['value'] != null
		entryMetadata[0]['value'].toString().contains('/store/' + contextId + '/metadata/')
		def storedMetadataUrl = entryMetadata[0]['value'].toString().substring(EntryStoreClient.baseUrl.length())

		// fetch entry metadata
		def entryMetaConn = client.getRequest(storedMetadataUrl, 'admin')
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = (new JsonSlurper()).parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Cool entry'
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
