package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_BAD_METHOD
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class LocalMetadataResourceIT extends BaseSpec {

	def static contextId = '90'
	def resourceUrl = 'https://bbc.co.uk'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata of the entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri)

		then:
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

	def "GET /{context-id}/metadata/{entryId} should not fetch local metadata of non-existing entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId
		def deleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + entryId)
		deleteConn.getResponseCode() == HTTP_NO_CONTENT

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri)

		then:
		entryMetaConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/metadata/{entryId} should not fetch metadata of the entry without metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'reference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri)

		then:
		entryMetaConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/metadata/{entryId} should not fetch local metadata with an invalid recursive parameter"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=10")

		then:
		entryMetaConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /{context-id}/metadata/{entryId} should not fetch local metadata with an invalid depth parameter"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		when:
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl + "?recursive=dcat&depth=abcd")

		then:
		entryMetaConn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "PUT /{context-id}/metadata/{entry-id} should update the metadata of the entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		def storedMetadataUrl = entryMetadata[0]['value'].toString()
		def newBody = [(storedMetadataUrl): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																			   value: 'Not cool entry']],]]

		when:
		def editEntryConn = EntryStoreClient.putRequest('/' + contextId + '/metadata/' + entryId,
			JsonOutput.toJson(newBody), 'admin', 'application/json')

		then:
		editEntryConn.getResponseCode() == HTTP_NO_CONTENT

		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Not cool entry'
	}

	def "PUT /{context-id}/metadata/{entry-id} should not update the metadata of the non-existing entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		def storedMetadataUrl = entryMetadata[0]['value'].toString()
		def deleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/entry/' + entryId)
		deleteConn.getResponseCode() == HTTP_NO_CONTENT
		def newBody = [(storedMetadataUrl): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																			   value: 'Not cool entry']],]]

		when:
		def editEntryConn = EntryStoreClient.putRequest('/' + contextId + '/metadata/' + entryId,
			JsonOutput.toJson(newBody), 'admin', 'application/json')

		then:
		editEntryConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "DELETE /{context-id}/metadata/{entryId} should delete local metadata of the entry"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryUri = EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId
		def entryMetadata = entryRespJson['info'][entryUri][NameSpaceConst.TERM_METADATA].collect()
		def storedMetadataUrl = entryMetadata[0]['value'].toString()

		when:
		def entryDeleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/metadata/' + entryId)


		then:
		entryDeleteConn.getResponseCode() == HTTP_NO_CONTENT
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 0
	}

	def "DELETE /{context-id}/metadata/{entryId} should not delete metadata of the entry without metadata"() {
		given:
		def metadataUrl = 'https://bbc.co.uk/metadata'
		def params = [entrytype: 'reference', resource: resourceUrl, 'cached-external-metadata': metadataUrl]
		def entryId = createEntry(contextId, params)

		when:
		def entryDeleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/metadata/' + entryId)

		then:
		entryDeleteConn.getResponseCode() == HTTP_BAD_METHOD
	}

	def "DELETE /{context-id}/metadata/{entryId} should not delete local metadata of the entry as un-authorized user"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.DC_TERM_TITLE): [[type : 'literal',
																					value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)

		when:
		def entryDeleteConn = EntryStoreClient.deleteRequest('/' + contextId + '/metadata/' + entryId, null)


		then:
		entryDeleteConn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /{context-id}/metadata/{entryId} should not fetch local metadata with a valid recursive parameter, when that is not part of the metadata"() {
		given:
		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [(NameSpaceConst.TERM_EXTERNAL_METADATA): [[type : 'literal',
																							 value: 'Cool entry']],]]]
		def entryId = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryId

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 1
		def metaResourceUrl = (entryMetaRespJson as Map).keySet()[0].toString()
		entryMetaRespJson[metaResourceUrl][NameSpaceConst.TERM_EXTERNAL_METADATA] != null
		def dcTitles = entryMetaRespJson[metaResourceUrl][NameSpaceConst.TERM_EXTERNAL_METADATA].collect()
		dcTitles.size() == 1
		dcTitles[0]['type'] == 'literal'
		dcTitles[0]['value'] == 'Cool entry'
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata with a valid recursive parameter and full max-depth"() {
		given:
		def entryD2params = [entrytype: 'link', resource: resourceUrl + '/2']
		def entryD2ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD2body = [metadata: [(entryD2ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth2']
			]
		]]]
		def entryD2Id = createEntry(contextId, entryD2params, entryD2body)

		def entryD1params = [entrytype: 'link', resource: resourceUrl + '/1']
		def entryD1ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD1body = [metadata: [(entryD1ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth1']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id]
			]
		]]]
		def entryD1Id = createEntry(contextId, entryD1params, entryD1body)

		def entryD0params = [entrytype: 'link', resource: resourceUrl + '/0']
		def entryD0ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD0body = [metadata: [(entryD0ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth0']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id]
			]
		]]]
		def entryD0Id = createEntry(contextId, entryD0params, entryD0body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryD0Id

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 3
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth0Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth0Titles.size() == 1
		dcDepth0Titles[0]['type'] == 'literal'
		dcDepth0Titles[0]['value'] == 'Depth0'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth0Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth0Creators.size() == 1
		dcDepth0Creators[0]['type'] == 'uri'
		dcDepth0Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth1Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth1Titles.size() == 1
		dcDepth1Titles[0]['type'] == 'literal'
		dcDepth1Titles[0]['value'] == 'Depth1'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth1Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth1Creators.size() == 1
		dcDepth1Creators[0]['type'] == 'uri'
		dcDepth1Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth2Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth2Titles.size() == 1
		dcDepth2Titles[0]['type'] == 'literal'
		dcDepth2Titles[0]['value'] == 'Depth2'
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata with a valid recursive parameter with depth=1, even if the data is deeper"() {
		given:
		def entryD2params = [entrytype: 'link', resource: resourceUrl + '/2']
		def entryD2ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD2body = [metadata: [(entryD2ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth2']
			]
		]]]
		def entryD2Id = createEntry(contextId, entryD2params, entryD2body)

		def entryD1params = [entrytype: 'link', resource: resourceUrl + '/']
		def entryD1ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD1body = [metadata: [(entryD1ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth1']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id]
			]
		]]]
		def entryD1Id = createEntry(contextId, entryD1params, entryD1body)

		def entryD0params = [entrytype: 'link', resource: resourceUrl + '/0']
		def entryD0ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD0Body = [metadata: [(entryD0ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth0']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id]
			]
		]]]
		def entryD0Id = createEntry(contextId, entryD0params, entryD0Body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryD0Id

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct&depth=1")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 2
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth0Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth0Titles.size() == 1
		dcDepth0Titles[0]['type'] == 'literal'
		dcDepth0Titles[0]['value'] == 'Depth0'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth0Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth0Creators.size() == 1
		dcDepth0Creators[0]['type'] == 'uri'
		dcDepth0Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth1Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth1Titles.size() == 1
		dcDepth1Titles[0]['type'] == 'literal'
		dcDepth1Titles[0]['value'] == 'Depth1'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth1Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth1Creators.size() == 1
		dcDepth1Creators[0]['type'] == 'uri'
		dcDepth1Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata with a valid recursive parameter with max-depth, even if the data is deeper and depth parameter is higher"() {
		given:
		def entryD3params = [entrytype: 'link', resource: resourceUrl + '/3']
		def entryD3ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD3body = [metadata: [(entryD3ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth3']
			]
		]]]
		def entryD3Id = createEntry(contextId, entryD3params, entryD3body)

		def entryD2params = [entrytype: 'link', resource: resourceUrl + '/2']
		def entryD2ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD2body = [metadata: [(entryD2ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth2']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD3Id]
			]
		]]]
		def entryD2Id = createEntry(contextId, entryD2params, entryD2body)

		def entryD1params = [entrytype: 'link', resource: resourceUrl + '/1']
		def entryD1ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD1body = [metadata: [(entryD1ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth1']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id]
			]
		]]]
		def entryD1Id = createEntry(contextId, entryD1params, entryD1body)

		def entryD0Params = [entrytype: 'link', resource: resourceUrl + '/0']
		def entryD0ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD0Body = [metadata: [(entryD0ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth0']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id]
			]
		]]]
		def entryD0Id = createEntry(contextId, entryD0Params, entryD0Body)

		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryD0Id

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct&depth=3")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 3
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth0Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth0Titles.size() == 1
		dcDepth0Titles[0]['type'] == 'literal'
		dcDepth0Titles[0]['value'] == 'Depth0'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth0Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD0Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth0Creators.size() == 1
		dcDepth0Creators[0]['type'] == 'uri'
		dcDepth0Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth1Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth1Titles.size() == 1
		dcDepth1Titles[0]['type'] == 'literal'
		dcDepth1Titles[0]['value'] == 'Depth1'
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR] != null
		def dcDepth1Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth1Creators.size() == 1
		dcDepth1Creators[0]['type'] == 'uri'
		dcDepth1Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata with a valid recursive parameter and full max-depth, but with blacklisted profile"() {
		given:
		def entryD3params = [entrytype: 'link', resource: resourceUrl + '/1']
		def entryD3ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD3body = [metadata: [(entryD3ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth3']
			]
		]]]
		def entryD3Id = createEntry(contextId, entryD3params, entryD3body)

		def entryD2params = [entrytype: 'link', resource: resourceUrl + '/2']
		def entryD2ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD2body = [metadata: [(entryD2ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth2']
			]
		]]]
		def entryD2Id = createEntry(contextId, entryD2params, entryD2body)

		def params = [entrytype: 'link', resource: resourceUrl]
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)    : [
				[type: 'literal', value: 'Depth1']
			],
			(NameSpaceConst.DC_TERM_CREATOR)  : [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id]
			],
			(NameSpaceConst.DC_TERM_PUBLISHER): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD3Id]
			]
		]]]
		def entryD1Id = createEntry(contextId, params, body)
		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryD1Id

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 2
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth0Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth0Titles.size() == 1
		dcDepth0Titles[0]['type'] == 'literal'
		dcDepth0Titles[0]['value'] == 'Depth1'
		def dcDepth0Creators = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_CREATOR].collect()
		dcDepth0Creators.size() == 1
		dcDepth0Creators[0]['type'] == 'uri'
		dcDepth0Creators[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE] != null
		def dcDepth1Titles = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_TITLE].collect()
		dcDepth1Titles.size() == 1
		dcDepth1Titles[0]['type'] == 'literal'
		dcDepth1Titles[0]['value'] == 'Depth1'
		def dcDepth0Publishers = entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id][NameSpaceConst.DC_TERM_PUBLISHER].collect()
		dcDepth0Publishers.size() == 1
		dcDepth0Publishers[0]['type'] == 'uri'
		dcDepth0Publishers[0]['value'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD3Id
		entryMetaRespJson[EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD3Id] == null
	}

	def "GET /{context-id}/metadata/{entryId} should fetch local metadata with a valid recursive parameter with limit number of entries"() {
		given:
		def entryD6params = [entrytype: 'link', resource: resourceUrl + '/6']
		def entryD6ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD6body = [metadata: [(entryD6ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Width6']
			]
		]]]
		def entryD6Id = createEntry(contextId, entryD6params, entryD6body)

		def entryD5params = [entrytype: 'link', resource: resourceUrl + '/5']
		def entryD5ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD5body = [metadata: [(entryD5ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Width5']
			]
		]]]
		def entryD5Id = createEntry(contextId, entryD5params, entryD5body)

		def entryD4params = [entrytype: 'link', resource: resourceUrl + '/4']
		def entryD4ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD4body = [metadata: [(entryD4ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Width4']
			]
		]]]
		def entryD4Id = createEntry(contextId, entryD4params, entryD4body)

		def entryD3params = [entrytype: 'link', resource: resourceUrl + '/3']
		def entryD3ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD3body = [metadata: [(entryD3ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [
				[type: 'literal', value: 'Depth3']
			]
		]]]
		def entryD3Id = createEntry(contextId, entryD3params, entryD3body)

		def entryD2params = [entrytype: 'link', resource: resourceUrl + '/2']
		def entryD2ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD2body = [metadata: [(entryD2ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth2']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD3Id]
			]
		]]]
		def entryD2Id = createEntry(contextId, entryD2params, entryD2body)

		def entryD1params = [entrytype: 'link', resource: resourceUrl + '/1']
		def entryD1ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD1body = [metadata: [(entryD1ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth1']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD2Id]
			]
		]]]
		def entryD1Id = createEntry(contextId, entryD1params, entryD1body)

		def entryD0Params = [entrytype: 'link', resource: resourceUrl + '/0']
		def entryD0ResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def entryD0Body = [metadata: [(entryD0ResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE)  : [
				[type: 'literal', value: 'Depth0']
			],
			(NameSpaceConst.DC_TERM_CREATOR): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD1Id]
			],
			(NameSpaceConst.DC_TERM_PUBLISHER): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD4Id]
			],
			(NameSpaceConst.DC_TERM_SUBJECT): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD5Id]
			],
			(NameSpaceConst.DC_TERM_DESCRIPTION): [
				[type : 'uri',
				 value: EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryD6Id]
			]
		]]]
		def entryD0Id = createEntry(contextId, entryD0Params, entryD0Body)

		def metadataUri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/' + entryD0Id

		when:
		def entryMetaConn = EntryStoreClient.getRequest(metadataUri + "?recursive=dct")

		then:
		entryMetaConn.getResponseCode() == HTTP_OK
		entryMetaConn.getContentType().contains('application/json')
		def entryMetaRespJson = JSON_PARSER.parseText(entryMetaConn.getInputStream().text)
		(entryMetaRespJson as Map).keySet().size() == 4
	}
}
