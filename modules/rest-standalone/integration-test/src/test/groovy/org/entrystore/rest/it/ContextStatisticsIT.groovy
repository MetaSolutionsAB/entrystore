package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static java.net.HttpURLConnection.*

class ContextStatisticsIT extends BaseSpec {

	def static contextId = '30'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /{context-id}/statistics/properties should respond with BadRequest for non-existing context ID"() {
		when:
		def connection = EntryStoreClient.getRequest('/notExistingContextId/statistics/properties')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getErrorStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'The requested context ID does not exist'
	}

	def "GET /{context-id}/statistics/properties on empty context should respond with zero counts of properties statistics"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/properties')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == 0
		responseJson['propertyUsage'] == []
		responseJson['entryCountValidated'] == 0
	}

	def "GET /{context-id}/statistics/properties as non-admin user on empty context should respond with zero counts of properties statistics"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/properties', null)

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == 0
		responseJson['propertyUsage'] == []
		responseJson['entryCountValidated'] == 0
	}

	def "GET /{context-id}/statistics/ontology on empty context should respond with zero counts of ontology statistics"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/ontology')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == 0
		responseJson['ontologyTermUsage'] == []
		responseJson['predicateUsage'] == []
	}

	def "GET /{context-id}/statistics/keywords on empty context should respond with zero counts of keywords statistics"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/keywords')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == 0
		responseJson['entryCountWithKeyword'] == 0
		responseJson['keywordUsage'] == []
	}

	def "GET /{context-id}/statistics/propertiesDetailed on empty context should respond with error as it's not supported yet"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/propertiesDetailed')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'This statistics type is not supported yet'
		responseJson['entryCount'] == null
	}

	def "GET /{context-id}/statistics/relations on empty context should respond with error as it's not supported yet"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/relations')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'This statistics type is not supported yet'
		responseJson['entryCount'] == null
	}

	def "GET /{context-id}/statistics/type on empty context should respond with error as it's not supported yet"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/type')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'This statistics type is not supported yet'
		responseJson['entryCount'] == null
	}

	def "GET /{context-id}/statistics/randomType on empty context should respond with unknown type error"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/randomType')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] != null
		responseJson['error'] == 'Unknown statistics type'
		responseJson['entryCount'] == null
	}

	def "GET /{context-id}/statistics/competence on empty context should respond with zero counts fo competence statistics"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/competence')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == null
		responseJson['nrOfPersons'] > 1
	}

	def "GET /{context-id}/statistics/properties on non-empty context should respond with non-zero properties statistics"() {
		given:
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def params = [entrytype: 'link', resource: newResourceIri]
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_TITLE): [[
												 type : 'literal',
												 value: 'Cool creator'
											 ]],
		]]]
		createEntry(contextId, params, body)

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/properties')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] == 1
		responseJson['propertyUsage'] != null
		def propertiesUsage = responseJson['propertyUsage'].collect()
		propertiesUsage.size() == 1
		propertiesUsage[0]['property'] == NameSpaceConst.DC_TERM_TITLE
		propertiesUsage[0]['usedInCount'] == 1
		propertiesUsage[0]['statements'] == 1
		responseJson['entryCountValidated'] == 0
	}

	def "GET /{context-id}/statistics/properties?profile=someProfile on non-empty context should respond with non-zero properties statistics"() {
		given:
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def params = [entrytype: 'link', resource: newResourceIri]
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_CREATOR): [[
												   type : 'literal',
												   value: 'Cool creator'
											   ]],
		]]]
		createEntry(contextId, params, body)

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/properties?profile=someProfile')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] > 0
		responseJson['propertyUsage'] != null
		def propertiesUsage = responseJson['propertyUsage'].collect()
		propertiesUsage.size() > 0
		propertiesUsage.contains([property: NameSpaceConst.DC_TERM_CREATOR, usedInCount: 1, statements: 1])
		responseJson['entryCountMandatory'] == 0
		responseJson['entryCountRecommended'] == 0
		responseJson['entryCountValidated'] == 0
	}

	def "GET /{context-id}/statistics/ontology on non-empty context should respond with non-zero counts of ontology statistics"() {
		given:
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def params = [entrytype: 'link', resource: newResourceIri]
		def body = [metadata: [(newResourceIri): [
			'http://www.cc.uah.es/ie/ont/OE-Predicates#IsAbout': [[
																	  type : 'literal',
																	  value: 'What is this all'
																  ]],
		]]]
		createEntry(contextId, params, body)

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/ontology')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] > 0

		responseJson['ontologyTermUsage'] != null
		def ontologyTermUsage = responseJson['ontologyTermUsage'].collect()
		ontologyTermUsage.size() > 0
		ontologyTermUsage.contains([usedInCount: 1, title: 'What is this all', totalCount: 1])

		responseJson['predicateUsage'] != null
		def predicateUsage = responseJson['predicateUsage'].collect()
		predicateUsage.size() > 0
		predicateUsage.contains([usedInCount: 1, title: 'IsAbout', totalCount: 1])
	}

	def "GET /{context-id}/statistics/keywords on non-empty context should respond with non-zero counts of keywords statistics"() {
		given:
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def params = [entrytype: 'link', resource: newResourceIri]
		def body = [metadata: [(newResourceIri): [
			(NameSpaceConst.DC_TERM_SUBJECT): [[
												   type : 'literal',
												   value: 'This is a subject'
											   ]],
		]]]
		createEntry(contextId, params, body)

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/statistics/keywords')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		responseJson['error'] == null
		responseJson['entryCount'] > 0
		responseJson['entryCountWithKeyword'] > 0
		responseJson['keywordUsage'] != null
		def keywordUsage = responseJson['keywordUsage'].collect()
		keywordUsage.size() > 0
		keywordUsage.contains([usedInCount: 1, title: 'this is a subject', totalCount: 1])
	}

}
