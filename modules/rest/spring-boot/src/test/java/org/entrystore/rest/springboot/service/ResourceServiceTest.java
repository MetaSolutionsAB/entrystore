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

package org.entrystore.rest.springboot.service;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

	@TempDir
	Path isolatedTmpDir;

	private String originalTmpDir;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ResourceJsonSerializer resourceSerializer;

	@Mock
	private RestTemplate restTemplate;

	@Mock
	private SessionRegistry sessionRegistry;

	@Mock
	private Entry entry;

	private ResourceService service;

	@BeforeEach
	void setUp() {
		// Redirect java.io.tmpdir to a JUnit-managed isolated directory so the
		// temp-file cleanup assertions cannot be polluted by other processes or
		// orphan files left in the shared system temp directory.
		originalTmpDir = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", isolatedTmpDir.toString());
		service = new ResourceService(repositoryManager, resourceSerializer, restTemplate, sessionRegistry);
	}

	@AfterEach
	void restoreTmpDir() {
		if (originalTmpDir != null) {
			System.setProperty("java.io.tmpdir", originalTmpDir);
		}
	}

	@Test
	void importEntryResource_zipWithoutRdfEntries_deletesTempFile() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);

		service.importEntryResource(entry, buildZip("readme.txt", "hello"), true);

		assertIsolatedTmpDirIsEmpty("success path");
	}

	@Test
	void importEntryResource_zipWithRdfEntry_deletesTempFileEvenOnException() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] zipBytes = buildZip("data.rdf", "<rdf:RDF/>");

		// importRDFResource is currently stubbed and throws NotImplementedException;
		// the finally block in importFromZIP must still clean up.
		assertThrows(NotImplementedException.class,
				() -> service.importEntryResource(entry, zipBytes, true));

		assertIsolatedTmpDirIsEmpty("exception path from importRDFResource stub");
	}

	@Test
	void importEntryResource_malformedZipPayload_deletesTempFileAndThrows() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] notAZip = "this is not a zip file".getBytes(StandardCharsets.UTF_8);

		// new ZipFile(tmpFile) throws ZipException (an IOException) for a non-ZIP
		// payload; importFromZIP wraps it as InternalServerErrorException.
		assertThrows(InternalServerErrorException.class,
				() -> service.importEntryResource(entry, notAZip, true));

		assertIsolatedTmpDirIsEmpty("exception path from ZipFile constructor");
	}

	private void assertIsolatedTmpDirIsEmpty(String pathDescription) throws IOException {
		try (Stream<Path> entries = Files.list(isolatedTmpDir)) {
			assertEquals(0, entries.count(),
					"isolated tmpdir must be empty after importEntryResource (" + pathDescription + ")");
		}
	}

	private static byte[] buildZip(String entryName, String body) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(baos)) {
			zos.putNextEntry(new ZipEntry(entryName));
			zos.write(body.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return baos.toByteArray();
	}
}
