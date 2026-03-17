package org.entrystore.rest.it

import org.entrystore.rest.it.util.EntryStoreClient

class ServerHeaderIT extends BaseSpec {

	def 'GET request should include Server header starting with EntryStore/'() {
		when:
		def conn = EntryStoreClient.getRequest('/echo', '', 'text/html')

		then:
		def serverHeader = conn.getHeaderField('Server')
		serverHeader != null
		serverHeader.startsWith('EntryStore/')
	}

	def 'Server header value should contain a version after EntryStore/'() {
		when:
		def conn = EntryStoreClient.getRequest('/search', '', 'application/json')

		then:
		def serverHeader = conn.getHeaderField('Server')
		serverHeader != null
		serverHeader ==~ /EntryStore\/.+/
	}
}
