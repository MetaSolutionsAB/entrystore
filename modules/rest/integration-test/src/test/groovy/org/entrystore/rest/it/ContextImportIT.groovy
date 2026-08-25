/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it

import org.entrystore.repository.util.HashType
import org.entrystore.repository.util.Hashing
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.http.HttpMethod

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ContextImportIT extends BaseSpec {

	def static contextExportId = 'context-export'
	def static contextImportId = 'context-import-test'
	def static entryIdInExportOriginally = 'export-entry-id'
	def static entryId2InImportOriginally = 'import-entry-id-should-be-overridden'
	def static resourceUrl = 'https://bbc.co.uk'

	// Test-only predicate with a unique literal so the entry removed by the import can be located in
	// Solr via the generic literal_s dynamic field; the field name is derived from the predicate IRI
	// with the same MD5 truncation the indexer uses (SolrSearchIndex.addGenericMetadataFields), cf. SearchIT.
	static final String PURGE_MARKER_IRI = 'http://example.org/ns/contextImportIT-purge-marker'
	static final String PURGE_MARKER_VALUE = 'contextimportpurgemarker'
	static final String PURGE_MARKER_FIELD = 'metadata.predicate.literal_s.' + Hashing.hash(PURGE_MARKER_IRI, HashType.MD5).substring(0, 8)

	def setupSpec() {
		getOrCreateContext([contextId: contextExportId, name: 'context for Export'])
		def params = [entrytype: 'link', resource: resourceUrl, id: entryIdInExportOriginally]
		getOrCreateEntry(contextExportId, params)

		getOrCreateContext([contextId: contextImportId, name: 'context for Import'])
		def params2 = [entrytype: 'link', resource: resourceUrl, id: entryId2InImportOriginally]
		getOrCreateEntry(contextImportId, params2)
	}

	def "POST /{context-id}/import as guest should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', 'dummyBody', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import as guest for non-existing context should return Unauthorized 401"() {
		when:
		def contextId = 'non-existing-context-id'
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', 'dummyBody', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import with empty body as guest should return Unauthorized 401"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import with empty body as guest for non-existing context should return Unauthorized 401"() {
		when:
		def contextId = 'non-existing-context-id'
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', '', '')

		then:
		connection.getResponseCode() == HTTP_UNAUTHORIZED
	}

	def "POST /{context-id}/import as non-admin user should return Forbidden 403"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', 'dummyBody',
			'user', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_FORBIDDEN
	}

	def "POST /{context-id}/import as admin for non-existing context should return Not-Found 404"() {
		given:
		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', 'dummyBody')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/import as member of admin group for non-existing context should return Not-Found 404"() {
		given:
		def contextId = 'non-existing-context-id'

		when:
		def connection = EntryStoreClient.postRequest('/' + contextId + '/import', 'dummyBody',
			'userInAdminGroup')

		then:
		connection.getResponseCode() == HTTP_NOT_FOUND
	}

	def "POST /{context-id}/import as member of admin group with invalid zip-file should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '[]',
			'userInAdminGroup', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/import as admin with invalid zip-file should return Bad-Request 400"() {
		when:
		def connection = EntryStoreClient.postRequest('/' + contextImportId + '/import', '[]',
			'admin', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_BAD_REQUEST
	}

	def "POST /{context-id}/import as admin with zip-file as body should import context from the file, overriding existing entries"() {
		given:
		// check existing list of entries for context that will be overridden
		def contextConn = EntryStoreClient.getRequest('/' + contextImportId)
		assert contextConn.getResponseCode() == HTTP_OK
		assert contextConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(contextConn.inputStream.text) == [entryId2InImportOriginally]

		// check context name for context that will be overridden
		def contextNameConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		assert contextNameConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(contextNameConn.inputStream.text) == [name: 'context for Import']

		// get the ZIP file by exporting context with ID `contextExportId`
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK

		when:
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + contextImportId + '/import',
			exportConn.getInputStream(), 'admin', 'application/zip')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		connection.inputStream.text == '<textarea></textarea>'

		def conn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def getResponseJson = JSON_PARSER.parseText(conn.inputStream.text)
		getResponseJson['entryId'] == contextImportId
		getResponseJson['info'] != null

		// check if overridden context changed it's entries
		def contextConn2 = EntryStoreClient.getRequest('/' + contextImportId)
		contextConn2.getResponseCode() == HTTP_OK
		contextConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(contextConn2.inputStream.text) == [entryIdInExportOriginally]

		// check if overridden context changed it's name
		def contextNameConn2 = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		contextNameConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(contextNameConn2.inputStream.text) == [name: 'context for Import']
	}

	def "POST /{context-id}/import with multi-part file as body should import context from the file, overriding existing entries"() {
		given:
		def contextImportId = 'context-import-test2'
		getOrCreateContext([contextId: contextImportId, name: 'context for Import 2'])

		// check existing list of entries for context that will be overridden
		def contextConn = EntryStoreClient.getRequest('/' + contextImportId)
		assert contextConn.getResponseCode() == HTTP_OK
		assert contextConn.getContentType().contains('application/json')
		assert JSON_PARSER.parseText(contextConn.inputStream.text) == []

		// check context name for context that will be overridden
		def contextNameConn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		assert contextNameConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(contextNameConn.inputStream.text) == [name: 'context for Import 2']

		// get the ZIP file by exporting context with ID `contextExportId`
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK
		def tempFile = File.createTempFile('export-file', '.zip')
		tempFile.withOutputStream { out ->
			out << exportConn.getInputStream()
		}

		when:
		def connection = EntryStoreClient.postRequestMultiPart('/' + contextImportId + '/import',
			tempFile, 'admin')

		then:
		connection.getResponseCode() == HTTP_OK
		connection.getContentType().contains('text/html')
		connection.inputStream.text == '<textarea></textarea>'

		def conn = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId)
		conn.getResponseCode() == HTTP_OK
		conn.getContentType().contains('application/json')
		def getResponseJson = JSON_PARSER.parseText(conn.inputStream.text)
		getResponseJson['entryId'] == contextImportId
		getResponseJson['info'] != null

		// check if overridden context changed it's entries
		def contextConn2 = EntryStoreClient.getRequest('/' + contextImportId)
		contextConn2.getResponseCode() == HTTP_OK
		contextConn2.getContentType().contains('application/json')
		JSON_PARSER.parseText(contextConn2.inputStream.text) == [entryIdInExportOriginally]

		// check if overridden context changed it's name
		def contextNameConn2 = EntryStoreClient.getRequest('/_contexts/entry/' + contextImportId + '/name')
		contextNameConn2.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(contextNameConn2.inputStream.text) == [name: 'context for Import 2']
	}

	def "POST /{context-id}/import with corrupt triples.rdf should fail with Bad-Request 400 and preserve existing entries"() {
		given: 'a context holding an entry that must survive a failed import'
		def corruptImportId = 'context-import-corrupt'
		def preservedEntryId = 'should-survive-failed-import'
		getOrCreateContext([contextId: corruptImportId, name: 'context for corrupt import'])
		getOrCreateEntry(corruptImportId, [entrytype: 'link', resource: resourceUrl, id: preservedEntryId])

		def beforeConn = EntryStoreClient.getRequest('/' + corruptImportId)
		assert beforeConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(beforeConn.inputStream.text) == [preservedEntryId]

		and: 'a valid export ZIP whose triples.rdf has been replaced with invalid RDF'
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK
		byte[] corruptZip = corruptTriplesInZip(exportConn.getInputStream())

		when: 'importing the corrupted ZIP'
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + corruptImportId + '/import',
			new ByteArrayInputStream(corruptZip), 'admin', 'application/zip')

		then: 'the import is rejected'
		connection.getResponseCode() == HTTP_BAD_REQUEST

		and: 'the original entry is still present - the failed parse must not have wiped the context'
		def afterConn = EntryStoreClient.getRequest('/' + corruptImportId)
		afterConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(afterConn.inputStream.text) == [preservedEntryId]

		and: 'the context name is unchanged too'
		def nameConn = EntryStoreClient.getRequest('/_contexts/entry/' + corruptImportId + '/name')
		nameConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(nameConn.inputStream.text) == [name: 'context for corrupt import']
	}

	def "POST /{context-id}/import with a ZIP missing triples.rdf should fail with Bad-Request 400 and preserve existing entries"() {
		given: 'a context holding an entry that must survive a failed import'
		def missingTriplesImportId = 'context-import-missing-triples'
		def preservedEntryId = 'should-survive-missing-triples-import'
		getOrCreateContext([contextId: missingTriplesImportId, name: 'context for missing-triples import'])
		getOrCreateEntry(missingTriplesImportId, [entrytype: 'link', resource: resourceUrl, id: preservedEntryId])

		def beforeConn = EntryStoreClient.getRequest('/' + missingTriplesImportId)
		assert beforeConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(beforeConn.inputStream.text) == [preservedEntryId]

		and: 'a valid export ZIP with its triples.rdf entry removed'
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK
		byte[] zipWithoutTriples = dropTriplesFromZip(exportConn.getInputStream())

		when: 'importing the ZIP that has no triples.rdf'
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + missingTriplesImportId + '/import',
			new ByteArrayInputStream(zipWithoutTriples), 'admin', 'application/zip')

		then: 'the import is rejected'
		connection.getResponseCode() == HTTP_BAD_REQUEST

		and: 'the original entry is still present - the missing triples.rdf must not have wiped the context'
		def afterConn = EntryStoreClient.getRequest('/' + missingTriplesImportId)
		afterConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(afterConn.inputStream.text) == [preservedEntryId]

		and: 'the context name is unchanged too'
		def nameConn = EntryStoreClient.getRequest('/_contexts/entry/' + missingTriplesImportId + '/name')
		nameConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(nameConn.inputStream.text) == [name: 'context for missing-triples import']
	}

	def "POST /{context-id}/import should purge the overridden entries from the Solr index"() {
		given: 'a context holding an indexed entry that the import will remove'
		def solrImportId = 'context-import-solr-purge'
		def purgedEntryId = 'solr-purge-entry-id'
		getOrCreateContext([contextId: solrImportId, name: 'context for Solr purge import'])
		def newResourceIri = EntryStoreClient.baseUrl + '/' + solrImportId + '/resource/_newId'
		def body = [resource: 'purge text',
					metadata: [(newResourceIri): [
						(PURGE_MARKER_IRI): [
							[type: 'literal', value: PURGE_MARKER_VALUE]
						]
					]]]
		getOrCreateEntry(solrImportId, [id: purgedEntryId, graphtype: 'string'], body)
		waitForSolrProcessing()
		// Solr needs even more time to finish processing, cf. SearchIT
		Thread.sleep(1500)

		and: 'the entry is searchable before the import'
		def searchQuery = URLEncoder.encode(PURGE_MARKER_FIELD + ':' + PURGE_MARKER_VALUE, 'UTF-8')
		def beforeConn = EntryStoreClient.getRequest('/search?type=solr&query=' + searchQuery)
		assert beforeConn.getResponseCode() == HTTP_OK
		assert JSON_PARSER.parseText(beforeConn.inputStream.text)['results'] == 1

		and: 'a valid export ZIP of another context'
		def exportConn = EntryStoreClient.getRequest('/' + contextExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK

		when: 'importing the ZIP over the context'
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + solrImportId + '/import',
			exportConn.getInputStream(), 'admin', 'application/zip')

		then: 'the import succeeds'
		connection.getResponseCode() == HTTP_OK

		and: 'the removed entry is no longer searchable - the deferred EntryDeleted events must reach Solr'
		waitForSolrProcessing()
		Thread.sleep(1500)
		def afterConn = EntryStoreClient.getRequest('/search?type=solr&query=' + searchQuery)
		afterConn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(afterConn.inputStream.text)['results'] == 0
	}

	def "POST /{context-id}/import with file entries should replace the old data file with the imported one"() {
		given: 'an export context holding a file entry with content'
		def fileExportId = 'context-export-files'
		def fileImportId = 'context-import-files'
		getOrCreateContext([contextId: fileExportId, name: 'context for file export'])
		def exportedFileEntryId = getOrCreateEntry(fileExportId, [id: 'exported-file-entry'], [resource: [name: 'exported file']])
		def exportedFile = File.createTempFile('entrystore-import-it-exported', '.bin')
		exportedFile.text = 'imported file content'
		def uploadExportedConn = EntryStoreClient.putRequestFile('/' + fileExportId + '/resource/' + exportedFileEntryId,
			exportedFile, 'admin', 'application/octet-stream')
		assert uploadExportedConn.getResponseCode() == HTTP_CREATED

		and: 'a target context holding its own file entry that the import will remove'
		getOrCreateContext([contextId: fileImportId, name: 'context for file import'])
		def oldFileEntryId = getOrCreateEntry(fileImportId, [id: 'old-file-entry'], [resource: [name: 'old file']])
		def oldFile = File.createTempFile('entrystore-import-it-old', '.bin')
		oldFile.text = 'old file content'
		def uploadOldConn = EntryStoreClient.putRequestFile('/' + fileImportId + '/resource/' + oldFileEntryId,
			oldFile, 'admin', 'application/octet-stream')
		assert uploadOldConn.getResponseCode() == HTTP_CREATED

		and: 'the export ZIP of the file-holding context'
		def exportConn = EntryStoreClient.getRequest('/' + fileExportId + '/export')
		assert exportConn.getResponseCode() == HTTP_OK

		when: 'importing the ZIP over the target context'
		def connection = EntryStoreClient.sendRequestAsStream(HttpMethod.POST, '/' + fileImportId + '/import',
			exportConn.getInputStream(), 'admin', 'application/zip')

		then: 'the import succeeds'
		connection.getResponseCode() == HTTP_OK

		and: 'the old file entry and its data are gone'
		EntryStoreClient.getRequest('/' + fileImportId + '/resource/' + oldFileEntryId).getResponseCode() == HTTP_NOT_FOUND

		and: 'the imported file entry serves the content copied from the export'
		def importedFileConn = EntryStoreClient.getRequest('/' + fileImportId + '/resource/' + exportedFileEntryId)
		importedFileConn.getResponseCode() == HTTP_OK
		importedFileConn.inputStream.text == 'imported file content'
	}

	// Copies a context-export ZIP while overwriting the triples.rdf entry with invalid RDF, leaving
	// export.properties intact so the import reaches the parse step (which then fails).
	private static byte[] corruptTriplesInZip(InputStream zipInput) {
		def out = new ByteArrayOutputStream()
		new ZipInputStream(zipInput).withCloseable { zis ->
			new ZipOutputStream(out).withCloseable { zos ->
				ZipEntry entry
				while ((entry = zis.getNextEntry()) != null) {
					zos.putNextEntry(new ZipEntry(entry.name))
					if (entry.name == 'triples.rdf') {
						zos.write('this is not valid TriG @@@ <<< >>>'.getBytes('UTF-8'))
					} else {
						zos << zis
					}
					zos.closeEntry()
				}
			}
		}
		out.toByteArray()
	}

	// Copies a context-export ZIP while dropping the triples.rdf entry entirely, so the import fails
	// with an IOException when it tries to read the missing file (mapped to 400 by the REST layer).
	private static byte[] dropTriplesFromZip(InputStream zipInput) {
		def out = new ByteArrayOutputStream()
		new ZipInputStream(zipInput).withCloseable { zis ->
			new ZipOutputStream(out).withCloseable { zos ->
				ZipEntry entry
				while ((entry = zis.getNextEntry()) != null) {
					if (entry.name == 'triples.rdf') {
						continue
					}
					zos.putNextEntry(new ZipEntry(entry.name))
					zos << zis
					zos.closeEntry()
				}
			}
		}
		out.toByteArray()
	}
}
