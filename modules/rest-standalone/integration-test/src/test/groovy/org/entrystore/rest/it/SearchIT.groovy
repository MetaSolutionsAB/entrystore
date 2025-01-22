package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.time.Year

import static java.net.HttpURLConnection.HTTP_OK

class SearchIT extends BaseSpec {

	def static contextId = 'searchContextId'

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
	}

	def "GET /search?type=solr&query=public:true+AND+title:*&syndication=rss_2.0 should return created resources syndication"() {
		given:
		// create local String entry
		def someText = 'Some text'
		def params = [id: 'searchEntryId', graphtype: 'string']
		def body = [resource: someText]
		def entryId = getOrCreateEntry(contextId, params, body)
		assert entryId.length() > 0
		Thread.sleep(100)

		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=public:true+AND+title:*&syndication=rss_2.0')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 6

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Syndication feed of search'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=public:true+AND+title:*&syndication=rss_2.0'

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'Syndication feed containing max 50 items'

		channelNode['item'].size() > 2
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['title'].size() == 1
		def itemDescriptionNode = channelItemNode['title'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == '_principals'

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/_principals'

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}
}
