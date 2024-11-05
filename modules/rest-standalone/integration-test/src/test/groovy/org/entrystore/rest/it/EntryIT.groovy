package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.time.Year

import static java.net.HttpURLConnection.*
import static java.nio.charset.StandardCharsets.UTF_8

class EntryIT extends BaseSpec {

	def static contextId = '10'
	def resourceUrl = 'https://bbc.co.uk'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "POST /{context-id}?entrytype=link without metadata, should create a new link entry with empty metadata"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]

		when:
		def entryId = createEntry(contextId, params)

		then:
		entryId.length() > 0

		// fetch entries under the context
		// extract to separate test?
		def contextConn = EntryStoreClient.getRequest('/' + contextId)
		contextConn.getResponseCode() == HTTP_OK
		contextConn.getContentType().contains('application/json')
		def contextRespJson = JSON_PARSER.parseText(contextConn.getInputStream().text).collect()
		contextRespJson.size() == 1
		contextRespJson[0] != null
		!contextRespJson[0].toString().isEmpty()
		def contextEntryId = contextRespJson[0]
		contextEntryId == entryId

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		// metadata should be empty
		(entryMetaRespJson as Map).keySet().size() == 0
	}

	def "POST /{context-id}?entrytype=link with metadata in the body, should create a new link entry with local metadata"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'Cool entry'
											 ]],
		]]]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Cool entry'
	}

	def "POST /{context-id}?entrytype=linkreference without metadata, should create a new link-reference entry with empty local metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'linkreference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]

		when:
		def entryId = createEntry(contextId, params)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] != null
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		entryMetadata.size() == 1
		entryMetadata[0]['type'] == 'uri'
		entryMetadata[0]['value'] != null
		entryMetadata[0]['value'].toString().contains('/store/' + contextId + '/metadata/')
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		// fetch local metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 0

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
		def storedExternalMetadataUrl = entryCachedExtMetadata[0]['value'].toString()

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "POST /{context-id}?entrytype=linkreference with metadata, should create a new link-reference entry with local metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'linkreference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'Cool entry 2'
											 ]],
		]]]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] != null
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		entryMetadata.size() == 1
		entryMetadata[0]['type'] == 'uri'
		entryMetadata[0]['value'] != null
		entryMetadata[0]['value'].toString().contains('/store/' + contextId + '/metadata/')
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		// fetch local metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Cool entry 2'

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
		def storedExternalMetadataUrl = entryCachedExtMetadata[0]['value'].toString()

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "POST /{context-id}?entrytype=reference without metadata, should create a new reference entry without local metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'reference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]

		when:
		def entryId = createEntry(contextId, params)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] == null

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
		def storedExternalMetadataUrl = entryCachedExtMetadata[0]['value'].toString()

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "POST /{context-id}?entrytype=reference with metadata, should create a new reference entry *without* local metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'reference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'Cool entry 3'
											 ]],
		]]]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] == null

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
		def storedExternalMetadataUrl = entryCachedExtMetadata[0]['value'].toString()

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "POST /{context-id}?entrytype=link&id=x should create a new link entry with specific id"() {
		given:
		def requestedEntryId = 'myEntryId'
		def params = [entrytype: 'link', resource: resourceUrl, id: requestedEntryId]

		when:
		def entryId = createEntry(contextId, params)

		then:
		entryId.length() > 0
		entryId == requestedEntryId

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

	def "POST /{context-id}?entrytype=link&id=x should not create a new entry if entry with such id already exists"() {
		given:
		def entryId = 'myEntryId'
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		getOrCreateEntry(contextId, params)

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params))

		then:
		connection.getResponseCode() == HTTP_CONFLICT
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'].toString().contains('Entry with provided ID already exists')
	}

	def "POST /{context-id}?entrytype=link should throw unauthorized for non-admin user"() {
		given:
		getOrCreateContext([contextId: contextId])
		def params = [entrytype: 'link', resource: resourceUrl]

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), '', null)

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}?entrytype=link&template=otherEntry with metadata in the body, should create a new link entry with local metadata combined with MD from otherEntry"() {
		given:
		def otherEntryParams = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def otherEntryBody = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [[
												   type : 'literal',
												   value: 'Other Entry Title'
											   ]],
			(NameSpaceConst.RDF_TYPE)       : [[
												   type : 'uri',
												   value: NameSpaceConst.NS_DCAT_DATASET
											   ]],
			(NameSpaceConst.DC_TERM_CREATOR): [[
												   type : 'literal',
												   value: 'Other Entry Creator'
											   ]]
		]]]
		def otherEntryId = createEntry(contextId, otherEntryParams, otherEntryBody)
		assert otherEntryId.length() > 0

		def params = [entrytype: 'link', resource: resourceUrl, template: EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + otherEntryId]
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)    : [[
													 type : 'literal',
													 value: 'New Entry Title' // should not get overridden
												 ]],
			(NameSpaceConst.RDF_TYPE)         : [[
													 type : 'uri',
													 value: NameSpaceConst.TERM_NAMED_RESOURCE  // should get overridden
												 ]],
			(NameSpaceConst.DC_TERM_PUBLISHER): [[
													 type : 'literal',
													 value: 'New Entry Publisher' // should not get overridden
												 ]]
		]]]

		when:
		def entryId = createEntry(contextId, params, body)

		then:
		entryId.length() > 0

		// fetch created entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		def metadataKeys = (entryMetaRespJson as Map).keySet()
		metadataKeys.size() == 2

		def firstMetadataUri = metadataKeys[0].toString()
		(entryMetaRespJson[firstMetadataUri] as Map).keySet().size() == 2 // only 2 metadata should be copied from Other entry - without dc:title
		entryMetaRespJson[firstMetadataUri][NameSpaceConst.DC_TERM_TITLE] == null
		entryMetaRespJson[firstMetadataUri][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcCreators = entryMetaRespJson[firstMetadataUri][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcCreators.size() == 1
		dcCreators[0]['type'] == 'literal'
		dcCreators[0]['value'] == 'Other Entry Creator'
		entryMetaRespJson[firstMetadataUri][NameSpaceConst.RDF_TYPE] != null
		def rdfTypes = entryMetaRespJson[firstMetadataUri][NameSpaceConst.RDF_TYPE].collect()
		rdfTypes.size() == 1
		rdfTypes[0]['type'] == 'uri'
		rdfTypes[0]['value'] == NameSpaceConst.NS_DCAT_DATASET

		def secondMetadataUri = metadataKeys[1].toString()
		(entryMetaRespJson[secondMetadataUri] as Map).keySet().size() == 3 // All 3 metadata should be copied from New entry
		entryMetaRespJson[secondMetadataUri][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[secondMetadataUri][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'New Entry Title'
		entryMetaRespJson[secondMetadataUri][NameSpaceConst.DC_TERM_CREATOR] == null
		entryMetaRespJson[secondMetadataUri][NameSpaceConst.DC_TERM_PUBLISHER] != null
		def dcPublishers = entryMetaRespJson[secondMetadataUri][NameSpaceConst.DC_TERM_PUBLISHER].collect()
		dcPublishers.size() == 1
		dcPublishers[0]['type'] == 'literal'
		dcPublishers[0]['value'] == 'New Entry Publisher'
		entryMetaRespJson[secondMetadataUri][NameSpaceConst.RDF_TYPE] != null
		def rdf2Types = entryMetaRespJson[secondMetadataUri][NameSpaceConst.RDF_TYPE].collect()
		rdf2Types.size() == 1
		rdf2Types[0]['type'] == 'uri'
		rdf2Types[0]['value'] == NameSpaceConst.TERM_NAMED_RESOURCE
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll for a link entry, should return extra information about the entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'Cool entry 20'
											 ]],
		]]]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		entryRespJson['metadata'] != null
		(entryRespJson['metadata'] as Map).keySet().size() == 1
		def entryMDResourceUrl = (entryRespJson['metadata'] as Map).keySet()[0].toString()
		entryRespJson['metadata'][entryMDResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def entryMDdcTitles = entryRespJson['metadata'][entryMDResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		entryMDdcTitles.size() == 1
		entryMDdcTitles[0]['type'] == 'literal'
		entryMDdcTitles[0]['value'] == 'Cool entry 20'

		entryRespJson['rights'] != null
		entryRespJson['relations'] != null

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Cool entry 20'
	}

	def "GET /{context-id}/entry/{entry-id}?includeAll for a linkreference entry, should return extra information about the entry"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/json')
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
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

		entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA] != null
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		entryMetadata.size() == 1
		entryMetadata[0]['type'] == 'uri'
		entryMetadata[0]['value'] != null
		entryMetadata[0]['value'].toString().contains('/store/' + contextId + '/metadata/')
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		entryRespJson['metadata'] != null
		(entryRespJson['metadata'] as Map).keySet().size() == 1
		def entryMDResourceUrl = (entryRespJson['metadata'] as Map).keySet()[0].toString()
		entryRespJson['metadata'][entryMDResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def entryMDdcTitles = entryRespJson['metadata'][entryMDResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		entryMDdcTitles.size() == 1
		entryMDdcTitles[0]['type'] == 'literal'
		entryMDdcTitles[0]['value'] == 'local metadata title'

		entryRespJson['cached-external-metadata'] != null
		entryRespJson['cached-external-metadata'] as Map == [:]

		entryRespJson['rights'] != null
		entryRespJson['rights'].collect() == ['administer']
		entryRespJson['relations'] != null
		entryRespJson['relations'] as Map == [:]

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'local metadata title'

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
		def storedExternalMetadataUrl = entryCachedExtMetadata[0]['value'].toString()

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "GET /{context-id}/entry/{entry-id} in rdf+xml format for a linkreference entry, should return information about the entry in RDF+XML format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		// TODO: bug? below GET (a request for an entry with "application/ld+json" or "rdf+json" format in Accept header) throws 500
//		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'admin', 'application/ld+json') // Exception: Hierarchical view is not supported by this JSON-LD processor
//		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + '?includeAll', 'admin', 'application/rdf+json') // Exception: NullPointer

		// TODO: bug? below GET (a request for an entry with param "rdfFormat=application/ld+json" and empty Accept header) returns RDF+XML (the default type, and rdfFormat param value is ignored)
//		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId + convertMapToQueryParams([rdfFormat: 'application/ld+json']), 'admin', null)

		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'application/rdf+xml')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/rdf+xml')
		def entryRespXml = new XmlParser(false, false).parseText(entryConn.getInputStream().text)
		entryRespXml['es:LinkReference'].size() == 1
		def entryLinkRefXml = entryRespXml['es:LinkReference'][0] as Node
		// es:LinkReference has one attribute and 9 children
		entryLinkRefXml.attributes().size() == 1
		entryLinkRefXml['@rdf:about'] == 'http://localhost:8181/store/10/entry/' + entryId
		entryLinkRefXml.value().size() == 9

		//   es:resource child should have: 1 attr, 0 children
		entryLinkRefXml['es:resource'].size() == 1
		def entryResourceXml = entryLinkRefXml['es:resource'][0] as Node
		entryResourceXml.attributes().size() == 1
		entryResourceXml['@rdf:resource'] == resourceUrl
		entryResourceXml.value().size() == 0

		//   dcterms:created child should have: 1 attr, 1 val
		entryLinkRefXml['dcterms:created'].size() == 1
		def entryCreatedXml = entryLinkRefXml['dcterms:created'][0] as Node
		entryCreatedXml.attributes().size() == 1
		entryCreatedXml['@rdf:datatype'] == 'http://www.w3.org/2001/XMLSchema#dateTime'
		entryCreatedXml.text().length() > 10
		entryCreatedXml.text().contains(Year.now().getValue().toString())

		//   es:metadata child should have: 1 attr, no val
		entryLinkRefXml['es:metadata'].size() == 1
		def entryMetadataXml = entryLinkRefXml['es:metadata'][0] as Node
		entryMetadataXml.attributes().size() == 1
		def storedMetadataUrl = entryMetadataXml['@rdf:resource'].toString()
		storedMetadataUrl.contains('/store/' + contextId + '/metadata/')
		entryMetadataXml.text() == ''

		//   es:externalMetadata child should have: 1 attr, no val
		entryLinkRefXml['es:externalMetadata'].size() == 1
		def entryExternalMetadataXml = entryLinkRefXml['es:externalMetadata'][0] as Node
		entryExternalMetadataXml.attributes().size() == 1
		entryExternalMetadataXml['@rdf:resource'] == metadataUrl
		entryExternalMetadataXml.text() == ''

		//   es:cachedExternalMetadata child should have: 1 attr, no val
		entryLinkRefXml['es:cachedExternalMetadata'].size() == 1
		def entryCachedExternalMetadataXml = entryLinkRefXml['es:cachedExternalMetadata'][0] as Node
		entryCachedExternalMetadataXml.attributes().size() == 1
		def storedExternalMetadataUrl = entryCachedExternalMetadataXml['@rdf:resource'].toString()
		storedExternalMetadataUrl.contains('/store/' + contextId + '/cached-external-metadata/')
		entryCachedExternalMetadataXml.text() == ''

		// fetch entry metadata
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'local metadata title'

		// fetch external metadata
		def entryExternalMetaConn = EntryStoreClient.getRequest(storedExternalMetadataUrl)
		entryExternalMetaConn.getResponseCode() == HTTP_OK
		entryExternalMetaConn.getContentType().contains('application/json')
		def entryExternalMetaRespJson = JSON_PARSER.parseText(entryExternalMetaConn.getInputStream().text)
		(entryExternalMetaRespJson as Map).keySet().size() == 0
	}

	def "GET /{context-id}/entry/{entry-id} in text/n3 format for a linkreference entry, should return information about the entry in text/n3 format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'text/n3')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('text/n3')
		def response = entryConn.getInputStream().text
		response.contains('/' + contextId + '/entry/' + entryId + '> a es:LinkReference;')
		response.contains('es:resource <' + resourceUrl + '>;')
		response.contains('es:metadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId + '>;')
		response.contains('es:externalMetadata <' + metadataUrl + '>;')
		response.contains('es:cachedExternalMetadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/cached-external-metadata/' + entryId + '>;')
	}

	def "GET /{context-id}/entry/{entry-id} in text/turtle format for a linkreference entry, should return information about the entry in text/turtle format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'text/turtle')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('text/turtle')
		def response = entryConn.getInputStream().text
		response.contains('/' + contextId + '/entry/' + entryId + '> a es:LinkReference;')
		response.contains('es:resource <' + resourceUrl + '>;')
		response.contains('es:metadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId + '>;')
		response.contains('es:externalMetadata <' + metadataUrl + '>;')
		response.contains('es:cachedExternalMetadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/cached-external-metadata/' + entryId + '>;')
	}

	def "GET /{context-id}/entry/{entry-id} in application/trix format for a linkreference entry, should return information about the entry in application/trix format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)
		def expectedEntryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'application/trix')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/trix')
		def responseXml = new XmlParser(false, false).parseText(entryConn.getInputStream().text)
		responseXml['@xmlns'] == 'http://www.w3.org/2004/03/trix/trix-1/'
		responseXml['graph'].size() == 1
		def respGraphXml = responseXml['graph'][0] as Node
		// top level graph has no attributes and 11 children (1 uri + 10 triples)
		respGraphXml.attributes().size() == 0
		respGraphXml.value().size() == 11

		//   1 uri child should have: 0 attr, 1 children
		respGraphXml['uri'].size() == 1
		def graphUriXml = respGraphXml['uri'][0] as Node
		graphUriXml.attributes().size() == 0
		graphUriXml.value().size() == 1
		graphUriXml.value() == [expectedEntryUri]

		//   10 triple children, all with 0 attributes and 3 values
		def entryTriples = respGraphXml['triple'] as NodeList
		entryTriples.size() == 10
		entryTriples.every { it.name() == 'triple' && it.attributes().size() == 0 }
		entryTriples.any { tripleHasUrisEqualTo(it, [expectedEntryUri, NameSpaceConst.RDF_TYPE, NameSpaceConst.TERM_LINK_REFERENCE]) }
		entryTriples.any { tripleHasUrisEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_RESOURCE, resourceUrl]) }
		entryTriples.any { tripleHasUrisEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_METADATA, EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId]) }
		entryTriples.any { tripleHasUrisEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_EXTERNAL_METADATA, metadataUrl]) }
		entryTriples.any { tripleHasUrisEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA, EntryStoreClient.baseUrl + '/' + contextId + '/cached-external-metadata/' + entryId]) }
	}

	def tripleHasUrisEqualTo(Node triple, List<String> expectedValues) {
		def uris = triple['uri'] as NodeList
		if (uris.size() != 3)
			return false
		return uris[0].value() == [expectedValues[0]] &&
			uris[1].value() == [expectedValues[1]] &&
			uris[2].value() == [expectedValues[2]]
	}

	def "GET /{context-id}/entry/{entry-id} in application/n-triples format for a linkreference entry, should return information about the entry in application/n-triples format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)
		def expectedEntryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'application/n-triples')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/n-triples')
		def responseArray = entryConn.getInputStream().text.split('\n').collect { it.split(' ') }
		// expect 10 triples
		responseArray.size() == 10
		// expect a triple with rdf-type of LinkReference
		responseArray.any { tripleArrayEqualTo(it, [expectedEntryUri, NameSpaceConst.RDF_TYPE, NameSpaceConst.TERM_LINK_REFERENCE]) }
		responseArray.any { tripleArrayEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_RESOURCE, resourceUrl]) }
		responseArray.any { tripleArrayEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_METADATA, EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId]) }
		responseArray.any { tripleArrayEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_EXTERNAL_METADATA, metadataUrl]) }
		responseArray.any { tripleArrayEqualTo(it, [expectedEntryUri, NameSpaceConst.TERM_CACHED_EXTERNAL_METADATA, EntryStoreClient.baseUrl + '/' + contextId + '/cached-external-metadata/' + entryId]) }
	}

	def wrapInAngleBrackets(String input) {
		return '<' + input + '>'
	}

	def tripleArrayEqualTo(String[] triple, List<String> expectedValues) {
		if (triple.size() < 3)
			return false
		return triple[0] == wrapInAngleBrackets(expectedValues[0]) &&
			triple[1] == wrapInAngleBrackets(expectedValues[1]) &&
			triple[2] == wrapInAngleBrackets(expectedValues[2])
	}

	def "GET /{context-id}/entry/{entry-id} in application/trig format for a linkreference entry, should return information about the entry in application/trig format"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId, 'admin', 'application/trig')

		then:
		entryConn.getResponseCode() == HTTP_OK
		entryConn.getContentType().contains('application/trig')
		def response = entryConn.getInputStream().text
		response.contains('/' + contextId + '/entry/' + entryId + '> a es:LinkReference;')
		response.contains('es:resource <' + resourceUrl + '>;')
		response.contains('es:metadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId + '>;')
		response.contains('es:externalMetadata <' + metadataUrl + '>;')
		response.contains('es:cachedExternalMetadata <' + EntryStoreClient.baseUrl + '/' + contextId + '/cached-external-metadata/' + entryId + '>;')
	}

	def "DELETE /{context-id}/entry/{entry-id} as not-authorized user should not delete the entry"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def entryConn = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + entryId, null)

		then:
		entryConn.getResponseCode() == HTTP_UNAUTHORIZED
		entryConn.getContentType().contains('text/html')
		def response = entryConn.getErrorStream().text
		response.contains('<title>Status page</title>')
		response.contains('Unauthorized')
		response.contains('The request requires user authentication')
	}

	def "DELETE /{context-id}/entry/{entry-id} should delete the entry"() {
		given:
		def entryId = 'entryForGetTests'
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [id                        : entryId,
					  entrytype                 : 'linkreference',
					  resource                  : resourceUrl,
					  'cached-external-metadata': URLEncoder.encode(metadataUrl, UTF_8)]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'local metadata title'
											 ]]
		]]]
		getOrCreateEntry(contextId, params, body)

		when:
		def deleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + entryId)

		then:
		// DELETE request should respond with 20x
		deleteConn.getResponseCode() == HTTP_NO_CONTENT

		// GET entry by ID should respond with 404
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		entryConn.getResponseCode() == HTTP_NOT_FOUND
	}
}
