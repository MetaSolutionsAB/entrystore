/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.text.SimpleDateFormat

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
		json['error'] == 'Not Found'
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

	/**
	 * ENTRYSTORE-1055. This endpoint emits Last-Modified and ETag, but nothing asserted it — the whole
	 * file had no header assertion, so when RelationController moved to HttpUtil's helper the change was
	 * covered by nothing. Deleting the helper call would leave every relations response without
	 * validators, silently defeating conditional GETs, with the suite still green.
	 */
	def "GET /{context-id}/relations/{entry-id} should include Last-Modified and ETag headers"() {
		given:
		def beforeRequest = new Date()
		def entryId = createEntry(contextId, [graphtype: 'string'], [resource: 'Header test entry'])
		assert entryId.length() > 0

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/relations/' + entryId)

		then:
		connection.getResponseCode() == HTTP_OK

		def lastModified = connection.getHeaderField('Last-Modified')
		lastModified != null
		def httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
		httpDateFormat.parse(lastModified).time >= beforeRequest.time - 1000

		def etag = connection.getHeaderField('ETag')
		etag != null
		etag ==~ /"\d+"/
	}

	def "GET /{context-id}/relations/{entry-id} with Accept header containing supported type among unsupported ones should return 200"() {
		when:
		def connection = EntryStoreClient.getRequest('/_contexts/relations/' + contextId, 'admin', 'text/html, text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/turtle')
	}
}
