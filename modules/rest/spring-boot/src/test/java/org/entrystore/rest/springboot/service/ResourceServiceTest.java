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

import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

	@TempDir
	Path isolatedTmpDir;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ResourceJsonSerializer resourceSerializer;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private SsrfValidator ssrfValidator;

	@Mock
	private SessionRegistry sessionRegistry;

	@Mock
	private Entry entry;

	private ResourceService service;

	@BeforeEach
	void setUp() {
		service = new ResourceService(repositoryManager, resourceSerializer, principalManager, ssrfValidator, sessionRegistry);
		// Point importTmpDir at the JUnit-managed isolated directory so the
		// temp-file cleanup assertions are scoped to this test and cannot be
		// polluted by other processes or orphan files in the shared system temp.
		service.setImportTmpDir(isolatedTmpDir.toFile());
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
	void deleteResource_proxyTrueUnauthorized_throwsBeforeOutboundDelete() {
		// Pins the contract that the WriteResource check fires BEFORE the proxy branch
		// reaches deleteRemoteResource — otherwise an unauthorized caller could trigger
		// an outbound DELETE.
		doThrow(new AuthorizationException(null, null, AccessProperty.WriteResource))
				.when(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		assertThrows(AuthorizationException.class,
				() -> service.deleteResource(entry, "true", false));

		verifyNoInteractions(ssrfValidator);
	}

	@Test
	void deleteResource_proxyTrue_blacklistedOrigin_throwsForbidden() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.Link);
		when(entry.getResourceURI()).thenReturn(URI.create("http://127.0.0.1:1/x"));
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/1/entry/2"));
		doThrow(new ForbiddenException("Access denied: host is blacklisted"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(ForbiddenException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void deleteResource_proxyTrue_badScheme_throwsBadRequest() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.Reference);
		when(entry.getResourceURI()).thenReturn(URI.create("ftp://example.org/x"));
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/1/entry/2"));
		doThrow(new BadRequestException("Only http and https URLs are supported"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(BadRequestException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void deleteResource_proxyTrue_userinfoInUrl_throwsBadRequest() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.LinkReference);
		when(entry.getResourceURI()).thenReturn(URI.create("http://user:pass@example.org/x"));
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/1/entry/2"));
		doThrow(new BadRequestException("URLs with embedded credentials are not allowed"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(BadRequestException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void importEntryResource_malformedZipPayload_deletesTempFileAndThrows() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] notAZip = "this is not a zip file".getBytes(StandardCharsets.UTF_8);

		// new ZipFile(tmpFile) throws ZipException (an IOException) for a non-ZIP
		// payload; importFromZIP wraps it as InternalServerErrorException.
		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.importEntryResource(entry, notAZip, true));
		assertInstanceOf(ZipException.class, ex.getCause());

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
