package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import org.slf4j.LoggerFactory
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND

abstract class BaseSpec extends Specification {

	def static log = LoggerFactory.getLogger(this.class)

	def static client = new EntryStoreClient()

	def static appStarted = false

	def setupSpec() {
		if (!appStarted) {
			def args = ['-c', 'file:src/test/resources/entrystore-it.properties', '-p', EntryStoreClient.port.toString()] as String[]
			log.info('Starting EntryStoreApp')
			appStarted = true
			EntryStoreApplicationStandaloneJetty.main(args)
		} else {
			log.info('EntryStoreApp already started')
		}
	}

	def getOrCreateContext(Map data) {
		def connection = client.getRequest('/_contexts/entry/' + data['contextId'])
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
		def connection = client.postRequest('/_principals/groups' + convertMapToQueryParams(data))
		assert connection.getResponseCode() == HTTP_CREATED
		return connection.getHeaderField('Location').find(/\/_principals\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
	}

	/**
	 *
	 * @param data a key-val map to be converted
	 * @return a string in form of "?key1=value1&key2=value2&..."; or empty string for empty map
	 */
	def convertMapToQueryParams(Map data) {
		return (data.size() == 0) ? '' : '?' + data.collect { k, v -> "$k=$v" }.join('&')
	}
}
