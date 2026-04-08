package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst
import spock.lang.Unroll

import java.time.Year

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

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

	// TODO: Fix inconsistency - guest users get empty results for Solr searches but an authorization error for SPARQL searches

	def "GET /search?type=solr with complex Solr query as guest should return empty search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch', '')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] == 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		respJson['resource']['children'].collect().size() == 0
	}

	def "GET /search?type=solr with complex Solr query as non-admin user should return empty search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch',
			'user')

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] == 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		respJson['resource']['children'].collect().size() == 0
	}

	def "GET /search?type=solr with complex Solr query as admin should return search results"() {
		when:
		def conn = EntryStoreClient.getRequest('/search?type=solr&query=id:randomNonExistingId+OR+description.pl:opissearch') //title.pl:tytuł

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
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

	def "GET /search?type=solr&syndication=rss_2.0 as guest should return empty syndication feed"() {
		when:
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0', '')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == null
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() == 3

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

		channelNode['item'].size() == 0
	}

	def "GET /search?type=solr&syndication=rss_2.0 as admin should return syndication feed for the entry"() {
		when:
		// fetch syndication feed
		def resourceConn = EntryStoreClient.getRequest('/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0')

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
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
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
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
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
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
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)
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

	@Unroll
	def "GET /search?type=sparql with invalid query '#invalidQuery' should be rejected as invalid SPARQL predicate"() {
		given:
		def queryParams = [type: 'sparql', query: invalidQuery]

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.errorStream.text)
		respJson['error'] != null
		respJson['error'].toString().contains('Invalid SPARQL predicate')

		where:
		invalidQuery            | _
		' ?p '                  | _ // SPARQL variable — matches all triples
		'?p . ?p ?q ?r . #'     | _ // triple pattern break injection
		'?p } UNION { ?x ?y'    | _ // UNION injection via curly braces
		'_:blankNode'           | _ // blank node syntax
		'dc:title.'             | _ // trailing dot — SPARQL statement terminator
		'dc:title-'             | _ // trailing hyphen
		'<http://a> ?y . ?x ?q' | _ // IRI followed by injection
		'; DROP'                | _ // semicolon injection
	}

	def "GET /search?type=sparql with valid full IRI predicate should return results"() {
		given:
		def queryParams = [type: 'sparql', query: '<http://purl.org/dc/terms/title>']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['results'] > 0
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		results.size() > 0
		results[0]['metadata'] != null
	}

	def "GET /search?type=sparql&query=dc:title as guest should respond with unauthorized error"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.errorStream.text)
		respJson['error'] != null
		respJson['error'].toString().contains('Unauthorized')
	}

	def "GET /search?type=sparql&query=dc:title as admin should return entries json response with entries having 'dc:title' predicate"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] > 1
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		def searchTestEntry = results.find { it['entryId'] == entryId }
		searchTestEntry != null
		searchTestEntry['metadata'] != null
		def metadataMap = (searchTestEntry['metadata'] as Map).values()
		metadataMap.size() == 1
		def metadata = (metadataMap[0] as Map)
		metadata[NameSpaceConst.DC_TERM_TITLE] != null
		def dcTitle = metadata[NameSpaceConst.DC_TERM_TITLE].collect()
		dcTitle.size() == 3
		dcTitle.find { it['lang'] == 'en' && it['value'] == 'local metadata title explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&rdfFormat=ld+json should return ld+json response with entries having 'dc:title' predicate"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', rdfFormat: 'application/ld+json']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def respJson = JSON_PARSER.parseText(conn.inputStream.text)
		respJson['offset'] == 0
		respJson['results'] > 1
		respJson['resource'] != null
		respJson['resource']['children'] != null
		def results = respJson['resource']['children'].collect()
		def searchTestEntry = results.find { it['entryId'] == entryId }
		searchTestEntry != null
		searchTestEntry['metadata'] != null
		searchTestEntry['metadata']['@graph'] != null
		def metadataMap = searchTestEntry['metadata']['@graph'].collect()
		metadataMap.size() == 1
		def metadata = (metadataMap[0] as Map)
		metadata['dcterms:title'] != null
		def dcTitle = metadata['dcterms:title'].collect()
		dcTitle.size() == 3
		dcTitle.find { it['@value'] == 'local metadata title explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&syndication=rss_2.0 should return rss feed with entries having 'dc:title' predicate, defaulting to explicit English text"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.attributes()['version'] != null
		respXml.value().size() == 1
		respXml['channel'].size() == 1

		def channelNode = respXml['channel'][0] as Node
		channelNode.attributes().size() == 0
		channelNode.value().size() > 3

		channelNode['title'].size() == 1
		with(channelNode['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		channelNode['link'].size() == 1
		with(channelNode['link'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == EntryStoreClient.baseUrl + '/search?type=sparql&query=dc%3Atitle&syndication=rss_2.0'
		}

		channelNode['description'].size() == 1
		with(channelNode['description'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		channelNode['item'].size() > 1
		channelNode['item']['title'].size() > 1
		channelNode['item']['title'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'local metadata title explicitly in EN' } != null

		channelNode['item']['description'].size() > 1
		channelNode['item']['description'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'local metadata description explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&syndication=atom_1.0 should return atom feed with entries having 'dc:title' predicate, defaulting to explicit English text"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'atom_1.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns'].toString().contains('Atom')
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 3

		respXml['title'].size() == 1
		with(respXml['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		respXml['link'].size() == 1
		with(respXml['link'][0] as Node) {
			attributes().size() == 2
			attribute('href') == EntryStoreClient.baseUrl + '/search?type=sparql&query=dc%3Atitle&syndication=atom_1.0'
			value().size() == 0
		}

		respXml['subtitle'].size() == 1
		with(respXml['subtitle'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		respXml['entry'].size() > 1
		respXml['entry']['title'].size() > 1
		respXml['entry']['title'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'local metadata title explicitly in EN' } != null

		respXml['entry']['summary'].size() > 1
		respXml['entry']['summary'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'local metadata description explicitly in EN' } != null
	}

	def "GET /search?type=sparql&query=dc:title&syndication=atom_1.0&lang=pl should return atom feed with entries having 'dc:title' predicate, with values explicitly in Polish"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'atom_1.0', lang: 'pl']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/atom+xml')
		def respXml = new XmlParser(false, false).parseText(conn.inputStream.text)
		respXml.attributes()['xmlns'].toString().contains('Atom')
		respXml.attributes()['xmlns:dc'] == NameSpaceConst.DC_ELEMENTS
		respXml.value().size() > 3

		respXml['title'].size() == 1
		with(respXml['title'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed of search'
		}

		respXml['link'].size() == 1
		with(respXml['link'][0] as Node) {
			attributes().size() == 2
			attribute('href') == EntryStoreClient.baseUrl + '/search?type=sparql&query=dc%3Atitle&syndication=atom_1.0&lang=pl'
			value().size() == 0
		}

		respXml['subtitle'].size() == 1
		with(respXml['subtitle'][0] as Node) {
			attributes().size() == 0
			value().size() == 1
			value()[0] == 'Syndication feed containing max 50 items'
		}

		respXml['entry'].size() > 1
		respXml['entry']['title'].size() > 1
		respXml['entry']['title'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'lokalne metadane tytuł jawnie po polsku' } != null

		respXml['entry']['summary'].size() > 0
		respXml['entry']['summary'].find { Node n -> n.value()?.size() == 1 && n.value()?[0] == 'lokalne metadane opissearch jawnie po polsku' } != null
	}

	def "GET /search?type=sparql&query=dc:title&syndication=random-string as guest should respond with Unauthorized"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'random-string']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "GET /search?type=sparql&query=dc:title&syndication=random-string as admin should return BAD-REQUEST 400 due to invalid syndication format"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc:title', syndication: 'random-string']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		resp['error'] == 'Invalid syndication feed type: \'random-string\''
	}

	def "GET /search?type=sparql&query=dc&syndication=rss_2.0 as guest should return BAD-REQUEST 400 due to short query"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams), '')

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		resp['error'] == 'findEntriesSparql.query: \'query\' param length must be minimum 3'
	}

	def "GET /search?type=sparql&query=dc&syndication=rss_2.0 as admin should return BAD-REQUEST 400 due to short query"() {
		given:
		def queryParams = [type: 'sparql', query: 'dc', syndication: 'rss_2.0']

		when:
		def conn = EntryStoreClient.getRequest('/search' + convertMapToQueryParams(queryParams))

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		conn.getContentType().contains('application/json')
		def resp = JSON_PARSER.parseText(conn.errorStream.text)
		resp['error'] == 'findEntriesSparql.query: \'query\' param length must be minimum 3'
	}

	def "GET /search?type=solr&syndication=rss_2.0 with X-Forwarded headers should use configured base URL, not forwarded host"() {
		given:
		def extraHeaders = [
			'X-Forwarded-Proto': 'https',
			'X-Forwarded-Host' : 'public.example.com',
			'X-Forwarded-Port' : '443'
		]

		when:
		def resourceConn = EntryStoreClient.getRequest(
				'/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0',
				'admin', 'application/rss+xml', extraHeaders)

		then:
		resourceConn.getResponseCode() == HTTP_OK
		resourceConn.getContentType().contains('application/rss+xml')
		def respXml = new XmlParser(false, false).parseText(resourceConn.inputStream.text)

		def channelNode = respXml['channel'][0] as Node
		channelNode['link'].size() == 1
		def channelLinkNode = channelNode['link'][0] as Node
		channelLinkNode.value()[0] == EntryStoreClient.baseUrl + '/search?type=solr&query=description.pl:opissearch&syndication=rss_2.0'
		!channelLinkNode.value()[0].toString().contains('public.example.com')
	}
}
