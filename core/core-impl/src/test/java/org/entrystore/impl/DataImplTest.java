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

package org.entrystore.impl;

import org.apache.commons.codec.digest.DigestUtils;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataImplTest extends AbstractCoreTest {

	@TempDir
	Path tempDataDir;

	private Entry contextEntry;
	private Context context;
	private Entry entry;
	private Data data;
	private final DataImpl.FileMover originalFileMover = DataImpl.fileMover;

	@BeforeEach
	@Override
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(true);

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		contextEntry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) contextEntry.getResource();
		entry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		data = (Data) entry.getResource();
	}

	@Disabled("To be implemented")
	@Test
	public void testGetData() throws Exception {
		// TODO
	}

	@Test
	public void setData_replacesThePreviousDataAndDigest() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("old content".getBytes(StandardCharsets.UTF_8)));

		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));

		assertEquals("test content", Files.readString(data.getDataFile().toPath()));
		// SHA-256 of "test content"
		assertEquals("6ae8a75555209fd6c44157c0aed8016e763ff435a19cf186f76863140143ff72",
				((DataImpl) data).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void setData_failingStream_keepsThePreviousDataAndDigest() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		InputStream abortedUpload = new SequenceInputStream(new ByteArrayInputStream(new byte[100_000]),
				new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("client went away");
					}
				});

		assertThrows(IOException.class, () -> data.setData(abortedUpload));

		assertEquals("test content", Files.readString(data.getDataFile().toPath()));
		assertEquals("6ae8a75555209fd6c44157c0aed8016e763ff435a19cf186f76863140143ff72",
				((DataImpl) data).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void setData_writesToDiskWhileTheStreamIsRead() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		long size = 8L * 1024 * 1024;
		InputStream lockstepBody = new InputStream() {
			private long delivered;

			@Override
			public int read() {
				throw new UnsupportedOperationException();
			}

			@Override
			public int read(byte[] buf, int off, int len) throws IOException {
				long onDisk = 0;
				for (Path part : partFiles()) {
					onDisk += Files.size(part);
				}
				if (delivered - onDisk > 64 * 1024) {
					throw new AssertionError("the data is being buffered: " + delivered + " bytes read, "
							+ onDisk + " on disk");
				}
				if (delivered == size) {
					return -1;
				}
				int read = (int) Math.min(len, size - delivered);
				delivered += read;
				return read;
			}
		};

		data.setData(lockstepBody);

		assertEquals(size, data.getDataFile().length());
	}

	@Test
	public void setData_entryWithALongId_isStored() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		// Leaves room for the digest suffix within the usual 255-byte name limit, but not for one more UUID.
		Entry longIdEntry = context.createResource("x".repeat(245), GraphType.None, ResourceType.InformationResource,
				null);
		Data longIdData = (Data) longIdEntry.getResource();

		longIdData.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));

		assertEquals("test content", Files.readString(longIdData.getDataFile().toPath()));
		assertEquals(DigestUtils.sha256Hex("test content"), ((DataImpl) longIdData).readDigest());
	}

	@Test
	public void setData_interruptedBeforeTheDigestIsMoved_leavesTheNewDataWithoutTheOldDigest() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("old content".getBytes(StandardCharsets.UTF_8)));
		Path digest = Path.of(data.getDataFile().getCanonicalPath() + DataImpl.SHA_256_POSTFIX);
		failMovesTo(DataImpl.SHA_256_POSTFIX);

		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));

		assertEquals("test content", Files.readString(data.getDataFile().toPath()));
		assertFalse(Files.exists(digest));
		assertNull(((DataImpl) data).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void setData_interruptedBeforeTheDataIsMoved_keepsTheOldDataWithoutADigest() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("old content".getBytes(StandardCharsets.UTF_8)));
		failMovesTo(entry.getId());

		assertThrows(IOException.class,
				() -> data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8))));

		assertEquals("old content", Files.readString(data.getDataFile().toPath()));
		assertNull(((DataImpl) data).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void setData_overlappingWrites_endWithTheDataAndDigestOfTheLastToComplete() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		CountDownLatch slowWriteStarted = new CountDownLatch(1);
		CountDownLatch fastWriteDone = new CountDownLatch(1);
		InputStream slowBody = new SequenceInputStream(
				new ByteArrayInputStream("slow ".getBytes(StandardCharsets.UTF_8)),
				new InputStream() {
					private final InputStream rest = new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));

					@Override
					public int read() throws IOException {
						slowWriteStarted.countDown();
						try {
							assertTrue(fastWriteDone.await(10, TimeUnit.SECONDS));
						} catch (InterruptedException e) {
							throw new IOException(e);
						}
						return rest.read();
					}
				});
		URI admin = pm.getAuthenticatedUserURI();
		CompletableFuture<Void> slowWrite = CompletableFuture.runAsync(() -> {
			pm.setAuthenticatedUserURI(admin);
			try {
				data.setData(slowBody);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertTrue(slowWriteStarted.await(10, TimeUnit.SECONDS));

		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		fastWriteDone.countDown();
		slowWrite.get(10, TimeUnit.SECONDS);

		assertEquals("slow content", Files.readString(data.getDataFile().toPath()));
		assertEquals(DigestUtils.sha256Hex("slow content"), ((DataImpl) data).readDigest());
	}

	@Test
	public void setData_entryRemovedAndRecreatedDuringTheUpload_leavesTheNewEntrysFileUntouched() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		CountDownLatch uploadStarted = new CountDownLatch(1);
		CountDownLatch entryRecreated = new CountDownLatch(1);
		InputStream pausedBody = new SequenceInputStream(
				new ByteArrayInputStream("stale ".getBytes(StandardCharsets.UTF_8)),
				new InputStream() {
					private final InputStream rest = new ByteArrayInputStream("upload".getBytes(StandardCharsets.UTF_8));

					@Override
					public int read() throws IOException {
						uploadStarted.countDown();
						try {
							assertTrue(entryRecreated.await(10, TimeUnit.SECONDS));
						} catch (InterruptedException e) {
							throw new IOException(e);
						}
						return rest.read();
					}
				});
		URI admin = pm.getAuthenticatedUserURI();
		CompletableFuture<Void> staleUpload = CompletableFuture.runAsync(() -> {
			pm.setAuthenticatedUserURI(admin);
			try {
				data.setData(pausedBody);
			} catch (IOException | QuotaException e) {
				throw new CompletionException(e);
			}
		});
		assertTrue(uploadStarted.await(10, TimeUnit.SECONDS));

		context.remove(entry.getEntryURI());
		Entry recreated = context.createResource(entry.getId(), GraphType.None, ResourceType.InformationResource, null);
		Data recreatedData = (Data) recreated.getResource();
		recreatedData.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		entryRecreated.countDown();

		ExecutionException failure = assertThrows(ExecutionException.class, () -> staleUpload.get(10, TimeUnit.SECONDS));
		assertInstanceOf(DataImpl.EntryRemovedException.class, failure.getCause());
		assertEquals("test content", Files.readString(recreatedData.getDataFile().toPath()));
		assertEquals(DigestUtils.sha256Hex("test content"), ((DataImpl) recreatedData).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void setData_replacementAboveTheQuota_keepsThePreviousDataAndDigest() throws Exception {
		rm.shutdown();
		setUpEnvironment(Map.of(Settings.DATA_QUOTA, "on", Settings.DATA_FOLDER, tempDataDir.toString()));
		TestSuite.initDisneySuite(rm);
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Context quotaContext = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
		quotaContext.setQuota(20);
		Data quotaData = (Data) quotaContext.createResource(null, GraphType.None, ResourceType.InformationResource,
				null).getResource();
		quotaData.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));

		assertThrows(QuotaException.class, () -> quotaData.setData(new ByteArrayInputStream(new byte[30])));

		assertEquals("test content", Files.readString(quotaData.getDataFile().toPath()));
		assertEquals(DigestUtils.sha256Hex("test content"), ((DataImpl) quotaData).readDigest());
		assertEquals(List.of(), partFiles());
	}

	@Test
	public void deleteStaleStagingFiles_deletesOnlyStagingFiles() throws Exception {
		Path contextFolder = Files.createDirectories(tempDataDir.resolve("1"));
		Files.writeString(contextFolder.resolve("2"), "data");
		Files.writeString(contextFolder.resolve("2" + DataImpl.SHA_256_POSTFIX), "digest");
		// The file of an entry whose id resembles a staging name; ids created through core are not restricted.
		Files.writeString(contextFolder.resolve(".document.part"), "data");
		Files.writeString(contextFolder.resolve("." + UUID.randomUUID() + DataImpl.STAGING_POSTFIX), "partial");

		DataImpl.deleteStaleStagingFiles("file://" + tempDataDir);

		try (Stream<Path> files = Files.list(contextFolder)) {
			assertEquals(Set.of("2", "2" + DataImpl.SHA_256_POSTFIX, ".document.part"),
					files.map(f -> f.getFileName().toString()).collect(Collectors.toSet()));
		}
	}

	@AfterEach
	public void restoreFileMover() {
		DataImpl.fileMover = originalFileMover;
	}

	/** Makes moves onto a name ending with {@code suffix} fail, as an interruption before them would. */
	private void failMovesTo(String suffix) {
		DataImpl.fileMover = (source, target) -> {
			if (target.getFileName().toString().endsWith(suffix)) {
				throw new IOException("interrupted");
			}
			originalFileMover.move(source, target);
		};
	}

	/** The staging files that a write in progress, or a failed one left behind, uses in the data folder. */
	private List<Path> partFiles() throws IOException {
		try (Stream<Path> files = Files.walk(tempDataDir)) {
			return files.filter(f -> DataImpl.isStagingFile(f.getFileName().toString())).toList();
		}
	}

	@Disabled("To be implemented")
	@Test
	public void testUseData() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemove() throws Exception {
		// TODO
	}

	@Test
	public void delete_throwsForGuest() {
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		assertThrows(AuthorizationException.class, () -> data.delete());
	}

	@Test
	public void delete_returnsFalseWhenNoFile() {
		assertFalse(data.delete());
	}

	@Test
	public void delete_doesNotThrowForGrantedUser() {
		Entry mickey = pm.getPrincipalEntry("Mickey");
		entry.addAllowedPrincipalsFor(AccessProperty.WriteResource, mickey.getResourceURI());
		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		assertFalse(data.delete());
	}

	@Test
	public void delete_returnsTrueWhenFileExists() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		assertTrue(data.delete());
		assertFalse(data.delete());
	}

	@Test
	public void remove_bypassesAuthCheck() {
		Entry mickey = pm.getPrincipalEntry("Mickey");
		// Mickey can manage the context but has no WriteResource on the data entry itself.
		// remove(RepositoryConnection) must not check entry-level auth — only delete() does.
		contextEntry.addAllowedPrincipalsFor(AccessProperty.WriteResource, mickey.getResourceURI());
		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		assertDoesNotThrow(() -> context.remove(entry.getEntryURI()));
	}

	@Test
	public void readDigest_throwsForGuestWhenThereIsNoFile() {
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		assertThrows(AuthorizationException.class, () -> ((DataImpl) data).readDigest());
	}

	@Test
	public void readDigest_throwsForGuestWhenThereIsAFile() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		assertThrows(AuthorizationException.class, () -> ((DataImpl) data).readDigest());
	}

	@Test
	public void readDigest_returnsTheDigestToAReader() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		Entry mickey = pm.getPrincipalEntry("Mickey");
		entry.addAllowedPrincipalsFor(AccessProperty.ReadResource, mickey.getResourceURI());
		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		// SHA-256 of "test content"
		assertEquals("6ae8a75555209fd6c44157c0aed8016e763ff435a19cf186f76863140143ff72",
				((DataImpl) data).readDigest());
	}

	@Disabled("To be implemented")
	@Test
	public void testGetDataFile() throws Exception {
		// TODO
	}

}
