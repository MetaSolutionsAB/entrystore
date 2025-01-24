/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.repository.util;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileOperationsTest {

	private final String content = "foo\nbar";
	private final File tempFile = Files.createTempFile("temp", ".txt").toFile();

	private FileOperationsTest() throws IOException {
	}

	private static String readFileToString(File file) {

		StringBuilder fileContents = new StringBuilder();

		try {
			Scanner scanner = new Scanner(file);

			while (scanner.hasNextLine()) {
				fileContents.append(scanner.nextLine());
				if (scanner.hasNextLine()){
					fileContents.append('\n');
				}
			}

			scanner.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		return fileContents.toString();
	}

	@Test
	public void writeStringToFile_ok() throws IOException {
		FileOperations.writeStringToFile(tempFile, content);
		assertEquals(content, readFileToString(tempFile));
	}

	@Test
	public void writeStringToFile_nullException() {
		assertThrows(NullPointerException.class, () -> FileOperations.writeStringToFile(null, content));
	}

	@Test
	public void writeStringToFile_ioException() throws IOException {
		Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), "temp-" + UUID.randomUUID());
		File file = Files.createDirectories(path).toFile();
		assertThrows(IOException.class, () -> FileOperations.writeStringToFile(file, content));
	}

	@Test
	public void createTempDirectory_ok() throws IOException {
		File file = FileOperations.createTempDirectory("temp", "folder");
		assertThrows(IOException.class, () -> FileOperations.writeStringToFile(file, content));
		assertTrue(file.isDirectory());
	}

	@Test
	public void copyFile_streams_ok() throws IOException {
		long bytesToCopy = content.getBytes().length;
		InputStream inputStream = new ByteArrayInputStream(content.getBytes());
		try (OutputStream outputStream = new FileOutputStream(tempFile)) {
			long copiedBytes = FileOperations.copyFile(inputStream, outputStream);

			assertEquals(copiedBytes, bytesToCopy);
		}
	}

}
