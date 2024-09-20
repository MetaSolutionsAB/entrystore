package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_CREATED

abstract class BaseSpec extends Specification {

	static Logger log = LoggerFactory.getLogger(this.class);

	static client = new EntryStoreClient()

	static appStarted = false

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

	/**
	 *
	 * @param data Map with data that will be sent in the request URL to the endpoint
	 * @return ID of the created group
	 */
	def createContext(Map data) {
		def reqParams = (data.size() == 0) ? '' : '?' + data.collect { k, v -> "$k=$v" }.join('&')
		def connection = client.postRequest('/_principals/groups' + reqParams, '', 'admin')
		assert connection.getResponseCode() == HTTP_CREATED
		return connection.getHeaderField('Location').find(/\/_principals\/entry\/(\d+)$/) { match, id -> id } as Integer
	}
}
