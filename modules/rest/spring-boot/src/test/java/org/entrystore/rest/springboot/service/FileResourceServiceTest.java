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

import org.apache.commons.io.function.IOSupplier;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileResourceServiceTest {

	@TempDir
	Path isolatedTmpDir;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private Entry entry;

	private FileResourceService service;

	@BeforeEach
	void setUp() {
		service = new FileResourceService(repositoryManager);
	}

	@Test
	void mediaTypeForDownload_invalidMimetype_fallsBackToOctetStream() {
		// The mimetype is stored verbatim from the upload request, so it may not parse.
		when(entry.getMimetype()).thenReturn("not a type");

		assertEquals(MediaType.APPLICATION_OCTET_STREAM, service.mediaTypeForDownload(entry));
	}

	@Test
	void mediaTypeForDownload_javascriptWithRewriteEnabled_returnsTextPlain() {
		service.setRewriteMediaTypeJavaScript(true);
		when(entry.getMimetype()).thenReturn("application/javascript");

		assertEquals(MediaType.TEXT_PLAIN, service.mediaTypeForDownload(entry));
	}

	@Test
	void mediaTypeForDownload_javascriptWithRewriteDisabled_returnsStoredType() {
		// The flag defaults to off, so JavaScript is served as stored.
		when(entry.getMimetype()).thenReturn("application/javascript");

		assertEquals(MediaType.parseMediaType("application/javascript"), service.mediaTypeForDownload(entry));
	}

	@Test
	void setDataMultipart_nonNoneGraphType_throwsBadRequest() {
		when(entry.getGraphType()).thenReturn(GraphType.String);

		assertThrows(BadRequestException.class,
				() -> service.setDataMultipart(entry, mock(MultipartFile.class), null));

		verifyNoInteractions(repositoryManager);
	}

	@Test
	void setData_contentLengthAboveMaximum_throwsEntityTooLargeBeforeOpeningTheBody() {
		when(repositoryManager.getMaximumFileSize()).thenReturn(1L);

		assertThrows(EntityTooLargeException.class,
				() -> service.setData(entry, unopenableBody(), 2, "application/octet-stream", null, null));

		verify(entry, never()).getResource();
	}

	@Test
	void setData_bodyWithoutContentLengthAboveMaximum_throwsEntityTooLarge() throws Exception {
		Data data = mock(Data.class);
		when(entry.getResource()).thenReturn(data);
		doAnswer(drainBody()).when(data).setData(any(InputStream.class));
		when(repositoryManager.getMaximumFileSize()).thenReturn(1024L);

		EntityTooLargeException e = assertThrows(EntityTooLargeException.class, () -> service.setData(entry,
				() -> new ByteArrayInputStream(new byte[1025]), -1, "application/octet-stream", null, null));

		assertEquals("Received file exceeds maximum allowed size of: 1024b", e.getMessage());
		verify(entry, never()).setFileSize(anyLong());
	}

	@Test
	void setData_bodyWithoutContentLengthExactlyAtMaximum_isStored() throws Exception {
		File dataFile = Files.createFile(isolatedTmpDir.resolve("payload.bin")).toFile();
		Data data = mock(Data.class);
		when(entry.getResource()).thenReturn(data);
		when(data.getDataFile()).thenReturn(dataFile);
		doAnswer(drainBody()).when(data).setData(any(InputStream.class));
		when(repositoryManager.getMaximumFileSize()).thenReturn(1024L);

		service.setData(entry, () -> new ByteArrayInputStream(new byte[1024]), -1, "application/octet-stream", null, null);

		verify(entry).setFileSize(dataFile.length());
	}

	@Test
	void setData_bodyOfThreeGigabytes_isStreamedWithoutBuffering() throws Exception {
		File dataFile = Files.createFile(isolatedTmpDir.resolve("payload.bin")).toFile();
		Data data = mock(Data.class);
		when(entry.getResource()).thenReturn(data);
		when(data.getDataFile()).thenReturn(dataFile);
		long size = 3L * 1024 * 1024 * 1024;
		AtomicLong stored = new AtomicLong();
		doAnswer(invocation -> drain(invocation.getArgument(0), stored)).when(data).setData(any(InputStream.class));
		when(repositoryManager.getMaximumFileSize()).thenReturn(size);

		service.setData(entry, () -> lockstepBody(size, stored), size, "application/octet-stream", null, null);

		assertEquals(size, stored.get());
	}

	@Test
	void setData_clientGoneMidBody_throwsBadRequestRatherThanServerError() throws Exception {
		Data data = mock(Data.class);
		when(entry.getResource()).thenReturn(data);
		doAnswer(drainBody()).when(data).setData(any(InputStream.class));
		when(repositoryManager.getMaximumFileSize()).thenReturn(-1L);
		InputStream abortedBody = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("Connection reset");
			}
		};

		assertThrows(BadRequestException.class,
				() -> service.setData(entry, () -> abortedBody, 1024, "application/octet-stream", null, null));
	}

	@Test
	void setData_entryRemovedDuringTheUpload_throwsNotFound() throws Exception {
		Data data = mock(Data.class);
		when(entry.getResource()).thenReturn(data);
		when(repositoryManager.getMaximumFileSize()).thenReturn(-1L);
		doThrow(new DataImpl.EntryRemovedException(URI.create("http://example.org/1/entry/2")))
				.when(data).setData(any(InputStream.class));

		assertThrows(EntityNotFoundException.class, () -> service.setData(entry,
				() -> new ByteArrayInputStream(new byte[3]), 3, "application/octet-stream", null, null));

		verify(entry, never()).setFileSize(anyLong());
	}

	@Test
	void setDataMultipart_entryRemovedDuringTheUpload_throwsNotFound() throws Exception {
		Data data = mock(Data.class);
		when(entry.getGraphType()).thenReturn(GraphType.None);
		when(entry.getResource()).thenReturn(data);
		when(repositoryManager.getMaximumFileSize()).thenReturn(-1L);
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[3]));
		doThrow(new DataImpl.EntryRemovedException(URI.create("http://example.org/1/entry/2")))
				.when(data).setData(any(InputStream.class));

		assertThrows(EntityNotFoundException.class, () -> service.setDataMultipart(entry, file, null));

		verify(entry, never()).setFileSize(anyLong());
	}

	@Test
	void setData_underMaximum_storesDataAndRecordsMetadata() throws Exception {
		File dataFile = Files.createFile(isolatedTmpDir.resolve("payload.bin")).toFile();
		Data data = mock(Data.class);
		when(data.getDataFile()).thenReturn(dataFile);
		when(entry.getResource()).thenReturn(data);
		when(repositoryManager.getMaximumFileSize()).thenReturn(-1L);
		ByteArrayOutputStream stored = new ByteArrayOutputStream();
		doAnswer(invocation -> ((InputStream) invocation.getArgument(0)).transferTo(stored))
				.when(data).setData(any(InputStream.class));

		service.setData(entry, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/octet-stream",
				"image/png", "../a.png");

		assertArrayEquals(new byte[]{1, 2, 3}, stored.toByteArray());
		verify(entry).setFileSize(dataFile.length());
		// The explicit mimeType parameter wins over the request media type.
		verify(entry).setMimetype("image/png");
		verify(entry).setFilename(FileUtil.sanitizeFilename("../a.png"));
	}

	/** Reads the stream passed to {@code Data.setData} to its end, as the data store does. */
	private static Answer<Void> drainBody() {
		return invocation -> {
			drain(invocation.getArgument(0));
			return null;
		};
	}

	/** Reads {@code in} to its end, publishing the running count to {@code consumed} after every chunk. */
	private static long drain(InputStream in, AtomicLong consumed) throws IOException {
		byte[] buf = new byte[8192];
		int read;
		while ((read = in.read(buf)) != -1) {
			consumed.addAndGet(read);
		}
		return consumed.get();
	}

	private static long drain(InputStream in) throws IOException {
		return drain(in, new AtomicLong());
	}

	/** {@code size} zero bytes, refusing to deliver more while the data store lags more than 64 KiB behind. */
	private static InputStream lockstepBody(long size, AtomicLong stored) {
		return new InputStream() {
			private long delivered;

			@Override
			public int read() {
				throw new UnsupportedOperationException();
			}

			@Override
			public int read(byte[] buf, int off, int len) {
				if (delivered - stored.get() > 64 * 1024) {
					throw new AssertionError("the body is being buffered: " + delivered + " bytes read, "
							+ stored.get() + " stored");
				}
				if (delivered == size) {
					return -1;
				}
				int read = (int) Math.min(len, size - delivered);
				delivered += read;
				return read;
			}
		};
	}

	/** A request body that fails the test if it is opened, which makes Jetty ask the client to send it. */
	private static IOSupplier<InputStream> unopenableBody() {
		return () -> {
			throw new AssertionError("the request body was opened");
		};
	}

	@Test
	void deleteData_failedDelete_throwsServerErrorWithDiagnostics() {
		Data data = mock(Data.class);
		when(entry.getResourceType()).thenReturn(ResourceType.InformationResource);
		when(entry.getResource()).thenReturn(data);
		when(data.delete()).thenReturn(false);
		when(data.getDataFile()).thenReturn(null);

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.deleteData(entry));

		// The diagnostics travel in the message; the handler logs it, so no separate log line is needed.
		assertTrue(ex.getMessage().contains("dataFile=null"));
	}

	@Test
	void deleteData_namedResource_isNoop() {
		when(entry.getResourceType()).thenReturn(ResourceType.NamedResource);

		service.deleteData(entry);

		verify(entry, never()).getResource();
	}

	@Test
	void setDataMultipart_fileAboveMaximum_throwsEntityTooLargeAs5xDid() {
		when(entry.getGraphType()).thenReturn(GraphType.None);
		when(repositoryManager.getMaximumFileSize()).thenReturn(1L);
		MultipartFile file = mock(MultipartFile.class);
		when(file.getSize()).thenReturn(2L);

		assertThrows(EntityTooLargeException.class, () -> service.setDataMultipart(entry, file, null));

		// Rejected before anything is written or recorded on the entry.
		verify(entry, never()).getResource();
		verify(entry, never()).setMimetype(any());
	}
}
