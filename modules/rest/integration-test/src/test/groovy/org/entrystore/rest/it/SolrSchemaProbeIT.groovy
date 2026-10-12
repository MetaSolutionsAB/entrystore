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

import groovy.json.JsonSlurper
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient
import org.entrystore.impl.SolrSchemaCheck

import static java.net.HttpURLConnection.HTTP_OK

/**
 * The startup schema guard fails open on an unreachable Solr: when it cannot read the schema it logs and lets the
 * boot continue, so a wrong path, a changed Schema API response shape or a parser mismatch would switch the guard
 * off with nothing failing. These specs run the guard's own schema read, through SolrJ, against the real Solr the
 * ITs run on, so the guard's input is pinned rather than assumed. The analyzer specs pin the terms the shipped
 * configset indexes, which search results alone cannot show.
 */
class SolrSchemaProbeIT extends BaseSpec {

	private static final List<String> REQUIRED_DYNAMIC_FIELDS = [
		'metadata.predicate.literal_l.*',
		'related.metadata.predicate.literal_l.*'
	]

	private static String coreUrl() {
		return 'http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core'
	}

	private static Object readSchemaDynamicFields() {
		def url = new URI(coreUrl() + '/schema/dynamicfields?wt=json').toURL()
		def connection = (HttpURLConnection) url.openConnection()
		connection.setRequestMethod('GET')
		assert connection.getResponseCode() == HTTP_OK
		return new JsonSlurper().parseText(connection.inputStream.text)
	}

	/** The terms the index analyzer of {@code fieldType} produces for {@code value}, via Solr's field analysis. */
	private static List<String> indexTerms(String fieldType, String value) {
		def url = new URI(coreUrl() + '/analysis/field?wt=json&analysis.fieldtype=' + fieldType
			+ '&analysis.fieldvalue=' + URLEncoder.encode(value, 'UTF-8')).toURL()
		def connection = (HttpURLConnection) url.openConnection()
		assert connection.getResponseCode() == HTTP_OK
		def stages = new JsonSlurper().parseText(connection.inputStream.text)['analysis']['field_types'][fieldType]['index']
		// a flat list of stage name and token list; the last token list is what gets indexed
		return stages.last().collect { it['text'] as String }
	}

	def "the text_ngram index analyzer should emit no term that spans whitespace"() {
		when:
		def terms = indexTerms('text_ngram', 'Open\tData  portal\nof Sweden')

		then:
		['ope', 'open', 'dat', 'data', 'portal', 'swede', 'sweden'].every { terms.contains(it) }
		terms.every { !(it =~ /\s/) }
	}

	def "the text_ngram index analyzer should also index words shorter than the minimum gram whole"() {
		when:
		def terms = indexTerms('text_ngram', 'EU AI policy')

		then:
		// entrystore.js sends eu* for a search on "EU", which needs a term starting with "eu"
		terms.containsAll(['eu', 'ai', 'pol'])
	}

	def "the text_ngram index analyzer should not split a word longer than 255 characters"() {
		given:
		def word = 'x' * 250 + 'boundary' + 'y' * 50

		when:
		def terms = indexTerms('text_ngram', word)

		then:
		// this gram crosses character 255, where WhitespaceTokenizer splits by default
		terms.contains('xxxboundaryyy')
	}

	private static Object readSchemaFieldType(String name) {
		def url = new URI(coreUrl() + '/schema/fieldtypes/' + name + '?wt=json').toURL()
		def connection = (HttpURLConnection) url.openConnection()
		assert connection.getResponseCode() == HTTP_OK
		return new JsonSlurper().parseText(connection.inputStream.text)['fieldType']
	}

	def "the string, boolean and date types are declared with docValues"() {
		expect:
		// facets and sorts on these fields read docValues; without them Solr uninverts the fields onto its heap
		readSchemaFieldType('string')['docValues'] == true
		readSchemaFieldType('boolean')['docValues'] == true
		readSchemaFieldType('date')['docValues'] == true
	}

	def "the startup guard reads the required dynamic fields from the Solr core used by the ITs"() {
		given:
		def client = new HttpJettySolrClient.Builder(coreUrl()).useHttp1_1(true).build()

		when:
		def declared = SolrSchemaCheck.dynamicFieldNames(client)
		def verified = SolrSchemaCheck.requireDynamicFields(client, coreUrl(), REQUIRED_DYNAMIC_FIELDS)

		then:
		// the exact read the guard performs at startup: if it ever stops parsing the names, startup would run
		// unverified, so the names must come back and the guard must report the schema verified
		declared.containsAll(REQUIRED_DYNAMIC_FIELDS)
		verified

		cleanup:
		client?.close()
	}

	def "the language companion field is declared docValues-only"() {
		when:
		def response = readSchemaDynamicFields()
		def companion = response['dynamicFields'].find { it['name'] == 'metadata.predicate.literal_l.*' }

		then:
		companion != null
		// faceting and facet.matches read docValues; the field is never queried, so an inverted index would be
		// index cost for nothing on every literal of every entry
		companion['docValues'] == true
		companion['indexed'] == false
		companion['stored'] == false
	}
}
