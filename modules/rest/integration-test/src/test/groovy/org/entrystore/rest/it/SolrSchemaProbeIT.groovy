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

import static java.net.HttpURLConnection.HTTP_OK

/**
 * The startup schema guard fails open: when it cannot read the schema it logs and lets the boot continue, so a
 * wrong path, a changed Schema API response shape or a parser mismatch would switch the guard off with nothing
 * failing. These specs read the same Schema API endpoint the guard reads, against the real Solr the ITs run on,
 * so the guard's input is pinned rather than assumed.
 */
class SolrSchemaProbeIT extends BaseSpec {

	private static final List<String> REQUIRED_DYNAMIC_FIELDS = [
		'metadata.predicate.literal_l.*',
		'related.metadata.predicate.literal_l.*'
	]

	private static Object readSchemaDynamicFields() {
		def url = new URI('http://localhost:' + solrContainer.getSolrPort()
			+ '/solr/entrystore-core/schema/dynamicfields?wt=json').toURL()
		def connection = (HttpURLConnection) url.openConnection()
		connection.setRequestMethod('GET')
		assert connection.getResponseCode() == HTTP_OK
		return new JsonSlurper().parseText(connection.inputStream.text)
	}

	def "the Solr core used by the ITs declares the dynamic fields the startup guard requires"() {
		when:
		def response = readSchemaDynamicFields()
		def declared = response['dynamicFields'].collect { it['name'] } as Set

		then:
		// the guard reads exactly this list; if the endpoint or the response shape ever changes, it silently
		// stops checking anything, so assert the names are readable and present
		declared.containsAll(REQUIRED_DYNAMIC_FIELDS)
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
