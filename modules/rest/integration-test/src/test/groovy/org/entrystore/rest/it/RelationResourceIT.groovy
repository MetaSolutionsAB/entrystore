package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.HTTP_NOT_ACCEPTABLE
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK

class RelationResourceIT extends BaseSpec {

	def static contextId = '60'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/relations/{entry-id} as guest on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/randomEntryId', '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == 'No entry with id \'randomEntryId\' found in context \'60\''
	}

	def "GET /{context-id}/relations/{entry-id} as admin on non-existing entry should return 404"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/randomEntryId')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.errorStream.text)
		json['error'] == 'No entry with id \'randomEntryId\' found in context \'60\''
	}

	def "GET /{context-id}/relations/{entry-id} as guest on a String entry should return no relations"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/' + entryId, '')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		// empty json = no relations for this entry
		(json as Map).keySet().size() == 0
	}

	def "GET /{context-id}/relations/{entry-id} as admin on a String entry should return no relations"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [graphtype: 'string']
		def body = [resource: someText]
		def entryId = createEntry(contextId, params, body)
		assert entryId.length() > 0

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/' + entryId)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		// empty json = no relations for this entry
		(json as Map).keySet().size() == 0
	}

	def "GET /{context-id}/relations/{entry-id} as guest on a Context entry should return relation to home context"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId, '')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		(json as Map).keySet().size() == 1
		def relationJsonKey = (json as Map).keySet()[0].toString()
		relationJsonKey.contains('/_principals/resource/')
		json[relationJsonKey] == [(NameSpaceConst.TERM_HOME_CONTEXT): [[type : 'uri',
																		value: EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId]]]
	}

	def "GET /{context-id}/relations/{entry-id} as admin on a Context entry should return relation to home context"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def json = JSON_PARSER.parseText(connection.inputStream.text)
		(json as Map).keySet().size() == 1
		def relationJsonKey = (json as Map).keySet()[0].toString()
		relationJsonKey.contains('/_principals/resource/')
		json[relationJsonKey] == [(NameSpaceConst.TERM_HOME_CONTEXT): [[type : 'uri',
																		value: EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId]]]
	}

	def "GET /{context-id}/relations/{entry-id} on a Context entry should return relation to home context, in rdf+xml format by default"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId, 'admin', null)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/rdf+xml')
		def responseXml = new XmlParser(false, false).parse(connection.getInputStream())
		responseXml.attributes().size() > 17
		responseXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		responseXml.attributes()['xmlns:rdf'] == NameSpaceConst.RDF
		responseXml.attributes()['xmlns:es'] == NameSpaceConst.ES_TERMS
		responseXml.value().size() == 1

		def descNode = responseXml.value()[0] as Node
		descNode.name() == 'rdf:Description'
		descNode.attributes().size() == 1
		descNode.attributes()['rdf:about'].toString().contains('/_principals/resource/')
		descNode.value().size() == 1

		def childNode = descNode.value()[0] as Node
		childNode.name() == 'es:homeContext'
		childNode.attributes().size() == 1
		childNode.attributes()['rdf:resource'] == EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId
		childNode.value().size() == 0
	}

	def "GET /{context-id}/relations/{entry-id} with format=text/html should return 406 Not Acceptable"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId + '?format=text/html')

		then:
		connection.getResponseCode() == HTTP_NOT_ACCEPTABLE
	}

	def "GET /{context-id}/relations/{entry-id} with format=text/turtle should return 200"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId + '?format=text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/turtle')
	}

	def "GET /{context-id}/relations/{entry-id} with Accept header containing supported type among unsupported ones should return 200"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId, 'admin', 'text/html, text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/turtle')
	}
}
