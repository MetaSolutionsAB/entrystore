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

import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
	void setData_bodyAboveMaximum_throwsBadRequest() {
		// The raw-body PUT used to skip the maximum-file-size check that the multipart path enforced.
		when(repositoryManager.getMaximumFileSize()).thenReturn(1L);

		assertThrows(BadRequestException.class,
				() -> service.setData(entry, new byte[]{1, 2}, "application/octet-stream", null, null));

		verify(entry, never()).getResource();
	}

	@Test
	void setData_underMaximum_storesDataAndRecordsMetadata() throws Exception {
		File dataFile = Files.createFile(isolatedTmpDir.resolve("payload.bin")).toFile();
		Data data = mock(Data.class);
		when(data.getDataFile()).thenReturn(dataFile);
		when(entry.getResource()).thenReturn(data);
		when(repositoryManager.getMaximumFileSize()).thenReturn(-1L);

		service.setData(entry, new byte[]{1, 2, 3}, "application/octet-stream", "image/png", "../a.png");

		verify(data).setData(any(InputStream.class));
		verify(entry).setFileSize(dataFile.length());
		// The explicit mimeType parameter wins over the request media type.
		verify(entry).setMimetype("image/png");
		verify(entry).setFilename(FileUtil.sanitizeFilename("../a.png"));
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
	void setDataMultipart_fileAboveMaximum_throwsBadRequest() {
		when(entry.getGraphType()).thenReturn(GraphType.None);
		when(repositoryManager.getMaximumFileSize()).thenReturn(1L);
		MultipartFile file = mock(MultipartFile.class);
		when(file.getSize()).thenReturn(2L);

		assertThrows(BadRequestException.class, () -> service.setDataMultipart(entry, file, null));

		// Rejected before anything is written or recorded on the entry.
		verify(entry, never()).getResource();
		verify(entry, never()).setMimetype(any());
	}
}
