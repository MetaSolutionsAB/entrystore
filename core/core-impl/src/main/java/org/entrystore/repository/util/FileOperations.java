/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Commonly used file operations.
 *
 * @author Hannes Ebner
 * @version $Id$
 */
public class FileOperations {

	private final static Logger log = LoggerFactory.getLogger(FileOperations.class);

	private final static int BUFFER_SIZE = 8192;

	// Non instantiable utility class
	private FileOperations() {
		throw new AssertionError();
	}

	/**
	 * Moves a file from one location to another. If the file cannot be moved
	 * (e.g., because source and destination are in a different file system), it
	 * is copied instead. The source file is removed then.
	 *
	 * @param source      File to move
	 * @param destination Destination file
	 * @throws IOException
	 */
	public static void moveFile(File source, File destination) throws IOException {
		if (source == null || destination == null) {
			throw new IllegalArgumentException("Parameters cannot be null");
		}

		if (source.equals(destination)) {
			throw new IllegalArgumentException("Cannot move file to itself");
		}

		log.debug("Moving file {} to {}", source, destination);
		if (!source.renameTo(destination)) {
			copyFile(source, destination);
			if (!source.delete()) {
				log.warn("Unable to delete source file");
			}
		}
	}

	/**
	 * Copies a File using Java NIO Channels.
	 *
	 * @param src Source file
	 * @param dst Destination file
	 * @throws IOException
	 */
	public static void copyFile(File src, File dst) throws IOException {
		FileChannel sourceChannel = null;
		FileChannel destinationChannel = null;
		boolean copied = false;
		try {
			sourceChannel = new FileInputStream(src).getChannel();
			destinationChannel = new FileOutputStream(dst).getChannel();
			long copiedSize = sourceChannel.transferTo(0, sourceChannel.size(), destinationChannel);
			if (sourceChannel.size() == copiedSize) {
				copied = true;
			}
		} finally {
			if (sourceChannel != null) {
				sourceChannel.close();
			}
			if (destinationChannel != null) {
				destinationChannel.close();
			}
		}

		if (!copied) {
			log.warn("File copying failed using NIO, performing traditional copy using streams instead");
			copyFile(Files.newInputStream(src.toPath()), Files.newOutputStream(dst.toPath()));
		}
	}

	public static void fastChannelCopy(final ReadableByteChannel src, final WritableByteChannel dest) throws IOException {
		final ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);
		while (src.read(buffer) != -1) {
			buffer.flip();
			dest.write(buffer);
			buffer.compact();
		}
		buffer.flip();
		while (buffer.hasRemaining()) {
			dest.write(buffer);
		}
	}

	/**
	 * Copies the contents of an InputStream to an OutputStream. Closes both
	 * streams after everything has gone well. Using buffered streams internally.
	 *
	 * @param is Source InputStream
	 * @param os Destination OutputStream
	 * @throws IOException
	 */
	public static long copyFile(InputStream is, OutputStream os) throws IOException {
		BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE);
		BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
		long bytesCopied = 0;
		byte[] b = new byte[BUFFER_SIZE];
		int s;
		try {
			while ((s = bis.read(b)) != -1) {
				bos.write(b, 0, s);
				bytesCopied += s;
			}
		} catch (NullPointerException npe) {
			throw new IOException(npe);
		}
		bis.close();
		bos.close();
		return bytesCopied;
	}

	/**
	 * Deletes all files in a given directory, but does not remove the directory
	 * itself. Does not delete files in subdirectories.
	 *
	 * @param dir Directory to be cleaned.
	 * @return True if successful for all files.
	 */
	public static boolean deleteAllFilesInDir(File dir) {
		File file;
		if (dir.isDirectory()) {
			String[] children = dir.list();
			if (children != null) {
				for (String child : children) {
					file = new File(dir, child);
					if (file.isFile()) {
						if (!file.delete()) {
							log.warn("Could not delete file {}", file);
							return false;
						}
						log.debug("Deleted file {}", file);
					}
				}
			}
		}
		return true;
	}

	/**
	 * Deletes all files and subdirectories in a given directory.
	 *
	 * @param path Directory to be deleted.
	 * @return True if successful.
	 */
	public static boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isDirectory()) {
						deleteDirectory(file);
					} else {
						file.delete();
					}
				}
			}
		}
		return path.delete();
	}

	/**
	 * Lists all files from a directory and its subdirectories.
	 *
	 * @param folder Folder
	 * @return Returns a list of files. Does not contain folders.
	 */
	public static List<File> listFiles(File folder) {
		List<File> result = new ArrayList<>();
		if (!folder.isDirectory()) {
			result.add(folder);
		} else {
			File[] fileArray = folder.listFiles();
			if (fileArray != null) {
				for (File file : fileArray) {
					if (file.isFile()) {
						result.add(file);
					} else if (file.isDirectory()) {
						result.addAll(listFiles(file));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Lists all subdirectories (and their subdirectories; recursively) of a
	 * directory.
	 *
	 * @param folder Folder
	 * @return Returns a list of folders. Does not contain files.
	 */
	public static List<File> listDirectories(File folder) {
		if (!folder.isDirectory()) {
			throw new IllegalArgumentException("Parameter is not a folder");
		}

		List<File> result = new ArrayList<>();
		File[] fileArray = folder.listFiles();
		if (fileArray != null) {
			for (File file : fileArray) {
				if (file.isDirectory()) {
					result.add(file);
					result.addAll(listFiles(file));
				}
			}
		}

		return result;
	}

	/**
	 * Zips a folder and its subfolders.
	 *
	 * @param zipFile   Destination ZIP file. Is created if it does not exist.
	 * @param directory The directory to be zipped.
	 * @param base      Base File path to strip off the zipped files. May be null.
	 * @return Returns a CRC32 checksum of the zipped file.
	 * @throws IOException
	 */
	public static long zipDirectory(File zipFile, File directory, File base) throws IOException {
		return zipFiles(zipFile, listFiles(directory), base);
	}

	/**
	 * Zips a set of files.
	 *
	 * @param zipFile Destination ZIP file. Is created if it does not exist.
	 * @param files   List of files to add to the ZIP file.
	 * @param base    Base File path to strip off the zipped files. May be null.
	 * @return Returns a CRC32 checksum of the zipped file.
	 * @throws IOException
	 */
	public static long zipFiles(File zipFile, List<File> files, File base) throws IOException {
		OutputStream fos = Files.newOutputStream(zipFile.toPath());
		CheckedOutputStream cos = new CheckedOutputStream(fos, new CRC32());
		BufferedOutputStream bos = new BufferedOutputStream(cos, BUFFER_SIZE);
		ZipOutputStream zos = new ZipOutputStream(bos);
		byte[] data = new byte[BUFFER_SIZE];
		for (File file : files) {
			InputStream fis = Files.newInputStream(file.toPath());
			BufferedInputStream source = new BufferedInputStream(fis);
			String entryPath = file.getPath();
			if (base != null) {
				entryPath = entryPath.replace(base.getPath(), "");
			}
			ZipEntry entry = new ZipEntry(entryPath);
			log.debug("Adding file: {}", file.getPath());
			zos.putNextEntry(entry);
			int count;
			while ((count = source.read(data, 0, BUFFER_SIZE)) != -1) {
				zos.write(data, 0, count);
			}
			source.close();
		}
		zos.close();

		return cos.getChecksum().getValue();
	}

	/**
	 * Unzips a file into a directory. Subfolders are created if necessary.
	 *
	 * @param zipFile     ZIP file to be used as a source.
	 * @param destination Destination folder
	 * @return Returns a CRC32 checksum of the ZIP file.
	 * @throws IOException
	 * @throws IllegalArgumentException If destination is not a folder.
	 */
	public static long unzipFile(File zipFile, File destination) throws IOException {
		if (!destination.isDirectory()) {
			throw new IllegalArgumentException("Destination is not a folder");
		}

		InputStream fis = Files.newInputStream(zipFile.toPath());
		CheckedInputStream cis = new CheckedInputStream(fis, new CRC32());
		BufferedInputStream bis = new BufferedInputStream(cis, BUFFER_SIZE);
		ZipInputStream zis = new ZipInputStream(bis);
		ZipEntry entry;
		while ((entry = zis.getNextEntry()) != null) {
			log.debug("Extracting file: {}", entry.getName());
			int count;
			byte[] data = new byte[BUFFER_SIZE];
			File unzippedFile = new File(destination, entry.getName());
			File parentDir = unzippedFile.getParentFile();
			if (!parentDir.exists()) {
				log.debug("Creating directory: {}", parentDir);
				parentDir.mkdirs();
			}
			OutputStream fos = Files.newOutputStream(unzippedFile.toPath());
			BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE);
			while ((count = zis.read(data, 0, BUFFER_SIZE)) != -1) {
				bos.write(data, 0, count);
			}
			bos.flush();
			bos.close();
		}
		zis.close();

		return cis.getChecksum().getValue();
	}

	/**
	 * Creates an empty file in the default temporary-file directory, using the
	 * given prefix and suffix to generate its name.
	 *
	 * @param prefix The prefix string to be used in generating the folder's name;
	 *               must be at least three characters long
	 * @param suffix The suffix string to be used in generating the folder's name;
	 *               may be <code>null</code>, in which case the suffix
	 *               <code>".tmp"</code> will be used
	 * @return An abstract pathname denoting a newly created empty folder
	 * @throws IllegalArgumentException If the <code>prefix</code> argument contains fewer than
	 *                                  three characters
	 * @throws IOException              If a folder could not be created
	 */
	public static File createTempDirectory(String prefix, String suffix) throws IOException {
		File tempFile = File.createTempFile(prefix, suffix);
		tempFile.delete();
		tempFile.mkdir();
		return tempFile;
	}

	public static void copyPath(Path src, Path dst) throws IOException {
		Files.walkFileTree(src, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
				throws IOException {
				Files.createDirectories(dst.resolve(src.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
				throws IOException {
				Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	@Deprecated
	public static void copyDirectory(File srcPath, File dstPath) throws IOException {
		if (srcPath.isDirectory()) {
			if (!dstPath.exists()) {
				dstPath.mkdir();
			}

			String[] files = srcPath.list();
			if (files != null) {
				for (String file : files) {
					copyDirectory(new File(srcPath, file), new File(dstPath, file));
				}
			}
		} else {
			if (!srcPath.exists()) {
				log.error("{}: file or directory does not exist.", srcPath);
			} else {
				InputStream in = null;
				OutputStream out = null;
				try {
					in = Files.newInputStream(srcPath.toPath());
					out = Files.newOutputStream(dstPath.toPath());
					// Transfer bytes from in to out
					byte[] buffer = new byte[1024];
					int len;

					while ((len = in.read(buffer)) > 0) {
						out.write(buffer, 0, len);
					}
				} finally {
					if (in != null) {
						in.close();
					}
					if (out != null) {
						out.close();
					}
				}
			}
		}
	}

	public static void writeStringToFile(File file, String content) throws IOException {
		if (file == null) {
			throw new NullPointerException("file to write to is null");
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
			writer.write(content);
		}
	}

}
