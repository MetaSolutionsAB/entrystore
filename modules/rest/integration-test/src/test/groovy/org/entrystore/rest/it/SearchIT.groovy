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
import org.entrystore.repository.util.HashType
import org.entrystore.repository.util.Hashing
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import spock.lang.Unroll

import java.time.Year
import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static org.awaitility.Awaitility.await

class SearchIT extends BaseSpec {

	def static contextId = 'searchContextId'
	def static entryId = ''
	def static decimalEntryId = ''
	def static malformedDecimalEntryId = ''

	// Test-only predicate attached to this IT's entries so SPARQL syndication tests can query
	// for a result set containing only SearchIT's entries, regardless of what other ITs have
	// added to the shared repository. Without this the feed's default-size window fills with
	// dc:title-bearing entries from other ITs and pushes SearchIT's entries out.
	static final String MARKER_PREDICATE_IRI = 'http://example.org/ns/searchIT-marker'

	// Test-only predicate carrying an xsd:double literal, used by the numeric range-query specs.
	// Its Solr dynamic field is derived from the predicate IRI with the same MD5 truncation the
	// indexer uses (SolrSearchIndex.addGenericMetadataFields), so the test queries the exact field
	// the entry was indexed under.
	static final String DECIMAL_PREDICATE_IRI = 'http://example.org/ns/searchIT-decimal'
	static final String DECIMAL_FIELD = 'metadata.predicate.decimal.' + Hashing.hash(DECIMAL_PREDICATE_IRI, HashType.MD5).substring(0, 8)

	// Test-only predicate carrying a plain string literal, attached to the malformed-decimal entry so
	// that entry can be located in Solr via its literal_s field. Used to prove the entry is still
	// indexed even though its xsd:double-typed literal has a non-numeric lexical form (which the
	// indexer skips via the NumberFormatException guard).
	static final String MALFORMED_MARKER_IRI = 'http://example.org/ns/searchIT-malformed-marker'
	static final String MALFORMED_MARKER_FIELD = 'metadata.predicate.literal_s.' + Hashing.hash(MALFORMED_MARKER_IRI, HashType.MD5).substring(0, 8)
	static final String MALFORMED_MARKER_VALUE = 'malformedmarkervalue'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'

		// Create local String entry used by Solr and SPARQL tests
		def someText = 'Some text'
		def params = [id: 'searchEntryId', graphtype: 'string']
		def body = [resource: someText,
					metadata: [(newResourceIri): [
						(NameSpaceConst.DC_TERM_TITLE)      : [
							[
								type : 'literal',
								value: 'local metadata title explicitly in EN',
								lang : 'en'
							],
							[
								type : 'literal',
								value: 'local metadata title implicitly in EN',
							],
							[
								type : 'literal',
								value: 'lokalne metadane tytułsearch jawnie po polsku',
								lang : 'pl'
							]
						],
						(NameSpaceConst.DC_TERM_DESCRIPTION): [
							[
								type : 'literal',
								value: 'local metadata description implicitly in EN',
							],
							[
								type : 'literal',
								value: 'local metadata description explicitly in EN',
								lang : 'en'
							],
							[
								type : 'literal',
								value: 'lokalne metadane opissearch jawnie po polsku',
								lang : 'pl'
							]
						],
						(MARKER_PREDICATE_IRI)              : [
							[type: 'literal', value: 'searchIT-entry-1']
						]
					]]]

		entryId = getOrCreateEntry(contextId, params, body)
		assert entryId.length() > 0

		// Second entry also carries the marker predicate and an English-tagged dc:description
		// so the syndication feed assertions that require size() > 1 for title/summary/description
		// see at least two values in an EN-lang feed.
		def secondParams = [id: 'searchEntryId2', graphtype: 'string']
		def secondBody = [resource: 'Second text',
						  metadata: [(newResourceIri): [
							  (NameSpaceConst.DC_TERM_TITLE)      : [
								  [type: 'literal', value: 'second entry title', lang: 'en']
							  ],
							  (NameSpaceConst.DC_TERM_DESCRIPTION): [
								  [type: 'literal', value: 'second entry description', lang: 'en']
							  ],
							  (MARKER_PREDICATE_IRI)              : [
								  [type: 'literal', value: 'searchIT-entry-2']
							  ]
						  ]]]
		getOrCreateEntry(contextId, secondParams, secondBody)

		// Entry carrying only an xsd:double literal — deliberately no dc:title/dc:description/marker
		// predicate, so the count assertions in the other specs (results == 1, item.size() == 2) stay
		// valid. Used by the numeric range-query specs below.
		def decimalParams = [id: 'searchDecimalEntryId', graphtype: 'string']
		def decimalBody = [resource: 'Decimal text',
						   metadata: [(newResourceIri): [
							   (DECIMAL_PREDICATE_IRI): [
								   [type    : 'literal',
									value   : '42.5',
									datatype: 'http://www.w3.org/2001/XMLSchema#double']
							   ]
						   ]]]
		decimalEntryId = getOrCreateEntry(contextId, decimalParams, decimalBody)
		assert decimalEntryId.length() > 0

		// Entry whose DECIMAL_PREDICATE_IRI value is typed xsd:double but has a non-numeric lexical
		// form. The indexer's isDecimalLiteral guard matches (datatype is xsd:double), l.doubleValue()
		// throws NumberFormatException, and the decimal field is skipped — but the rest of the entry
		// must still index. The plain-string MALFORMED_MARKER_IRI lets us confirm that in Solr.
		def malformedParams = [id: 'searchMalformedDecimalEntryId', graphtype: 'string']
		def malformedBody = [resource: 'Malformed decimal text',
							 metadata: [(newResourceIri): [
								 (DECIMAL_PREDICATE_IRI): [
									 [type    : 'literal',
									  value   : 'not-a-number',
									  datatype: 'http://www.w3.org/2001/XMLSchema#double']
								 ],
								 (MALFORMED_MARKER_IRI) : [
									 [type: 'literal', value: MALFORMED_MARKER_VALUE]
								 ]
							 ]]]
		malformedDecimalEntryId = getOrCreateEntry(contextId, malformedParams, malformedBody)
		assert malformedDecimalEntryId.length() > 0

		waitForSolrProcessing()
		// waitForSolrProcessing only proves the Solr post queue drained; commitWithin(1s) means the
		// searcher can lag behind it. Poll for the last-queued entry until the commit makes it
		// visible — a commit is index-wide, so the earlier entries become searchable with it.
		await()
			.pollInterval(200, TimeUnit.MILLISECONDS)
			.atMost(15, TimeUnit.SECONDS)
			.until {
				def probe = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(
					[type: 'solr', query: MALFORMED_MARKER_FIELD + ':' + MALFORMED_MARKER_VALUE]))
				probe.getResponseCode() == HTTP_OK && JSON_PARSER.parseText(probe.inputStream.text)['results'] >= 1
			}
	}

	// TODO: Fix inconsistency - guest users get empty results for Solr searches but an authorization error for SPARQL searches

	def "GET /search?type=solr with complex Solr query as guest should return empty search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch', '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] == 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		respJson['resource']['children'].collect().size() == 0
	}

	def "GET /search?type=solr with complex Solr query as non-admin user should return empty search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch',
			'user')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] == 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		respJson['resource']['children'].collect().size() == 0
	}

	def "GET /search?type=solr with complex Solr query as admin should return search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] == 1
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		results.size() == 1
		results[0]['metadata'] != null
		def metadata = results[0]['metadata'][EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId]
		metadata != null
		metadata[NameSpaceConst.DC_TERM_TITLE] != null
		metadata[NameSpaceConst.DC_TERM_TITLE].collect().size() == 3
		metadata[NameSpaceConst.DC_TERM_TITLE].collect().contains([type : 'literal',
																   value: 'lokalne metadane tytułsearch jawnie po polsku',
																   lang : 'pl'])

	}

	def "GET /search?type=solr with numeric range query covering an indexed decimal literal should return the entry"() {
		// This is the numeric-vs-lexicographic discriminator: 10 <= 42.5 <= 100 numerically, but as
		// strings "42.5" > "100" (because '4' > '1'), so a string-typed field would NOT match. Passing
		// proves the field is indexed and queried as a number.
		given:
		def query = DECIMAL_FIELD + ':[10 TO 100]'

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams([type: 'solr', query: query]))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] >= 1
		def results = respJson['resource']['children'].collect()
		results.find { it['entryId'] == decimalEntryId } != null
	}

	def "GET /search?type=solr with a fractional range tightly bracketing the literal should return the entry"() {
		// Proves fractional double precision: only a value of ~42.5 falls inside the 0.2-wide window
		// [42.4 TO 42.6]. A field that stored the value as an integer (truncated 42 or rounded 43)
		// would be excluded, so this guards against the field type regressing to slong.
		given:
		def query = DECIMAL_FIELD + ':[42.4 TO 42.6]'

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams([type: 'solr', query: query]))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		def results = respJson['resource']['children'].collect()
		results.find { it['entryId'] == decimalEntryId } != null
	}

	def "GET /search?type=solr with a range above the indexed decimal literal should not return the entry"() {
		given: 'a range [100 TO 200] that does not numerically contain 42.5 (out-of-range sanity check)'
		def query = DECIMAL_FIELD + ':[100 TO 200]'

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams([type: 'solr', query: query]))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		def results = respJson['resource']['children'].collect()
		results.find { it['entryId'] == decimalEntryId } == null
	}

	def "GET /search?type=solr should still index an entry whose xsd:double literal has a non-numeric value"() {
		// The malformed entry's decimal literal throws NumberFormatException during indexing and is
		// skipped; the entry must still be indexed. We locate it via its plain-string marker field,
		// proving the bad literal did not abort indexing of the whole document.
		given:
		def query = MALFORMED_MARKER_FIELD + ':' + MALFORMED_MARKER_VALUE

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams([type: 'solr', query: query]))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		def results = respJson['resource']['children'].collect()
		results.find { it['entryId'] == malformedDecimalEntryId } != null
	}

	def "GET /search?type=solr&syndication=rss_2.0 as guest should return empty syndication feed"() {
		when:
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0', '')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == null
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() == 3

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Syndication feed of search'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0'

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'Syndication feed containing max 50 items'

		channelNode['item'].size() == 0
	}

	def "GET /search?type=solr&syndication=rss_2.0 as admin should return syndication feed for the entry"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 3

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Syndication feed of search'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0'

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'Syndication feed containing max 50 items'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['title'].size() == 1
		def itemTitleNode = channelItemNode['title'][0] as Node
		itemTitleNode.attributes().size() == 0
		itemTitleNode.value().size() == 1
		// when the lang param is not given in the request, then it defaults to "en"
		itemTitleNode.value()[0] == 'local metadata title explicitly in EN'

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		// when the lang param is not given in the request, then it defaults to "en"
		itemDescriptionNode.value()[0] == 'local metadata description explicitly in EN'

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /search?type=solr&syndication=rss_2.0&lang=en should return syndication feed with values explicitly in English"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0&lang=en')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 3

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Syndication feed of search'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0&lang=en'

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'Syndication feed containing max 50 items'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['title'].size() == 1
		def itemTitleNode = channelItemNode['title'][0] as Node
		itemTitleNode.attributes().size() == 0
		itemTitleNode.value().size() == 1
		itemTitleNode.value()[0] == 'local metadata title explicitly in EN'

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == 'local metadata description explicitly in EN'

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /search?type=solr&syndication=rss_2.0&lang=pl should return syndication feed with values explicitly in Polish"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0&lang=pl')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 3

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Syndication feed of search'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0&lang=pl'

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'Syndication feed containing max 50 items'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['title'].size() == 1
		def itemTitleNode = channelItemNode['title'][0] as Node
		itemTitleNode.attributes().size() == 0
		itemTitleNode.value().size() == 1
		itemTitleNode.value()[0] == 'lokalne metadane tytułsearch jawnie po polsku'

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == 'lokalne metadane opissearch jawnie po polsku'

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /search?type=solr&syndication=rss_2.0&urltemplate=test123 should return syndication feed with links based on a URL template"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0&urltemplate=test123')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode['link'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == 'http://localhost?cid=searchContextId&eid=searchEntryId&euri=http%3A%2F%2Flocalhost%3A8181%2Fstore%2FsearchContextId%2Fentry%2FsearchEntryId&ruri=http%3A%2F%2Flocalhost%3A8181%2Fstore%2FsearchContextId%2Fresource%2FsearchEntryId'
	}

	@Unroll
	def "GET /search?type=sparql with invalid query '#invalidQuery' should be rejected as invalid SPARQL predicate"() {
		given:
		def queryParams = [type: 'sparql', query: invalidQuery]

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.errorStream.text)
		respJson['error'] != null
		respJson['error'].toString().contains('Invalid SPARQL predicate')

		where:
		invalidQuery            | _
		' ?p '                  | _ // SPARQL variable — matches all triples
		'?p . ?p ?q ?r . #'     | _ // triple pattern break injection
		'?p } UNION { ?x ?y'    | _ // UNION injection via curly braces
		'_:blankNode'           | _ // blank node syntax
		'dc:title.'             | _ // trailing dot — SPARQL statement terminator
		'dc:title-'             | _ // trailing hyphen
		'<http://a> ?y . ?x ?q' | _ // IRI followed by injection
		'; DROP'                | _ // semicolon injection
	}

	def "GET /search?type=sparql with valid full IRI predicate should return results"() {
		given:
		def queryParams = [type: 'sparql', query: '<http://purl.org/dc/terms/title>']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] > 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		results.size() > 0
		results[0]['metadata'] != null
	}

	def "GET /search?type=sparql&query=dc:title as guest should respond with Not Found 404"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		// SearchService re-throws core AuthorizationException unchanged; AppExceptionHandler maps anonymous to 404 (CWE-204)
		conn.getResponseCode() == HTTP_NOT_FOUND
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.errorStream.text)
		respJson['error'] != null
		respJson['error'].toString().contains('Not Found')
	}

	def "GET /search?type=sparql&query=dc:title as admin should return entries json response with entries having 'dc:title' predicate"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] > 1
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		def searchTestEntry = results.find { it['entryId'] == entryId }
		searchTestEntry != null
		searchTestEntry['metadata'] != null
		def metadataMap = (searchTestEntry['metadata'] as Map).values()
		metadataMap.size() == 1
		def metadata = (metadataMap[0] as Map)
		metadata[NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitle = metadata[NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitle.size() == 3
		dcTitle.find { it['lang'] == 'en' && it['value'] == 'local metadata title explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&rdfFormat=ld+json should return ld+json response with entries having 'dc:title' predicate"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', rdfFormat: 'application/ld+json']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] > 1
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		def searchTestEntry = results.find { it['entryId'] == entryId }
		searchTestEntry != null
		searchTestEntry['metadata'] != null
		searchTestEntry['metadata']['@graph'] != null
		def metadataMap = searchTestEntry['metadata']['@graph'].collect()
		metadataMap.size() == 1
		def metadata = (metadataMap[0] as Map)
		metadata['dcterms:title'] != null
		def dcTitle = metadata['dcterms:title'].collect()
		dcTitle.size() == 3
		dcTitle.find { it['@value'] == 'local metadata title explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&syndication=rss_2.0 should return well-formed RSS feed (curie predicate smoke test)"() {
		// Smoke-tests the SPARQL curie+syndication code path. Does not assert specific item content
		// because the dc:title query is repository-wide and not isolated to this IT's entries —
		// the marker-predicate tests below cover content assertions.
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml['channel'].size() == 1
		def channelNode = respXml['channel'][0] as Node
		channelNode['title'][0]?.value()?[0] == 'Syndication feed of search'
		// SearchIT populates 2 dc:title-bearing entries in setupSpec, so both must appear in the feed.
		channelNode['item'].size() >= 2
		// A curie-resolution regression could return items with the namespace declared but empty
		// <title> elements; assert at least one item has a non-empty title.
		channelNode['item']['title'].any { Node n -> (n.value()?[0] as String)?.trim() }
	}

	def "GET /search?type=sparql with test-only marker predicate, syndication=rss_2.0 should return rss feed with this IT's entries, defaulting to explicit English text"() {
		given:
		// Narrow to SearchIT's own entries via the marker predicate — avoids the 50-item feed
		// window being exhausted by dc:title-bearing entries from other ITs.
		def queryParams = [type: 'sparql', query: '<' + MARKER_PREDICATE_IRI + '>', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 3

		channelNode['title'].size() == 1
		with(channelNode['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		channelNode['link'].size() == 1
		with(channelNode['link'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == EntryStoreClient.baseUrl + '/search?type=sparql&query=%3Chttp%3A%2F%2Fexample.org%2Fns%2FsearchIT-marker%3E&syndication=rss_2.0'
		}

		channelNode['description'].size() == 1
		with(channelNode['description'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		// The marker predicate is unique to SearchIT's 2 entries.
		channelNode['item'].size() == 2
		def entry1Item = channelNode['item'].find { Node item ->
			(item['link'][0]?.value()?[0] as String) == EntryStoreClient.baseUrl + '/' + contextId + '/resource/searchEntryId'
		} as Node
		entry1Item != null
		entry1Item['title'][0].value()[0] == 'local metadata title explicitly in EN'
		entry1Item['description'][0].value()[0] == 'local metadata description explicitly in EN'
	}

	def "GET /search?type=sparql with test-only marker predicate, syndication=atom_1.0 should return atom feed with this IT's entries, defaulting to explicit English text"() {
		given:
		def queryParams = [type: 'sparql', query: '<' + MARKER_PREDICATE_IRI + '>', syndication: 'atom_1.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns'].toString().contains('Atom')
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 3

		respXml['title'].size() == 1
		with(respXml['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		respXml['link'].size() == 1
		with(respXml['link'][0] as Node) {
			attributes().size() == 2
			attribute('href') == EntryStoreClient.baseUrl + '/search?type=sparql&query=%3Chttp%3A%2F%2Fexample.org%2Fns%2FsearchIT-marker%3E&syndication=atom_1.0'
			value().size() == 0
		}

		respXml['subtitle'].size() == 1
		with(respXml['subtitle'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		respXml['entry'].size() == 2
		def entry1AtomEntry = respXml['entry'].find { Node e ->
			(e['link'][0] as Node).attribute('href') == EntryStoreClient.baseUrl + '/' + contextId + '/resource/searchEntryId'
		} as Node
		entry1AtomEntry != null
		entry1AtomEntry['title'][0].value()[0] == 'local metadata title explicitly in EN'
		entry1AtomEntry['summary'][0].value()[0] == 'local metadata description explicitly in EN'
	}

	def "GET /search?type=sparql with test-only marker predicate, syndication=atom_1.0&lang=pl should return atom feed with this IT's entries, with values explicitly in Polish"() {
		given:
		def queryParams = [type: 'sparql', query: '<' + MARKER_PREDICATE_IRI + '>', syndication: 'atom_1.0', lang: 'pl']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns'].toString().contains('Atom')
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 3

		respXml['title'].size() == 1
		with(respXml['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		respXml['link'].size() == 1
		with(respXml['link'][0] as Node) {
			attributes().size() == 2
			attribute('href') == EntryStoreClient.baseUrl + '/search?type=sparql&query=%3Chttp%3A%2F%2Fexample.org%2Fns%2FsearchIT-marker%3E&syndication=atom_1.0&lang=pl'
			value().size() == 0
		}

		respXml['subtitle'].size() == 1
		with(respXml['subtitle'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		respXml['entry'].size() == 2
		def entry1PlAtomEntry = respXml['entry'].find { Node e ->
			(e['link'][0] as Node).attribute('href') == EntryStoreClient.baseUrl + '/' + contextId + '/resource/searchEntryId'
		} as Node
		entry1PlAtomEntry != null
		entry1PlAtomEntry['title'][0].value()[0] == 'lokalne metadane tytułsearch jawnie po polsku'
		// Only entry 1 has a Polish dc:description; entry 2 has only English, so its summary is absent or "Missing description".
		entry1PlAtomEntry['summary'][0].value()[0] == 'lokalne metadane opissearch jawnie po polsku'
	}

	def "GET /search?type=sparql&query=dc:title&syndication=random-string as guest should respond with Not Found 404"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'random-string']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		// SearchService re-throws core AuthorizationException unchanged; AppExceptionHandler maps anonymous to 404 (CWE-204)
		conn.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /search?type=sparql&query=dc:title&syndication=random-string as admin should return BAD-REQUEST 400 due to invalid syndication format"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'random-string']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		resp['error'] == 'Invalid syndication feed type: \'random-string\''
	}

	def "GET /search?type=sparql&query=dc&syndication=rss_2.0 as guest should return BAD-REQUEST 400 due to short query"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		// The constraint message alone: the leading "findEntriesSparql.query:" that
		// ConstraintViolationException.getMessage() prepends handed the controller method name to
		// anonymous callers.
		resp['error'] == '\'query\' param length must be minimum 3'
	}

	def "GET /search?type=sparql&query=dc&syndication=rss_2.0 as admin should return BAD-REQUEST 400 due to short query"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		// The constraint message alone: the leading "findEntriesSparql.query:" that
		// ConstraintViolationException.getMessage() prepends handed the controller method name to
		// anonymous callers.
		resp['error'] == '\'query\' param length must be minimum 3'
	}

	def "GET /search?type=solr&syndication=rss_2.0 with X-Forwarded headers should use configured base URL, not forwarded host"() {
		given:
		def extraHeaders = [
			'X-Forwarded-Proto': 'https',
			'X-Forwarded-Host' : 'public.example.com',
			'X-Forwarded-Port' : '443'
		]

		when:
		def resourceConn = EntryStoreClient.getRequest(
				'/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0',
				'admin', 'application/rss+xml', extraHeaders)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)

		def channelNode = respXml['channel'][0] as Node
		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0'
		!channelLinkNode.value()[0].toString().contains('public.example.com')
	}

	// ---------- ENTRYSTORE-1010: input validation specs ----------

	def "GET /search?type=solr with overlong 'query' should reply with Bad Request 400"() {
		given: 'a query parameter exceeding the configured 1024-char cap'
		def oversize = 'a' * 1025

		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=' + oversize, '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'query'")
	}

	def "GET /search?type=solr with 'sort' on a non-allowlisted field should reply with Bad Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&sort=evilField+desc', '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'sort'")
	}

	def "GET /search?type=solr with 'sort' on an allowlisted field should return search results"() {
		when:
		def conn = EntryStoreClient.getRequest(
			'/search?type=solr&query=description.pl:opissearch&sort=modified+desc,title.en+asc')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
	}

	def "GET /search?type=solr with too many filter queries should reply with Bad Request 400"() {
		given: '17 comma-separated FQs (cap is 16)'
		def fqs = (1..17).collect { "rdfType:Type${it}" }.join(',')

		when:
		def conn = EntryStoreClient.getRequest(
			'/search' + convertMapToQueryParams([type: 'solr', query: 'description.pl:opissearch', filterQuery: fqs]), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'filterQuery'")
	}

	def "GET /search?type=solr with overlong combined 'filterQuery' should reply with Bad Request 400"() {
		given: 'a single FQ longer than the 1024-char cap'
		def oversize = 'rdfType:' + ('a' * 1025)

		when:
		def conn = EntryStoreClient.getRequest(
			'/search' + convertMapToQueryParams([type: 'solr', query: 'description.pl:opissearch', filterQuery: oversize]), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'filterQuery'")
	}

	def "GET /search?type=solr with 'facetFields' on a non-allowlisted field should reply with Bad Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest(
			'/search?type=solr&query=description.pl:opissearch&facetFields=secret_field', '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'facetFields'")
	}

	def "GET /search?type=solr with dynamic-family 'facetFields' (metadata.predicate.uri.*) should return search results"() {
		when:
		def conn = EntryStoreClient.getRequest(
			'/search?type=solr&query=description.pl:opissearch&facetFields=metadata.predicate.uri.deadbeef')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
	}

	@Unroll
	def "GET /search?type=solr with regex-shaped facetMatches '#matches' should reply with Bad Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest(
			'/search' + convertMapToQueryParams([type: 'solr', query: 'description.pl:opissearch',
											   facetFields: 'rdfType', facetMatches: matches]), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		conn.errorStream.text.contains("'facetMatches'")

		where:
		matches << ['.*', '(a|b)+', 'foo bar', 'a' * 65]
	}

	def "GET /search?type=solr with literal facetMatches should return search results"() {
		when:
		def conn = EntryStoreClient.getRequest(
			'/search?type=solr&query=description.pl:opissearch&facetFields=rdfType&facetMatches=abc-1')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
	}
}
