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

package org.entrystore.rest.springboot.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartUtilTest {

	@Test
	void returnsFilePartNamedFile() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("file", "upload.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("upload.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void returnsFilePartNamedSomethingElse() {
		// Browsers building FormData from an unnamed or indexed file input send part names such as "0"
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("0", "test-page.html", "text/html",
				"<html></html>".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("test-page.html", resolved.get().getOriginalFilename());
	}

	@Test
	void returnsFirstFilePartWhenSeveralAreSentAndNoneIsNamedFile() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("0", "first.bin", "application/octet-stream",
				"first".getBytes(StandardCharsets.UTF_8)));
		request.addFile(new MockMultipartFile("1", "second.bin", "application/octet-stream",
				"second".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("first.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void prefersThePartNamedFileOverAnEarlierOne() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("thumbnail", "thumb.png", "image/png",
				"thumb".getBytes(StandardCharsets.UTF_8)));
		request.addFile(new MockMultipartFile("file", "upload.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("upload.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void skipsThePlaceholderPartOfAnUnfilledFileInput() {
		// An unfilled <input type="file"> is still submitted, as a zero-byte part with filename="",
		// which Spring classifies as a file part
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("0", "", "application/octet-stream", new byte[0]));
		request.addFile(new MockMultipartFile("1", "real.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("real.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void skipsThePlaceholderPartEvenWhenItIsTheOneNamedFile() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("file", "", "application/octet-stream", new byte[0]));
		request.addFile(new MockMultipartFile("0", "real.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("real.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void findsTheRealUploadBehindAPlaceholderOfTheSameName() {
		// Two <input type="file" name="file"> where the first is unfilled: getFileMap() would collapse
		// these two parts to the placeholder alone and hide the upload
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("file", "", "application/octet-stream", new byte[0]));
		request.addFile(new MockMultipartFile("file", "real.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("real.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void prefersAPartCarryingAFilenameOverAnEarlierUnnamedPartWithContent() {
		// Pins the second tier against the third: without the filename filter the stray part would win
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("0", "", "application/octet-stream",
				"stray".getBytes(StandardCharsets.UTF_8)));
		request.addFile(new MockMultipartFile("1", "real.bin", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("real.bin", resolved.get().getOriginalFilename());
	}

	@Test
	void returnsEmptyWhenEveryFilePartIsAnEmptyPlaceholder() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("file", "", "application/octet-stream", new byte[0]));
		request.addFile(new MockMultipartFile("0", "", "application/octet-stream", new byte[0]));

		assertTrue(MultipartUtil.firstFilePart(request).isEmpty());
	}

	@Test
	void returnsAPartCarryingContentWithoutAFilename() throws IOException {
		// A hand-built body may omit the filename while still carrying content; only zero-byte
		// parts are treated as placeholders
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addFile(new MockMultipartFile("0", "", "application/octet-stream",
				"payload".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), resolved.get().getBytes());
	}

	@Test
	void returnsTheFilePartOfARequestThatAlsoCarriesFormFields() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addParameter("mimeType", "text/html");
		request.addFile(new MockMultipartFile("0", "test-page.html", "text/html",
				"<html></html>".getBytes(StandardCharsets.UTF_8)));

		Optional<MultipartFile> resolved = MultipartUtil.firstFilePart(request);

		assertTrue(resolved.isPresent());
		assertEquals("test-page.html", resolved.get().getOriginalFilename());
	}

	@Test
	void returnsEmptyWhenRequestCarriesOnlyFormFields() {
		MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
		request.addParameter("mimeType", "text/html");

		assertTrue(MultipartUtil.firstFilePart(request).isEmpty());
	}
}
