package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class LocalMetadataResourceIT extends BaseSpec {

	def static contextId = '90'
	def resourceUrl = 'https://bbc.co.uk'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/metadata/{entryId}=fetch local metadata"() {
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
		dcTitles[0]['value'] == 'Cool entry'
	}

	def "GET /{context-id}/metadata/{entryId}=fetch local metadata of non-existing entry"() {
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

		when:
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl)

		then:
		entryMetaConn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/metadata/{entryId}=fetch local metadata with invalid recursive parameter"() {
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
		def entryMetaConn = EntryStoreClient.getRequest(storedMetadataUrl + "?recursive=10")

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
}
