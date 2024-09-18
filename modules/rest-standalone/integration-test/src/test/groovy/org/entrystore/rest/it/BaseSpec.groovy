package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_CREATED

abstract class BaseSpec extends Specification {

	static client = new EntryStoreClient()

	def setupSpec() {
		def args = ['-c', 'file:src/test/resources/entrystore-it.properties', '-p', EntryStoreClient.port.toString()] as String[]
		EntryStoreApplicationStandaloneJetty.main(args)
	}

	/**
	 *
	 * @param data Map with data that will be sent as body to the endpoint
	 * @return ID of the created context
	 */
	def createContext(Map data) {
		def body = JsonOutput.toJson(data)
		def connection = client.postRequest('/_principals/groups', body, 'admin')
		assert connection.getResponseCode() == HTTP_CREATED
		return connection.getHeaderField('Location').find(/\/_principals\/entry\/(\d+)$/) { match, id -> id } as Integer
	}
}
