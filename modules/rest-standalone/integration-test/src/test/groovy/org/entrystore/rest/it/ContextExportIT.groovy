package org.entrystore.rest.it

import groovy.xml.XmlParser
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.NameSpaceConst

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK

class ContextExportIT extends BaseSpec {

	def static contextId = 'context-export-test'
	def static contextName = 'context-export-name'
	def static entryId = 'export-entry-id'
	def static resourceUrl = 'https://bbc.co.uk'

	def setupSpec() {
		getOrCreateContext([contextId: contextId, name: contextName])
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		getOrCreateEntry(contextId, params)
	}

	def "GET /{context-id}/export as non-admin and for non-existing context should return a Not-Found 404 response"() {
		given:
		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/export', '')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "GET /{context-id}/export should return context data in Trig format inside a zip-file"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/export')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/zip')
		def zipFile = new ZipInputStream(connection.getInputStream())
		def exportedZip = new HashMap<String, String>()
		ZipEntry ze
		while ((ze = zipFile.getNextEntry()) != null) {
			exportedZip[ze.name] = new String(zipFile.readAllBytes())
		}

		exportedZip.keySet().size() == 2
		exportedZip['triples.rdf'] != null
		exportedZip['export.properties'] != null

		exportedZip['triples.rdf'].contains('@prefix es: <' + NameSpaceConst.ES_TERMS + '> .')
		exportedZip['triples.rdf'].contains('@prefix store: <' + EntryStoreClient.baseUrl + '/> .')
		exportedZip['triples.rdf'].contains('store:' + contextId + ' a es:Context;')
		exportedZip['triples.rdf'].contains('<' + EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId + '> es:resource store:' + contextId + ';')
		exportedZip['triples.rdf'].contains('<' + EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId + '> a es:Link;')
		exportedZip['triples.rdf'].contains(' es:resource <' + resourceUrl + '>;')

		exportedZip['export.properties'].contains('containedUsers=_admin\\:admin,')
		exportedZip['export.properties'].contains('contextEntryURI=http\\://localhost\\:8181/store/_contexts/entry/context-export-test')
		exportedZip['export.properties'].contains('scamBaseURI=http\\://localhost\\:8181/store/')
		exportedZip['export.properties'].contains('exportDate=')
	}

	def "GET /{context-id}/export?rdfFormat=text/turtle should return context data in Turtle format as zip-file"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/export?rdfFormat=text/turtle')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/zip')
		def zipFile = new ZipInputStream(connection.getInputStream())
		def exportedZip = new HashMap<String, String>()
		ZipEntry ze
		while ((ze = zipFile.getNextEntry()) != null) {
			exportedZip[ze.name] = new String(zipFile.readAllBytes())
		}

		exportedZip.keySet().size() == 2
		exportedZip['triples.rdf'] != null
		exportedZip['export.properties'] != null

		exportedZip['triples.rdf'].contains('@prefix es: <' + NameSpaceConst.ES_TERMS + '> .')
		exportedZip['triples.rdf'].contains('@prefix store: <' + EntryStoreClient.baseUrl + '/> .')
		exportedZip['triples.rdf'].contains('store:' + contextId + ' a es:Context;')
		exportedZip['triples.rdf'].contains('<' + EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId + '> es:resource store:' + contextId + ';')

		exportedZip['export.properties'].contains('containedUsers=_admin\\:admin,')
		exportedZip['export.properties'].contains('contextEntryURI=http\\://localhost\\:8181/store/_contexts/entry/context-export-test')
		exportedZip['export.properties'].contains('scamBaseURI=http\\://localhost\\:8181/store/')
		exportedZip['export.properties'].contains('exportDate=')
	}

	def "GET /{context-id}/export?rdfFormat=application/rdf+xml should return context data in RDF+XML format as zip-file"() {
		when:
		def connection = EntryStoreClient.getRequest('/' + contextId + '/export?rdfFormat=application/rdf+xml')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('application/zip')
		def zipFile = new ZipInputStream(connection.getInputStream())
		def exportedZip = new HashMap<String, String>()
		ZipEntry ze
		while ((ze = zipFile.getNextEntry()) != null) {
			exportedZip[ze.name] = new String(zipFile.readAllBytes())
		}

		exportedZip.keySet().size() == 2
		exportedZip['triples.rdf'] != null
		exportedZip['export.properties'] != null

		def entryRespXml = new XmlParser(false, false).parseText(exportedZip['triples.rdf'])
		entryRespXml.attributes().size() > 17
		entryRespXml.attributes()['xmlns:es'] == NameSpaceConst.ES_TERMS
		entryRespXml.attributes()['xmlns:store'] == EntryStoreClient.baseUrl + '/'
		entryRespXml.value().size() >= 4

		entryRespXml['es:Context'].size() == 1
		def contextNode = entryRespXml['es:Context'][0] as Node
		contextNode.attributes() == ['rdf:about': EntryStoreClient.baseUrl + '/' + contextId]
		contextNode.value().size() == 0

		entryRespXml['rdf:Description'].size() >= 2
		def descriptionNode = entryRespXml['rdf:Description'][0] as Node
		descriptionNode.attributes() == ['rdf:about': EntryStoreClient.baseUrl + '/_contexts/entry/' + contextId]
		descriptionNode.value().size() >= 7
		descriptionNode['es:resource'].size() == 1
		def entryResource = descriptionNode['es:resource'][0] as Node
		entryResource.attributes() == ['rdf:resource': 'http://localhost:8181/store/context-export-test']
		entryResource.value().size() == 0

		entryRespXml['es:Link'].size() == 1
		def entryNode = entryRespXml['es:Link'][0] as Node
		entryNode.attributes() == ['rdf:about': EntryStoreClient.baseUrl + '/' + contextId + '/entry/' + entryId]
		entryNode.value().size() > 5
		entryNode['es:resource'].size() == 1
		(entryNode['es:resource'][0] as Node).attributes() == ['rdf:resource': resourceUrl]

		exportedZip['export.properties'].contains('containedUsers=_admin\\:admin,')
		exportedZip['export.properties'].contains('contextEntryURI=http\\://localhost\\:8181/store/_contexts/entry/context-export-test')
		exportedZip['export.properties'].contains('scamBaseURI=http\\://localhost\\:8181/store/')
		exportedZip['export.properties'].contains('exportDate=')
	}
}
