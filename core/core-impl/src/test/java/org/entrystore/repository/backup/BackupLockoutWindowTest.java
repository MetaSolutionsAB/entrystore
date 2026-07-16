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

package org.entrystore.repository.backup;

import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * ENTRYSTORE-1086 (E3): the backup write lockout (503 for all writes) must cover only the RDF
 * export phase, not the data-folder copy — the copy can take minutes and only the RDF dump needs
 * a write-quiescent repository.
 */
public class BackupLockoutWindowTest {

	private RepositoryManagerImpl rm;
	private Path backupDir;
	private Path dataDir;

	@BeforeEach
	public void setUp() throws Exception {
		backupDir = Files.createTempDirectory("entrystore-backup-test");
		dataDir = Files.createTempDirectory("entrystore-data-test");
		Files.writeString(dataDir.resolve("some-upload.bin"), "payload");

		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.BACKUP_FOLDER, backupDir.toString());
		config.setProperty(Settings.DATA_FOLDER, dataDir.toString());
		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
	}

	@AfterEach
	public void tearDown() {
		rm.shutdown();
		FileOperations.deleteDirectory(backupDir.toFile());
		FileOperations.deleteDirectory(dataDir.toFile());
	}

	@Test
	public void lockoutIsReleasedBeforeDataFolderCopy() throws Exception {
		JobDataMap dataMap = new JobDataMap();
		dataMap.put("rm", rm);
		dataMap.put("gzip", false);
		dataMap.put("includeFiles", true);
		dataMap.put("simple", false);
		dataMap.put("deleteAfter", false);

		JobExecutionContext jobContext = mock(JobExecutionContext.class);
		JobDetail jobDetail = mock(JobDetail.class);
		when(jobContext.getJobDetail()).thenReturn(jobDetail);
		when(jobDetail.getJobDataMap()).thenReturn(dataMap);

		AtomicBoolean copyObserved = new AtomicBoolean(false);
		AtomicBoolean lockedDuringCopy = new AtomicBoolean(true);

		try (MockedStatic<FileOperations> fileOps = mockStatic(FileOperations.class, CALLS_REAL_METHODS)) {
			fileOps.when(() -> FileOperations.copyPath(any(Path.class), any(Path.class)))
					.thenAnswer(invocation -> {
						copyObserved.set(true);
						lockedDuringCopy.set(rm.hasModificationLockOut());
						return invocation.callRealMethod();
					});

			BackupJob.runBackup(jobContext);
		}

		assertTrue(copyObserved.get(), "the data-folder copy must have run (includeFiles=true)");
		assertFalse(lockedDuringCopy.get(),
				"the write lockout must be released before the data-folder copy starts");
		assertFalse(rm.hasModificationLockOut(), "the lockout must be off after the backup");
		assertTrue(new File(backupDir.toFile(), "all").exists() || backupDir.toFile().listFiles().length > 0,
				"a backup directory must have been produced");
	}
}
