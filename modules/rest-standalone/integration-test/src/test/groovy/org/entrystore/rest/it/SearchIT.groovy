package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.time.Year

import static java.net.HttpURLConnection.HTTP_OK

class SearchIT extends BaseSpec {

	def static contextId = 'searchContextId'
	def static entryId = ''

	def setupSpec() {
		getOrCreateContext([contextId: contextId])
		// create local String entry
		def someText = 'Some text'
		def params = [id: 'searchEntryId', graphtype: 'string']
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
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
								value: 'lokalne metadane tytuł jawnie po polsku',
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
						]
					]]]

		entryId = getOrCreateEntry(contextId, params, body)
		assert entryId.length() > 0
		Thread.sleep(100)
		waitForSolrProcessing()
		// Solr needs even more time to finish processing
		Thread.sleep(1500)
	}

	def "GET /search?type=solr with complex Solr query should be properly decoded and return search results"() {
		when:
		// fetch syndication feed
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch') //title.pl:tytuł

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.getInputStream().text)
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
																   value: 'lokalne metadane tytuł jawnie po polsku',
																   lang : 'pl'])

	}

	def "GET /search?type=solr&syndication=rss_2.0 should return syndication feed for the entry"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0')

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
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
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
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
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
		itemTitleNode.value()[0] == 'lokalne metadane tytuł jawnie po polsku'

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
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
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

}
