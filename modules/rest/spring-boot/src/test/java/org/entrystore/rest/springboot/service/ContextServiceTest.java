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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void addZipEntry_writesEntryWithNameAndContent() throws IOException {
		Path source = tempDir.resolve("payload.txt");
		Files.writeString(source, "hello zip");
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();

		try (ZipOutputStream zipOS = new ZipOutputStream(zipBytes)) {
			ContextService.addZipEntry(zipOS, "resources/payload.txt", source.toFile());
		}

		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes.toByteArray()))) {
			ZipEntry entry = zis.getNextEntry();
			assertEquals("resources/payload.txt", entry.getName());
			assertEquals("hello zip", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void addZipEntry_multipleEntries_allWrittenInOrder() throws IOException {
		Path first = tempDir.resolve("first.txt");
		Path second = tempDir.resolve("second.txt");
		Files.writeString(first, "first content");
		Files.writeString(second, "second content");
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();

		try (ZipOutputStream zipOS = new ZipOutputStream(zipBytes)) {
			ContextService.addZipEntry(zipOS, "first.txt", first.toFile());
			ContextService.addZipEntry(zipOS, "second.txt", second.toFile());
		}

		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes.toByteArray()))) {
			assertEquals("first.txt", zis.getNextEntry().getName());
			assertEquals("first content", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("second.txt", zis.getNextEntry().getName());
			assertEquals("second content", new String(zis.readAllBytes(), StandardCharsets.UTF_8));
			assertNull(zis.getNextEntry(), "no further entries expected");
		}
	}
}
