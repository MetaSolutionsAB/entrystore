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

import com.github.tomakehurst.wiremock.stubbing.StubMapping
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.put
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_OK

/**
 * Runs pipelines with the rowstore transform through {@code POST /{context-id}/execute} against a RowStore stubbed
 * by the shared WireMock server, which {@code entrystore.rowstore.url} points to.
 */
class RowStoreTransformIT extends BaseSpec {

	static final String CONTEXT_ID = 'rowstore-transform-it'
	static final String DATASETS_PATH = '/rowstore/datasets'
	static final String PIPELINE_RESULT = NameSpaceConst.ES_TERMS + 'PipelineResult'
	static final String PIPELINE = NameSpaceConst.ES_TERMS + 'pipeline'
	static final String PIPELINE_DATA = NameSpaceConst.ES_TERMS + 'pipelineData'

	List<StubMapping> stubs = []

	def setupSpec() {
		getOrCreateContext([contextId: CONTEXT_ID, name: 'context for rowstore transform'])
	}

	def cleanup() {
		stubs.each { wireMockServer.removeStub(it) }
	}

	def "create should upload the source CSV to RowStore and return a PipelineResult entry referencing the dataset"() {
		given:
		def csv = 'name,count\nfoo,1\n'
		def datasetPath = DATASETS_PATH + '/' + uniqueId()
		def datasetUrl = 'http://localhost:' + wireMockServer.port() + datasetPath
		stub(post(urlPathEqualTo(DATASETS_PATH))
			.willReturn(aResponse().withStatus(202).withHeader('Location', datasetUrl)))
		def pipelineUri = createRowStorePipeline([action: 'create'])
		def sourceUri = createCsvSource(csv)

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_CREATED
		def result = JSON_PARSER.parseText(connection.inputStream.text)['result']
		result.size() == 1
		def info = entryInfo(result[0].toString())
		values(info, NameSpaceConst.RDF_TYPE) == [NameSpaceConst.TERM_REFERENCE]
		values(info, NameSpaceConst.TERM_RESOURCE) == [datasetUrl]
		values(entryInfoGraph(result[0].toString())[datasetUrl] as Map, NameSpaceConst.RDF_TYPE) == [PIPELINE_RESULT]
		values(info, NameSpaceConst.TERM_EXTERNAL_METADATA) == [datasetUrl + '/info']
		values(info, PIPELINE) == [pipelineUri]
		values(info, PIPELINE_DATA) == [sourceUri]
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(DATASETS_PATH))
			.withHeader('Content-Type', equalTo('text/csv'))
			.withRequestBody(equalTo(csv)))
	}

	def "create should resolve a relative Location against the RowStore URL"() {
		given:
		def datasetPath = DATASETS_PATH + '/' + uniqueId()
		stub(post(urlPathEqualTo(DATASETS_PATH))
			.willReturn(aResponse().withStatus(202).withHeader('Location', datasetPath)))
		def pipelineUri = createRowStorePipeline([action: 'create'])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_CREATED
		def resultUri = JSON_PARSER.parseText(connection.inputStream.text)['result'][0].toString()
		values(entryInfo(resultUri), NameSpaceConst.TERM_RESOURCE) ==
			['http://localhost:' + wireMockServer.port() + datasetPath]
	}

	def "create should default to action create when the pipeline has no action argument"() {
		given:
		def datasetUrl = 'http://localhost:' + wireMockServer.port() + DATASETS_PATH + '/' + uniqueId()
		stub(post(urlPathEqualTo(DATASETS_PATH))
			.willReturn(aResponse().withStatus(202).withHeader('Location', datasetUrl)))
		def pipelineUri = createRowStorePipeline([:])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_CREATED
		def resultUri = JSON_PARSER.parseText(connection.inputStream.text)['result'][0].toString()
		values(entryInfo(resultUri), NameSpaceConst.TERM_RESOURCE) == [datasetUrl]
	}

	def "create should return 400 when RowStore does not accept the dataset"() {
		given:
		stub(post(urlPathEqualTo(DATASETS_PATH)).willReturn(aResponse().withStatus(500)))
		def pipelineUri = createRowStorePipeline([action: 'create'])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(DATASETS_PATH)))
	}

	def "create should return 400 when RowStore accepts the dataset without a Location"() {
		given:
		stub(post(urlPathEqualTo(DATASETS_PATH)).willReturn(aResponse().withStatus(202)))
		def pipelineUri = createRowStorePipeline([action: 'create'])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "create without source entry should return 400 and not contact RowStore"() {
		given:
		def pipelineUri = createRowStorePipeline([action: 'create'])

		when:
		def connection = execute(pipelineUri, null)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(0, anyRequestedFor(anyUrl()))
	}

	def "replace should PUT the source CSV to the dataset and point pipelineData to the new source only"() {
		given:
		def dataset = createDataset()
		def csv = 'name,count\nbar,2\n'
		stub(put(urlPathEqualTo(dataset.path)).willReturn(aResponse().withStatus(200)))
		def pipelineUri = createRowStorePipeline([action: 'replace', datasetURL: dataset.url])
		def newSourceUri = createCsvSource(csv)

		when:
		def connection = execute(pipelineUri, newSourceUri)

		then:
		connection.getResponseCode() == HTTP_CREATED
		JSON_PARSER.parseText(connection.inputStream.text)['result'] == [dataset.entryUri]
		values(entryInfo(dataset.entryUri), PIPELINE_DATA) == [newSourceUri]
		wireMockServer.verify(1, putRequestedFor(urlPathEqualTo(dataset.path))
			.withHeader('Content-Type', equalTo('text/csv'))
			.withRequestBody(equalTo(csv)))
	}

	def "append should POST the source CSV to the dataset and add the new source to pipelineData"() {
		given:
		def dataset = createDataset()
		def csv = 'name,count\nbaz,3\n'
		stub(post(urlPathEqualTo(dataset.path)).willReturn(aResponse().withStatus(200)))
		def pipelineUri = createRowStorePipeline([action: 'append', datasetURL: dataset.url])
		def newSourceUri = createCsvSource(csv)

		when:
		def connection = execute(pipelineUri, newSourceUri)

		then:
		connection.getResponseCode() == HTTP_CREATED
		JSON_PARSER.parseText(connection.inputStream.text)['result'] == [dataset.entryUri]
		values(entryInfo(dataset.entryUri), PIPELINE_DATA) as Set == [dataset.sourceUri, newSourceUri] as Set
		wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(dataset.path))
			.withHeader('Content-Type', equalTo('text/csv'))
			.withRequestBody(equalTo(csv)))
	}

	def "replace should return 400 and keep pipelineData when RowStore rejects the update"() {
		given:
		def dataset = createDataset()
		stub(put(urlPathEqualTo(dataset.path)).willReturn(aResponse().withStatus(500)))
		def pipelineUri = createRowStorePipeline([action: 'replace', datasetURL: dataset.url])
		def newSourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, newSourceUri)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		values(entryInfo(dataset.entryUri), PIPELINE_DATA) == [dataset.sourceUri]
	}

	def "replace without datasetURL argument should return 400 and not contact RowStore"() {
		given:
		def pipelineUri = createRowStorePipeline([action: 'replace'])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(0, anyRequestedFor(anyUrl()))
	}

	def "replace for a dataset without PipelineResult entry should return 400 and not contact RowStore"() {
		given:
		def unknownDatasetUrl = 'http://localhost:' + wireMockServer.port() + DATASETS_PATH + '/' + uniqueId()
		def pipelineUri = createRowStorePipeline([action: 'replace', datasetURL: unknownDatasetUrl])
		def sourceUri = createCsvSource('a,b\n1,2\n')

		when:
		def connection = execute(pipelineUri, sourceUri)

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
		wireMockServer.verify(0, anyRequestedFor(anyUrl()))
	}

	def "setalias without source entry should PUT the alias as JSON array to the dataset's aliases"() {
		given:
		def dataset = createDataset()
		stub(put(urlPathEqualTo(dataset.path + '/aliases')).willReturn(aResponse().withStatus(200)))
		def pipelineUri = createRowStorePipeline([action: 'setalias', datasetURL: dataset.url, alias: 'my-alias'])

		when:
		def connection = execute(pipelineUri, null)

		then:
		connection.getResponseCode() == HTTP_CREATED
		JSON_PARSER.parseText(connection.inputStream.text)['result'] == [dataset.entryUri]
		values(entryInfo(dataset.entryUri), PIPELINE_DATA) == [dataset.sourceUri]
		wireMockServer.verify(1, putRequestedFor(urlPathEqualTo(dataset.path + '/aliases'))
			.withHeader('Content-Type', equalTo('application/json'))
			.withRequestBody(equalToJson('["my-alias"]')))
	}

	def "setalias with empty alias should DELETE the dataset's aliases"() {
		given:
		def dataset = createDataset()
		stub(delete(urlPathEqualTo(dataset.path + '/aliases')).willReturn(aResponse().withStatus(204)))
		def pipelineUri = createRowStorePipeline([action: 'setalias', datasetURL: dataset.url, alias: ''])

		when:
		def connection = execute(pipelineUri, null)

		then:
		connection.getResponseCode() == HTTP_CREATED
		JSON_PARSER.parseText(connection.inputStream.text)['result'] == [dataset.entryUri]
		wireMockServer.verify(1, deleteRequestedFor(urlPathEqualTo(dataset.path + '/aliases')))
	}

	private static String uniqueId() {
		return UUID.randomUUID().toString()
	}

	private void stub(def mappingBuilder) {
		stubs << wireMockServer.stubFor(mappingBuilder)
	}

	/**
	 * Creates a pipeline entry with one rowstore transform whose arguments are the given key-value pairs.
	 */
	private static String createRowStorePipeline(Map<String, String> arguments) {
		def pipelineId = createEntry(CONTEXT_ID, [graphtype: 'pipeline'])
		def argumentsTurtle = arguments.collect { key, value ->
			"es:transformArgument [ es:transformArgumentKey \"${key}\" ; es:transformArgumentValue \"${value}\" ] ;"
		}.join('\n\t')
		def pipelineTurtle = """\
			@prefix es: <http://entrystore.org/terms/> .
			[] es:transform [
				${argumentsTurtle}
				es:transformType "rowstore"
			] .
			""".stripIndent()
		def connection = EntryStoreClient.putRequest('/' + CONTEXT_ID + '/resource/' + pipelineId,
			pipelineTurtle, 'admin', 'text/turtle')
		assert connection.getResponseCode() >= 200 && connection.getResponseCode() < 300
		return entryUri(pipelineId)
	}

	private static String createCsvSource(String csv) {
		def sourceId = createEntry(CONTEXT_ID, [:])
		def file = File.createTempFile('rowstore-it-source', '.csv')
		file.deleteOnExit()
		file.text = csv
		def connection = EntryStoreClient.putRequestFile('/' + CONTEXT_ID + '/resource/' + sourceId, file,
			'admin', 'text/csv')
		assert connection.getResponseCode() >= 200 && connection.getResponseCode() < 300
		return entryUri(sourceId)
	}

	/**
	 * Runs a create pipeline against a stubbed RowStore so that the dataset has a PipelineResult entry.
	 *
	 * @return the dataset's url and path in RowStore, its PipelineResult entry URI and the source entry URI
	 */
	private Map<String, String> createDataset() {
		def datasetPath = DATASETS_PATH + '/' + uniqueId()
		def datasetUrl = 'http://localhost:' + wireMockServer.port() + datasetPath
		def createStub = wireMockServer.stubFor(post(urlPathEqualTo(DATASETS_PATH))
			.willReturn(aResponse().withStatus(202).withHeader('Location', datasetUrl)))
		def sourceUri = createCsvSource('name,count\nfoo,1\n')
		def connection = execute(createRowStorePipeline([action: 'create']), sourceUri)
		assert connection.getResponseCode() == HTTP_CREATED
		def entryUri = JSON_PARSER.parseText(connection.inputStream.text)['result'][0].toString()
		wireMockServer.removeStub(createStub)
		wireMockServer.resetRequests()
		return [url: datasetUrl, path: datasetPath, entryUri: entryUri, sourceUri: sourceUri]
	}

	private static HttpURLConnection execute(String pipelineUri, String sourceUri) {
		def body = sourceUri == null ? [pipeline: pipelineUri] : [pipeline: pipelineUri, source: sourceUri]
		return EntryStoreClient.postRequest('/' + CONTEXT_ID + '/execute', JsonOutput.toJson(body), 'admin',
			'application/json')
	}

	private static String entryUri(String entryId) {
		return EntryStoreClient.baseUrl + '/' + CONTEXT_ID + '/entry/' + entryId
	}

	/**
	 * @return the entry information graph as RDF/JSON
	 */
	private static Map entryInfoGraph(String entryUri) {
		def entryId = entryUri.substring(entryUri.lastIndexOf('/') + 1)
		def connection = EntryStoreClient.getRequest('/' + CONTEXT_ID + '/entry/' + entryId)
		assert connection.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(connection.inputStream.text)['info'] as Map
	}

	/**
	 * @return the RDF/JSON statements about the entry from its entry information graph
	 */
	private static Map entryInfo(String entryUri) {
		return entryInfoGraph(entryUri)[entryUri] as Map
	}

	private static List<String> values(Map statements, String predicate) {
		return statements[predicate].collect { it['value'].toString() }
	}
}
