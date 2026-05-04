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

package org.entrystore.repository.util;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileOperationsTest {

	private final String content = "foo\nbar";
	private final File tempFileSource = Files.createTempFile("tempSource", ".txt").toFile();
	private final File tempFileDestination = Files.createTempFile("tempDestination", ".txt").toFile();
	private final Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), "temp-" + UUID.randomUUID());
	private final File tempBadFile = Files.createDirectories(path).toFile();

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

	@BeforeEach
	public void setUp() {
		tempFileSource.deleteOnExit();
		tempFileDestination.deleteOnExit();
		tempBadFile.deleteOnExit();
	}

	@Test
	public void writeStringToFile_ok() throws IOException {
		FileOperations.writeStringToFile(tempFileSource, content);
		assertEquals(content, readFileToString(tempFileSource));
	}

	@Test
	public void writeStringToFile_nullException() {
		assertThrows(NullPointerException.class, () -> FileOperations.writeStringToFile(null, content));
	}

	@Test
	public void writeStringToFile_ioException() {
		assertThrows(IOException.class, () -> FileOperations.writeStringToFile(tempBadFile, content));
	}

	@Test
	public void writeStringToFile_fileNotFoundException() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		File subDirectory = new File(directory.getAbsolutePath() + "/subdirectory");
		File file = new File(subDirectory.getAbsolutePath() + "/testFile.txt");
		directory.deleteOnExit();
		subDirectory.deleteOnExit();
		file.deleteOnExit();
		assertThrows(FileNotFoundException.class, () -> FileOperations.writeStringToFile(file, content));
	}

	@Test
	public void createTempDirectory_ok() throws IOException {
		File file = FileOperations.createTempDirectory("temp", "folder");
		file.deleteOnExit();
		assertThrows(IOException.class, () -> FileOperations.writeStringToFile(file, content));
		assertTrue(file.isDirectory());
	}

	@Test
	public void copyFile_streams_ok() throws IOException {
		long bytesToCopy = content.getBytes().length;
		InputStream inputStream = new ByteArrayInputStream(content.getBytes());
		try (OutputStream outputStream = new FileOutputStream(tempFileSource)) {
			long copiedBytes = FileOperations.copyFile(inputStream, outputStream);
			assertEquals(copiedBytes, bytesToCopy);
		}
	}

	@Test
	public void copyFile_streams_emptyOk() throws IOException {
		long bytesToCopy = "".getBytes().length;
		InputStream inputStream = new ByteArrayInputStream("".getBytes());
		try (OutputStream outputStream = new FileOutputStream(tempFileSource)) {
			long copiedBytes = FileOperations.copyFile(inputStream, outputStream);
			assertEquals(copiedBytes, bytesToCopy);
		}
	}


	@Test
	public void copyFile_ok() throws IOException {
		FileOperations.writeStringToFile(tempFileSource, content);
		FileOperations.copyFile(tempFileSource, tempFileDestination);
		assertEquals(content, readFileToString(tempFileDestination));
	}


	@Test
	public void copyFile_exception() throws IOException {
		FileOperations.writeStringToFile(tempFileSource, content);
		assertThrows(IOException.class, () -> FileOperations.copyFile(tempFileSource, tempBadFile));
	}

	@Test
	public void deleteDirectory_ok() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		assertTrue(directory.exists());
		FileOperations.deleteDirectory(directory);
		assertFalse(directory.exists());
	}

	@Test
	public void deleteDirectory_subfilesOk() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		File file = new File(directory.getAbsolutePath() + "/testFile.txt");
		FileOperations.writeStringToFile(file, content);
		assertTrue(file.exists());
		FileOperations.deleteDirectory(directory);
		assertFalse(file.exists());
		assertFalse(directory.exists());
	}

	@Test
	public void deleteAllFilesInDir_ok() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		directory.deleteOnExit();
		assertTrue(directory.exists());
		FileOperations.deleteAllFilesInDir(directory);
		assertTrue(directory.exists());
	}

	@Test
	public void deleteAllFilesInDir_subfilesOk() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		File file = new File(directory.getAbsolutePath() + "/testFile.txt");
		directory.deleteOnExit();
		file.deleteOnExit();
		FileOperations.writeStringToFile(file, content);
		assertTrue(file.exists());
		FileOperations.deleteAllFilesInDir(directory);
		assertFalse(file.exists());
		assertTrue(directory.exists());
	}

	@Test
	public void listFiles_ok() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		File subDirectory = new File(directory, "subDirectory");
		directory.deleteOnExit();
		subDirectory.deleteOnExit();
		assertTrue(subDirectory.mkdirs());
		File file1 = new File(directory.getAbsolutePath() + "/testFile1.txt");
		File file2 = new File(directory.getAbsolutePath() + "/testFile2.txt");
		File file3 = new File(subDirectory.getAbsolutePath() + "/testFile3.txt");
		file1.deleteOnExit();
		file2.deleteOnExit();
		file3.deleteOnExit();
		FileOperations.writeStringToFile(file1, content);
		FileOperations.writeStringToFile(file2, content);
		FileOperations.writeStringToFile(file3, content);
		assertEquals(3, FileOperations.listFiles(directory).size());
	}

	@Test
	public void listDirectories_ok() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		File subDirectory1 = new File(directory, "subDirectory1");
		File subDirectory2 = new File(directory, "subDirectory2");
		directory.deleteOnExit();
		subDirectory1.deleteOnExit();
		subDirectory2.deleteOnExit();
		assertTrue(subDirectory1.mkdirs());
		assertTrue(subDirectory2.mkdirs());
		File file1 = new File(directory.getAbsolutePath() + "/testFile1.txt");
		file1.deleteOnExit();
		FileOperations.writeStringToFile(file1, content);
		assertEquals(2, FileOperations.listDirectories(directory).size());
	}

	@Test
	public void unzipFile_ok() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		directory.deleteOnExit();
		FileOperations.unzipFile(new File("src/test/resources/zipfile2.zip"), directory);
		assertEquals(6, FileOperations.listFiles(directory).size());
	}

	@Disabled
	@Test
	public void unzipFile_subfolderOk() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		directory.deleteOnExit();
		FileOperations.unzipFile(new File("src/test/resources/zipfile.zip"), directory);
		assertEquals(6, FileOperations.listFiles(directory).size());
	}

	@Test
	public void unzipFile_empty() throws IOException {
		File directory = FileOperations.createTempDirectory("temp", "folder");
		directory.deleteOnExit();
		FileOperations.unzipFile(tempFileSource, directory);
		assertEquals(0, FileOperations.listFiles(directory).size());
	}

	@Test
	public void unzipFile_exception() {
		assertThrows(IllegalArgumentException.class, () -> FileOperations.unzipFile(tempFileSource, tempFileSource));
	}

	@ParameterizedTest
	@ValueSource(strings = {"../evil.txt", "subdir/../../evil.txt", "a/b/c/../../../../../../../evil.txt"})
	public void unzipFile_rejectsZipSlipEntry(String entryName) throws IOException {
		File destDir = FileOperations.createTempDirectory("temp", "folder");
		destDir.deleteOnExit();
		IOException ex = assertThrows(IOException.class, () -> FileOperations.unzipFile(createZipWithEntry(entryName), destDir));
		assertTrue(ex.getMessage().contains("escapes destination directory"));
		assertFalse(new File(destDir.getParentFile(), "evil.txt").exists());
	}

	@Test
	public void unzipFile_zipSlip_directoryEscape() throws IOException {
		File destDir = FileOperations.createTempDirectory("temp", "folder");
		destDir.deleteOnExit();
		IOException ex = assertThrows(IOException.class, () -> FileOperations.unzipFile(createZipWithEntry("../escapedDir/"), destDir));
		assertTrue(ex.getMessage().contains("escapes destination directory"));
		assertFalse(new File(destDir.getParentFile(), "escapedDir").exists());
	}

	@Test
	public void unzipFile_zipSlip_siblingPrefix() throws IOException {
		File destDir = FileOperations.createTempDirectory("entrystore-test-", "dest");
		destDir.deleteOnExit();
		// Entry resolves to a sibling directory that shares destDir's string prefix — guards based on
		// string startsWith (without trailing separator) would incorrectly allow this through.
		String siblingEntry = "../" + destDir.getName() + "extra/evil.txt";
		IOException ex = assertThrows(IOException.class, () -> FileOperations.unzipFile(createZipWithEntry(siblingEntry), destDir));
		assertTrue(ex.getMessage().contains("escapes destination directory"));
		assertFalse(new File(destDir.getParentFile(), destDir.getName() + "extra").exists());
	}

	@Test
	public void unzipFile_nestedPath_ok() throws IOException {
		File destDir = FileOperations.createTempDirectory("temp", "folder");
		destDir.deleteOnExit();
		File zipFile = createZipWithEntry("subdir/safe.txt");
		assertDoesNotThrow(() -> FileOperations.unzipFile(zipFile, destDir));
		assertEquals(1, FileOperations.listFiles(destDir).size());
	}

	private File createZipWithEntry(String entryName) throws IOException {
		File zipFile = File.createTempFile("zipslip-test", ".zip");
		zipFile.deleteOnExit();
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
			zos.putNextEntry(new ZipEntry(entryName));
			zos.write("payload".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return zipFile;
	}

}
