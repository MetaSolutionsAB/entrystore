package org.entrystore.rest.it

import groovy.json.JsonSlurper
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
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
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

	def "PUT /{context-id}/resource/{entry-id} should edit all resource data"() {
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
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
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

	def "DELETE /{context-id}/resource/{entry-id} should remove resource"() {
		given:
		// create minimal entry to be used in the list
		def givenEntryId = createEntry(contextId, [:])
		// create a list with the minimal entry
		def params = [graphtype: 'list']
		def body = [resource: [givenEntryId]]
		def entryId = createEntry(contextId, params, body)

		// fetch URI of the created resource for the String entry
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		assert entryConn.getResponseCode() == HTTP_OK
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
		assert entryRespJson['info'] != null
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		// fetch resource details
		def resourceConn = EntryStoreClient.getRequest(resourceUri)
		assert resourceConn.getResponseCode() == HTTP_OK
		assert resourceConn.getContentType().contains('application/json')
		def resourceRespJson = (new JsonSlurper()).parseText(resourceConn.getInputStream().text)
		assert resourceRespJson == [givenEntryId]

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

	def "DELETE /{context-id}/resource/{entry-id} does not delete resource if it is String type"() {
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
		def entryRespJson = (new JsonSlurper()).parseText(entryConn.getInputStream().text)
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
}
