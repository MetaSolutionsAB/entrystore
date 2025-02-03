package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.time.Year

import static java.net.HttpURLConnection.HTTP_OK

class ResourceSyndicationIT extends BaseSpec {

	def static contextId = '81'
	def static entryId = ''

	def setupSpec() {
		getOrCreateContext([contextId: contextId, name: 'Syndication Test'])
		// create local String entry
		def someText = 'Some text ttt'
		def params = [id: 'syndicationEntryId', graphtype: 'string']
		def newResourceIri = EntryStoreClient.baseUrl + '/' + contextId + '/resource/_newId'
		def body = [resource: someText,
					metadata: [(newResourceIri): [
						(NameSpaceConst.DC_TERM_TITLE)      : [
							[
								type : 'literal',
								value: 'local metadata title implicitly in EN',
							],
							[
								type : 'literal',
								value: 'local metadata title explicitly in EN',
								lang : 'en'
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
								value: 'lokalne metadane opis jawnie po polsku',
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

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0 should return created resource syndication"() {
		when:
		// fetch syndication feed for the context
		// TODO: context in the request needs to have a name (alias) for syndication to not return null values in the text, bug?
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=rss_2.0')

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
		channelNode.value().size() > 2

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Feed of "Syndication Test"'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0&lang=en should return created resource syndication in English"() {
		when:
		// fetch syndication feed for the context
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=rss_2.0&lang=en')

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
		channelNode.value().size() > 2

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Feed of "Syndication Test"'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=rss_2.0&lang=pl should return created resource syndication in Polish"() {
		when:
		// fetch syndication feed for the context
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=rss_2.0&lang=pl')

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
		channelNode.value().size() > 2

		channelNode['title'].size() == 1
		def channelTitleNode = channelNode['title'][0] as Node
		channelTitleNode.attributes().size() == 0
		channelTitleNode.value().size() == 1
		channelTitleNode.value()[0] == 'Feed of "Syndication Test"'

		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.attributes().size() == 0
		channelLinkNode.value().size() == 1
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId

		channelNode['description'].size() == 1
		def channelDescriptionNode = channelNode['description'][0] as Node
		channelDescriptionNode.attributes().size() == 0
		channelDescriptionNode.value().size() == 1
		channelDescriptionNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		channelNode['item'].size() == 1
		def channelItemNode = channelNode['item'][0] as Node
		channelItemNode.attributes().size() == 0
		channelItemNode.value().size() > 4

		channelItemNode['link'].size() == 1
		def itemLinkNode = channelItemNode['link'][0] as Node
		itemLinkNode.attributes().size() == 0
		itemLinkNode.value().size() == 1
		itemLinkNode.value()[0] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId

		channelItemNode['description'].size() == 1
		def itemDescriptionNode = channelItemNode['description'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		itemDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		channelItemNode['dc:date'].size() == 1
		def itemDateNode = channelItemNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=atom_1.0 should return created resource syndication in atom format"() {
		when:
		// fetch syndication feed for the context
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=atom_1.0')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
		respXml.attributes().size() > 0
		respXml.attributes()['xmlns'] == 'http://www.w3.org/2005/Atom'
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 2

		respXml['title'].size() == 1
		def titleNode = respXml['title'][0] as Node
		titleNode.attributes().size() == 0
		titleNode.value().size() == 1
		titleNode.value()[0] == 'Feed of "Syndication Test"'

		respXml['link'].size() == 1
		def linkNode = respXml['link'][0] as Node
		linkNode.attributes().size() == 2
		linkNode.attributes()['rel'] == 'alternate'
		linkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId
		linkNode.value().size() == 0

		respXml['subtitle'].size() == 1
		def subtitleNode = respXml['subtitle'][0] as Node
		subtitleNode.attributes().size() == 0
		subtitleNode.value().size() == 1
		subtitleNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		respXml['entry'].size() == 1
		def entryNode = respXml['entry'][0] as Node
		entryNode.attributes().size() == 0
		entryNode.value().size() > 3

		entryNode['title'].size() == 1
		def itemDescriptionNode = entryNode['title'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		// title with explicit english lang is chosen
		itemDescriptionNode.value()[0] == 'local metadata title explicitly in EN'

		entryNode['summary'].size() == 1
		def summaryDescriptionNode = entryNode['summary'][0] as Node
		summaryDescriptionNode.attributes().size() == 1
		summaryDescriptionNode.attributes()['type'] == 'text'
		summaryDescriptionNode.value().size() == 1
		summaryDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		entryNode['link'].size() == 1
		def itemLinkNode = entryNode['link'][0] as Node
		itemLinkNode.attributes().size() == 2
		itemLinkNode.attributes()['rel'] == 'alternate'
		itemLinkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId
		itemLinkNode.value().size() == 0

		entryNode['dc:date'].size() == 1
		def itemDateNode = entryNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=atom_1.0&lang=en should return created resource syndication in atom format in English"() {
		when:
		// fetch syndication feed for the context
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=atom_1.0&lang=en')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
		respXml.attributes().size() > 0
		respXml.attributes()['xmlns'] == 'http://www.w3.org/2005/Atom'
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 2

		respXml['title'].size() == 1
		def titleNode = respXml['title'][0] as Node
		titleNode.attributes().size() == 0
		titleNode.value().size() == 1
		titleNode.value()[0] == 'Feed of "Syndication Test"'

		respXml['link'].size() == 1
		def linkNode = respXml['link'][0] as Node
		linkNode.attributes().size() == 2
		linkNode.attributes()['rel'] == 'alternate'
		linkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId
		linkNode.value().size() == 0

		respXml['subtitle'].size() == 1
		def subtitleNode = respXml['subtitle'][0] as Node
		subtitleNode.attributes().size() == 0
		subtitleNode.value().size() == 1
		subtitleNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		respXml['entry'].size() == 1
		def entryNode = respXml['entry'][0] as Node
		entryNode.attributes().size() == 0
		entryNode.value().size() > 3

		entryNode['title'].size() == 1
		def itemDescriptionNode = entryNode['title'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		// title with explicit english lang is chosen
		itemDescriptionNode.value()[0] == 'local metadata title explicitly in EN'

		entryNode['summary'].size() == 1
		def summaryDescriptionNode = entryNode['summary'][0] as Node
		summaryDescriptionNode.attributes().size() == 1
		summaryDescriptionNode.attributes()['type'] == 'text'
		summaryDescriptionNode.value().size() == 1
		summaryDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		entryNode['link'].size() == 1
		def itemLinkNode = entryNode['link'][0] as Node
		itemLinkNode.attributes().size() == 2
		itemLinkNode.attributes()['rel'] == 'alternate'
		itemLinkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId
		itemLinkNode.value().size() == 0

		entryNode['dc:date'].size() == 1
		def itemDateNode = entryNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}

	def "GET /{context-id}/resource/{entry-id}?syndication=atom_1.0&lang=pl should return created resource syndication in atom format in Polish"() {
		when:
		// fetch syndication feed for the context
		def resourceConn = EntryStoreClient.getRequest('/_contexts/resource/' + contextId + '?syndication=atom_1.0&lang=pl')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.getInputStream().text)
		respXml.attributes().size() > 0
		respXml.attributes()['xmlns'] == 'http://www.w3.org/2005/Atom'
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 2

		respXml['title'].size() == 1
		def titleNode = respXml['title'][0] as Node
		titleNode.attributes().size() == 0
		titleNode.value().size() == 1
		titleNode.value()[0] == 'Feed of "Syndication Test"'

		respXml['link'].size() == 1
		def linkNode = respXml['link'][0] as Node
		linkNode.attributes().size() == 2
		linkNode.attributes()['rel'] == 'alternate'
		linkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId
		linkNode.value().size() == 0

		respXml['subtitle'].size() == 1
		def subtitleNode = respXml['subtitle'][0] as Node
		subtitleNode.attributes().size() == 0
		subtitleNode.value().size() == 1
		subtitleNode.value()[0] == 'A syndication feed containing the 50 most recent items from "Syndication Test"'

		respXml['entry'].size() == 1
		def entryNode = respXml['entry'][0] as Node
		entryNode.attributes().size() == 0
		entryNode.value().size() > 3

		entryNode['title'].size() == 1
		def itemDescriptionNode = entryNode['title'][0] as Node
		itemDescriptionNode.attributes().size() == 0
		itemDescriptionNode.value().size() == 1
		// title with explicit english lang is chosen
		itemDescriptionNode.value()[0] == 'local metadata title explicitly in EN'

		entryNode['summary'].size() == 1
		def summaryDescriptionNode = entryNode['summary'][0] as Node
		summaryDescriptionNode.attributes().size() == 1
		summaryDescriptionNode.attributes()['type'] == 'text'
		summaryDescriptionNode.value().size() == 1
		summaryDescriptionNode.value()[0] == 'lokalne metadane opis jawnie po polsku'

		entryNode['link'].size() == 1
		def itemLinkNode = entryNode['link'][0] as Node
		itemLinkNode.attributes().size() == 2
		itemLinkNode.attributes()['rel'] == 'alternate'
		itemLinkNode.attributes()['href'] == EntryStoreClient.baseUrl + '/' + contextId + '/resource/' + entryId
		itemLinkNode.value().size() == 0

		entryNode['dc:date'].size() == 1
		def itemDateNode = entryNode['dc:date'][0] as Node
		itemDateNode.attributes().size() == 0
		itemDateNode.value().size() == 1
		(itemDateNode.value()[0] as String).contains(Year.now().toString())
	}
}
