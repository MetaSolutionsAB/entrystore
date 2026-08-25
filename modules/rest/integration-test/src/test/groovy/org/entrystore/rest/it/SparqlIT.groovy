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

import org.awaitility.core.ConditionEvaluationLogger
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import static java.net.HttpURLConnection.HTTP_NOT_ACCEPTABLE
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE
import static org.awaitility.Awaitility.await

class SparqlIT extends BaseSpec {

	private static final String VALID_QUERY = 'SELECT * WHERE { ?s ?p ?o } LIMIT 1'

	private static final String CONTEXT_ID = 'sparql-it-ctx'
	private static final String CONTEXT_ID_2 = 'sparql-it-ctx-2'
	private static final String PUBLIC_TITLE = 'sparql-it-public-marker'
	private static final String PRIVATE_TITLE = 'sparql-it-private-marker'
	private static final String CTX2_PUBLIC_TITLE = 'sparql-it-ctx2-public-marker'

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID])
		getOrCreateContext([contextId: CONTEXT_ID_2])

		def guestUri = EntryStoreClient.baseUrl + '/_principals/resource/_guest'
		// Create the private entry first so the segregation assertion below cannot pass spuriously
		// before its target has been seen by the submitter. PublicRepository.updateEntries skips
		// entries without guest-read on every cycle they are processed, so once the public entry
		// is queryable the private entry has been considered (and rejected) at least once,
		// regardless of whether the three entries shipped in one or several batches.
		createSegregationTestEntry(CONTEXT_ID, PRIVATE_TITLE, null)
		createSegregationTestEntry(CONTEXT_ID, PUBLIC_TITLE, guestUri)
		createSegregationTestEntry(CONTEXT_ID_2, CTX2_PUBLIC_TITLE, guestUri)

		// Wait until the public entries become queryable. PublicRepository.EntrySubmitter is signalled
		// directly by enqueue()/remove() (wait/notify on queueSignal), so the latency from createEntry
		// returning to the entry being queryable is dominated by RDF4J writes — sub-second in steady
		// state. 5 s is a tight-but-honest ceiling that catches real regressions while leaving margin
		// for cold-JVM warmup and GC pauses on a contended CI runner.
		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.atMost(5, TimeUnit.SECONDS)
			.pollInterval(500, TimeUnit.MILLISECONDS)
			.until { sparqlContainsTitle(PUBLIC_TITLE) && sparqlContainsTitle(CTX2_PUBLIC_TITLE) }
	}

	private static void createSegregationTestEntry(String contextId, String title, String guestUriOrNull) {
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def newMetadataIri = EntryStoreClient.baseUrl + '/' + contextId + '/metadata/_newId'

		def params = [entrytype: 'link', resource: 'https://example.org/' + title]
		def body = createTitleMetadataBody(newResourceIri, title)
		if (guestUriOrNull != null) {
			// Grant guest ReadMetadata so the entry is indexed with public:true in Solr
			body.info = [(newMetadataIri): [(NameSpaceConst.TERM_READ): [[type: 'uri', value: guestUriOrNull]]]]
		}
		createEntry(contextId, params, body)
	}

	private static boolean sparqlContainsTitle(String title) {
		// Escape SPARQL string-literal metacharacters so a title containing " or \ produces a valid query
		// (rather than a 400 that the helper would silently report as "absent")
		def escaped = title.replace('\\', '\\\\').replace('"', '\\"')
		def query = 'SELECT ?s WHERE { ?s <' + NameSpaceConst.DC_TERM_TITLE + '> "' + escaped + '" } LIMIT 1'
		def conn = EntryStoreClient.getRequest(
			'/sparql' + convertMapToQueryParams([query: query, format: 'application/sparql-results+json']), '')
		def status = conn.getResponseCode()
		// Fail fast on any non-200 — the helper-issued query is statically valid, so a 4xx (parser
		// regression, routing change) or 5xx (server fault) is a real test failure, not a "still
		// indexing" signal. Without this, such regressions surface only as 180 s Awaitility timeouts
		// with the generic "condition was not fulfilled" message.
		if (status >= 400) {
			def errBody = (conn.errorStream != null) ? conn.errorStream.text : ''
			throw new AssertionError("Unexpected ${status} from /sparql while waiting for indexing of '${title}': ${errBody}")
		} else if (status != HTTP_OK) {
			log.info('sparqlContainsTitle({}) saw status {}', title, status)
			return false
		}
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		return !(json['results']['bindings'] as List).isEmpty()
	}

	def "GET /sparql without query param should return Bad-Request 400"() {
		when:
		def conn = EntryStoreClient.getRequest('/sparql', '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /sparql with malformed query should return Bad-Request 400"() {
		given:
		def queryParams = [query: 'this is not sparql {{{']

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		def err = JSON_PARSER.parseText(conn.errorStream.text)
		err['error'] == 'Malformed SPARQL query'
	}

	def "GET /sparql as guest with default Accept should return binary tuple result"() {
		given:
		def queryParams = [query: VALID_QUERY]

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '', '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/x-binary-rdf-results-table')
	}

	def "GET /sparql with format=application/sparql-results+json should return SPARQL JSON"() {
		given:
		def queryParams = [query: VALID_QUERY, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		json['head'] != null
		json['results'] != null
	}

	def "GET /sparql with format=text/csv should return CSV"() {
		given:
		def queryParams = [query: VALID_QUERY, format: 'text/csv']

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('text/csv')
		conn.inputStream.text.startsWith('s,p,o')
	}

	@Unroll
	def "GET /sparql with Accept '#acceptHeader' should respond with Content-Type '#expectedContentType'"() {
		given:
		def queryParams = [query: VALID_QUERY]

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '', acceptHeader)

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains(expectedContentType)

		where:
		acceptHeader                       | expectedContentType
		'application/sparql-results+xml'   | 'application/sparql-results+xml'
		'application/sparql-results+json'  | 'application/sparql-results+json'
		'application/json'                 | 'application/sparql-results+json'
		'application/xml'                  | 'application/sparql-results+xml'
		'text/csv'                         | 'text/csv'
		'*/*'                              | 'application/x-binary-rdf-results-table'
	}

	def "GET /sparql with malformed Accept header should return Bad-Request 400"() {
		given:
		def queryParams = [query: VALID_QUERY]

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '', '!@#$%')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /sparql with Accept of only unsupported types should return Not-Acceptable 406"() {
		given:
		def queryParams = [query: VALID_QUERY]

		when:
		def conn = EntryStoreClient.getRequest(
			'/sparql' + convertMapToQueryParams(queryParams), '', 'text/html, application/rdf+xml')

		then:
		conn.getResponseCode() == HTTP_NOT_ACCEPTABLE
	}

	def "GET /sparql with SERVICE clause should return Bad-Request 400"() {
		given:
		def query = 'SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }'
		def queryParams = [query: query]

		when:
		def conn = EntryStoreClient.getRequest('/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		def err = JSON_PARSER.parseText(conn.errorStream.text)
		err['error'].toString().contains('SERVICE')
	}

	def "GET /{contextId}/sparql with SERVICE clause should also return Bad-Request 400"() {
		given:
		def query = 'SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }'
		def queryParams = [query: query]

		when:
		def conn = EntryStoreClient.getRequest(
			'/' + CONTEXT_ID + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		def err = JSON_PARSER.parseText(conn.errorStream.text)
		err['error'].toString().contains('SERVICE')
	}

	def "POST /sparql form-encoded with output=xml should return SPARQL XML"() {
		given:
		def body = createFormBody([query: VALID_QUERY, output: 'xml'])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+xml')
		conn.inputStream.text.startsWith('<?xml')
	}

	def "POST /sparql form-encoded with output=json should return SPARQL JSON"() {
		given:
		def body = createFormBody([query: VALID_QUERY, output: 'json'])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
	}

	def "POST /sparql form-encoded with output=csv should return CSV"() {
		given:
		def body = createFormBody([query: VALID_QUERY, output: 'csv'])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('text/csv')
	}

	def "POST /sparql form-encoded without output should default to SPARQL JSON"() {
		given:
		def body = createFormBody([query: VALID_QUERY])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
	}

	@Unroll
	def "POST /sparql form-encoded with unknown output='#unknownOutput' should return Bad-Request 400"() {
		given:
		def body = createFormBody([query: VALID_QUERY, output: unknownOutput])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST

		where:
		unknownOutput | _
		'jsom'        | _
		'turtle'      | _
		''            | _
	}

	def "POST /sparql form-encoded without query field should return Bad-Request 400"() {
		given:
		def body = createFormBody([output: 'json'])

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /sparql with body equal to 32KB cap should succeed"() {
		given:
		// HttpUtil.isLargerThan compares with `>`, so size = MAX_POST_REQUEST_SIZE is admitted.
		// Pad VALID_QUERY with a SPARQL line comment up to the cap. Alphanumeric padding inside
		// the comment survives URL-encoding 1-to-1, so the wire length is predictable; the
		// comment runs to end-of-line and does not affect parsing.
		def overhead = createFormBody([query: VALID_QUERY + ' # ']).length()
		def body = createFormBody([query: VALID_QUERY + ' # ' + 'a' * (32 * 1024 - overhead)])
		assert body.length() == 32 * 1024

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
	}

	def "POST /sparql with body 32KB + 1 should return Payload-Too-Large 413"() {
		given:
		// One byte over the cap is the tightest regression guard: a silent change that raises
		// MAX_POST_REQUEST_SIZE would let this slip through.
		def overhead = createFormBody([query: VALID_QUERY + ' # ']).length()
		def body = createFormBody([query: VALID_QUERY + ' # ' + 'a' * (32 * 1024 - overhead + 1)])
		assert body.length() == 32 * 1024 + 1

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_ENTITY_TOO_LARGE
	}

	def "POST /sparql with non-form Content-Type should return Unsupported-Media-Type 415"() {
		given:
		// Pins the form-only contract so a future change adding application/sparql-query support
		// (which would bypass the size check and the `output` parameter mapping) is caught here.
		def body = VALID_QUERY

		when:
		def conn = EntryStoreClient.postRequest('/sparql', body, '', 'application/sparql-query')

		then:
		conn.getResponseCode() == HTTP_UNSUPPORTED_TYPE
	}

	def "POST /sparql with parameters in URL query string should return Bad-Request 400"() {
		given:
		// Defends against the URL-bypass of the 32 KB body cap: a tiny body but the actual query in
		// the URL would otherwise satisfy @RequestParam binding and slip past the cap.
		when:
		def conn = EntryStoreClient.postRequest(
			'/sparql' + convertMapToQueryParams([query: VALID_QUERY]),
			'', '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "GET /{contextId}/sparql with valid query should return 200"() {
		given:
		def queryParams = [query: VALID_QUERY, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		json['head'] != null
		json['results'] != null
	}

	def "GET /{unknownContextId}/sparql must not disclose context existence"() {
		// CWE-204: a 404 here would let an anonymous client enumerate context IDs by observing
		// the status difference between missing and existing-but-private contexts. Both must
		// produce 200 with empty bindings.
		given:
		def queryParams = [query: VALID_QUERY, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest('/no-such-ctx-' + System.currentTimeMillis() + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		(json['results']['bindings'] as List).isEmpty()
	}

	@Unroll
	def "GET /#reservedName/sparql with reserved endpoint name as context-id returns empty result"() {
		// Reserved names cannot become real contexts (ContextService rejects them at creation),
		// so the named-graph filter resolves to a synthesised URI that has no triples in the
		// public repo. The post-M7 contract is uniform 200/empty for missing/reserved/private.
		given:
		def queryParams = [query: VALID_QUERY, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest('/' + reservedName + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		(json['results']['bindings'] as List).isEmpty()

		where:
		reservedName | _
		'sparql'     | _
		'search'     | _
		'validator'  | _
		'lookup'     | _
	}

	@Unroll
	def "GET /#systemContext/sparql as guest must not leak principal/context data"() {
		given:
		// _principals and _contexts are real contexts holding user/group/ACL data. resolveNamedGraphUri
		// scopes the query to that context's named graph, AND PublicRepository.updateEntries refuses to
		// mirror entries that lack guest-read — so the named graph for these system contexts is empty
		// in the public repo and the scoped query has nothing to return.
		def queryParams = [query: VALID_QUERY, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest('/' + systemContext + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		(json['results']['bindings'] as List).isEmpty()

		where:
		systemContext | _
		'_principals' | _
		'_contexts'   | _
	}

	def "GET /sparql with conflicting format and Accept should honour format"() {
		given:
		def queryParams = [query: VALID_QUERY, format: 'text/csv']

		when:
		def conn = EntryStoreClient.getRequest(
			'/sparql' + convertMapToQueryParams(queryParams), '', 'application/sparql-results+json')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('text/csv')
	}

	def "GET /sparql segregates public-readable entries from private ones"() {
		expect:
		// PublicRepository.updateEntries skips entries that lack guest-read in their ACL, so by the
		// time PUBLIC_TITLE is queryable the private entry has been seen and rejected on at least
		// one submitter cycle. The fact that setupSpec created the private entry first only ensures
		// the rejection happens *before* this assertion runs, not that all three entries shipped in
		// the same batch.
		sparqlContainsTitle(PUBLIC_TITLE)
		!sparqlContainsTitle(PRIVATE_TITLE)
	}

	@Unroll
	def "GET /#requestedContext/sparql exposes only that context's triples"() {
		given:
		// CONTEXT_ID owns PUBLIC_TITLE; CONTEXT_ID_2 owns CTX2_PUBLIC_TITLE. Both directions of the
		// named-graph filter must hold: each context-scoped endpoint surfaces its own marker and
		// excludes the other's. Asserting only one direction would let a regression that empties
		// every context-scoped query (e.g. wrong IRI in SimpleDataset) pass the negative half.
		def query = 'SELECT ?title WHERE { ?s <' + NameSpaceConst.DC_TERM_TITLE + '> ?title } LIMIT 100'
		def queryParams = [query: query, format: 'application/sparql-results+json']

		when:
		def conn = EntryStoreClient.getRequest(
			'/' + requestedContext + '/sparql' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_OK
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		def titles = (json['results']['bindings'] as List).collect { it['title']['value'] }
		titles.contains(expectedTitle)
		!titles.contains(forbiddenTitle)

		where:
		requestedContext | expectedTitle      | forbiddenTitle
		CONTEXT_ID       | PUBLIC_TITLE       | CTX2_PUBLIC_TITLE
		CONTEXT_ID_2     | CTX2_PUBLIC_TITLE  | PUBLIC_TITLE
	}

	def "POST /{contextId}/sparql form-encoded should run the query against that context's named graph"() {
		given:
		def query = 'SELECT ?title WHERE { ?s <' + NameSpaceConst.DC_TERM_TITLE + '> ?title } LIMIT 100'
		def body = createFormBody([query: query, output: 'json'])

		when:
		def conn = EntryStoreClient.postRequest(
			'/' + CONTEXT_ID + '/sparql', body, '', 'application/x-www-form-urlencoded')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/sparql-results+json')
		def json = JSON_PARSER.parseText(conn.inputStream.text)
		def titles = (json['results']['bindings'] as List).collect { it['title']['value'] }
		titles.contains(PUBLIC_TITLE)
		!titles.contains(CTX2_PUBLIC_TITLE)
	}
}
