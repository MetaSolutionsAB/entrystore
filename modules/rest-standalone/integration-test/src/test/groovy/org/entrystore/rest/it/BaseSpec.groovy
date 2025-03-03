package org.entrystore.rest.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.awaitility.core.ConditionEvaluationLogger
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.springboot.EntryStoreApplicationStandaloneSpringBoot
import org.slf4j.LoggerFactory
import spock.lang.Specification

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8
import static org.awaitility.Awaitility.await

abstract class BaseSpec extends Specification {

	def static log = LoggerFactory.getLogger(this.class)
	def static JSON_PARSER = new JsonSlurper()

	def static appStarted = false

	def setupSpec() {
		if (!appStarted) {
			def args = ['-c', 'file:src/test/resources/entrystore-it.properties', '-p', EntryStoreClient.port.toString()] as String[]
			log.info('Starting EntryStoreApp')
			appStarted = true
			EntryStoreApplicationStandaloneSpringBoot.main(args)
		} else {
			log.info('EntryStoreApp already started')
		}
	}

	/**
	 * Fetches requested context by given ID, if it does not exist then creates a new context with that ID.
	 * Expects contextId to be present in the `data` argument.
	 * @param data Map with required param `contextId`, optional param `name`
	 * @return
	 */
	def getOrCreateContext(Map data) {
		assert data['contextId'] != null
		assert data['contextId'].toString().length() > 0
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + data['contextId'])
		if (connection.getResponseCode() == HTTP_NOT_FOUND) {
			createContext(data)
		}
	}

	/**
	 *
	 * @param data Map with data that will be sent in the request URL to the endpoint
	 * @return ID of the created group
	 */
	def createContext(Map data) {
		def connection = EntryStoreClient.postRequest('/_principals/groups' + convertMapToQueryParams(data))
		assert connection.getResponseCode() == HTTP_CREATED
		return connection.getHeaderField('Location').find(/\/_principals\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
	}

	/**
	 *
	 * @param data a key-val map to be converted
	 * @return a string in form of "?key1=value1&key2=value2&..."; or empty string for empty map
	 */
	def static convertMapToQueryParams(Map<String, String> data) {
		return (data.size() == 0) ? '' : '?' + data.collect { k, v -> k + '=' + URLEncoder.encode(v, UTF_8) }.join('&')
	}

	/**
	 * Fetches requested entry by given ID, if it does not exist then creates a new entry with that ID.
	 * Expects "id" key to be present in the `params` argument.
	 *
	 * @param contextId under which to create the entry
	 * @param params key-value map which will be send in the request URL, e.g. [entrytype: 'link', resource: '...Url...', id: 'entryId']
	 * @param body key-value map which will be send in the request body, e.g. [resource: 'someText']
	 * @return entry ID from the response
	 */
	def getOrCreateEntry(String contextId, Map params, Map body = [:]) {
		def entryId = params['id']
		assert entryId != null
		assert entryId.toString().length() > 0
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		if (entryConn.getResponseCode() == HTTP_OK) {
			entryConn.getContentType().contains('application/json')
			def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
			entryRespJson['entryId'] != null
			return entryRespJson['entryId'].toString()
		} else if (entryConn.getResponseCode() == HTTP_NOT_FOUND) {
			return createEntry(contextId, params, body)
		} else {
			assert false // unexpected response
		}
	}

	/**
	 *
	 * @param contextId under which to create the entry
	 * @param params key-value map which will be send in the request URL, e.g. [entrytype: 'link', resource: '...Url...', id: 'entryId']
	 * @param body key-value map which will be send in the request body, e.g. [resource: 'someText']
	 * @return created entry ID
	 */
	def createEntry(String contextId, Map params, Map body = [:]) {
		def bodyJson = JsonOutput.toJson(body)
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), bodyJson)
		assert connection.getResponseCode() == HTTP_CREATED
		assert connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		assert responseJson['entryId'] != null
		return responseJson['entryId'].toString()
	}

	Integer getSolrQueueSize() {
		def connection = EntryStoreClient.getRequest('/management/status?extended=true')
		assert connection.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		return (Integer) responseJson['solr']['postQueueSize']
	}

	def waitForSolrProcessing() {
		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.pollInterval(10, TimeUnit.MILLISECONDS)
			.atMost(30, TimeUnit.SECONDS)
			// separate supplier and predicate for better await logging
			.until({ getSolrQueueSize() }, { it == 0 })
	}

}
