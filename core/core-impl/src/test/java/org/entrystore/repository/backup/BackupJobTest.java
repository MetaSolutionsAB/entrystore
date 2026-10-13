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

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.impl.AbstractCoreTest;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A backup is marked with BACKUP_DATE only if it completed. A failed one is marked with BACKUP_FAILED, leaves no
 * partial dump and never costs the previous completed backup.
 */
public class BackupJobTest extends AbstractCoreTest {

	private static final String DUMP = "repository." + RDFFormat.TRIX.getDefaultFileExtension();

	private static final String MARKER = "earlier-backup";

	private static final String BACKUP_DATE = "BACKUP_DATE";

	private static final String BACKUP_FAILED = "BACKUP_FAILED";

	@TempDir
	Path backupFolder;

	@BeforeEach
	@Override
	public void setUp() {
		setUpEnvironment(Map.of(Settings.BACKUP_FOLDER, backupFolder.toString()));
	}

	@Test
	public void interruptExecute() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> job.execute(null));
		assertEquals("Backup job \"execute()\" was interrupted", ex.getMessage());
	}

	@Test
	public void interruptRunBackup() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> BackupJob.runBackup(null));
		assertEquals("Backup job \"runBackup()\" was interrupted", ex.getMessage());
	}

	@Test
	public void interruptRunBackupMaintenance() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> BackupJob.runBackupMaintenance(null));
		assertEquals("Backup job \"runBackupMaintenance()\" was interrupted", ex.getMessage());
	}

	@Test
	public void failedExportMarksTheBackupFailedAndLeavesNoDump() throws Exception {
		JobDataMap data = jobData(withFailingExport(rm), false, false);

		boolean succeeded = BackupJob.runBackup(jobContext(data));

		assertFalse(succeeded);
		Path backup = onlyTimestampedBackup();
		assertMarkedFailed(backup);
		assertTrue(Files.readString(backup.resolve(BACKUP_FAILED)).contains("No space left on device"));
		assertFalse(Files.exists(backup.resolve(DUMP)), "a partly written dump must not be left behind");
	}

	@Test
	public void completedBackupIsMarkedWithItsDate() throws Exception {
		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, false, false)));

		assertTrue(succeeded);
		Path backup = onlyTimestampedBackup();
		assertMarkedCompleted(backup);
		assertTrue(Files.size(backup.resolve(DUMP)) > 0);
	}

	@Test
	public void failedCopyOfTheDataFolderMarksTheBackupFailed(@TempDir Path dataFolder) throws Exception {
		// the data folder is copied next to the dump, where a directory of the same name cannot be created
		Files.createDirectory(dataFolder.resolve(DUMP));
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		JobDataMap data = jobData(rm, false, false);
		data.put("includeFiles", true);

		boolean succeeded = BackupJob.runBackup(jobContext(data));

		assertFalse(succeeded);
		assertMarkedFailed(onlyTimestampedBackup());
	}

	@Test
	public void simpleBackupKeepsThePreviousBackupWhenTheExportFails() throws Exception {
		completedBackup(backupFolder.resolve("all"));
		JobDataMap data = jobData(withFailingExport(rm), true, true);

		boolean succeeded = BackupJob.runBackup(jobContext(data));

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all-old").resolve(MARKER)),
				"the previous backup must survive a failed one");
		assertMarkedFailed(backupFolder.resolve("all"));
	}

	@Test
	public void simpleBackupReplacesAFailedBackupInsteadOfRotatingItOverTheCompletedOne() throws Exception {
		completedBackup(backupFolder.resolve("all-old"));
		Path failed = Files.createDirectories(backupFolder.resolve("all"));
		// as written by earlier versions, which marked a failed backup with both files
		Files.writeString(failed.resolve(BACKUP_DATE), "20200102000000");
		Files.writeString(failed.resolve(BACKUP_FAILED), "20200102000000\nNo space left on device");
		JobDataMap data = jobData(withFailingExport(rm), true, true);

		boolean succeeded = BackupJob.runBackup(jobContext(data));

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all-old").resolve(MARKER)),
				"the last completed backup must survive consecutive failures");
		assertFalse(Files.exists(backupFolder.resolve("all-old").resolve(BACKUP_FAILED)));
		assertMarkedFailed(backupFolder.resolve("all"));
	}

	@Test
	public void simpleBackupKeepsABackupFromAnEarlierVersionWhenNoCompletedOneIsLeft() throws Exception {
		// earlier versions deleted "all-old" after every run and marked a backup whose data copy failed with both files
		completedBackup(backupFolder.resolve("all"));
		Files.writeString(backupFolder.resolve("all").resolve(BACKUP_FAILED), "20200101000000\nAccessDeniedException");

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(withFailingExport(rm), true, true)));

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all-old").resolve(MARKER)),
				"a backup must not be deleted before the run while no completed one is left");
		assertMarkedFailed(backupFolder.resolve("all"));
	}

	@Test
	public void simpleBackupWithDeleteAfterReplacesAFailedBackupOnceItCompletes() throws Exception {
		failedBackup(backupFolder.resolve("all"));
		completedBackup(backupFolder.resolve("all-old"));

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, true)));

		assertTrue(succeeded);
		assertMarkedCompleted(backupFolder.resolve("all"));
		assertFalse(Files.exists(backupFolder.resolve("all-old")));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	public void simpleBackupLeavesTheCompletedBackupUntouchedWhenItCannotRotateIt() throws Exception {
		completedBackup(backupFolder.resolve("all"));
		// a directory cannot be renamed onto a non-directory, also as root
		Files.createSymbolicLink(backupFolder.resolve("all-old"), backupFolder.resolve("missing"));

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, true)));

		assertFalse(succeeded);
		assertEquals("20200101000000", Files.readString(backupFolder.resolve("all").resolve(BACKUP_DATE)));
		assertTrue(Files.exists(backupFolder.resolve("all").resolve(MARKER)));
		assertFalse(Files.exists(backupFolder.resolve("all").resolve(BACKUP_FAILED)));
	}

	@Test
	public void simpleBackupRotatesOverAFileNamedAllOld() throws Exception {
		completedBackup(backupFolder.resolve("all"));
		Files.writeString(backupFolder.resolve("all-old"), "stray file");

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, true)));

		assertTrue(succeeded);
		assertMarkedCompleted(backupFolder.resolve("all"));
	}

	@Test
	public void simpleBackupFailsWhenItCannotDeleteThePreviousBackup() throws Exception {
		boolean succeeded;
		try (MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
			// stubbed before "all" exists, as stubbing with CALLS_REAL_METHODS runs the real delete once
			fileUtils.when(() -> FileUtils.deleteDirectory(backupFolder.resolve("all").toFile()))
					.thenThrow(new IOException("simulated delete failure"));
			completedBackup(backupFolder.resolve("all"));

			succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, false)));
		}

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all").resolve(BACKUP_FAILED)));
		assertFalse(Files.exists(backupFolder.resolve("all").resolve(DUMP)),
				"the run must not write into the previous backup");
	}

	@Test
	public void simpleBackupReplacesAnUnmarkedBackupLeftByAKilledRun() throws Exception {
		completedBackup(backupFolder.resolve("all-old"));
		Files.writeString(Files.createDirectories(backupFolder.resolve("all")).resolve(DUMP), "<TriX>");

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(withFailingExport(rm), true, true)));

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all-old").resolve(MARKER)),
				"an unmarked backup must not be rotated over the last completed one");
		assertMarkedFailed(backupFolder.resolve("all"));
	}

	@Test
	public void simpleBackupDeletesThePreviousBackupOnceTheNewOneIsComplete() throws Exception {
		completedBackup(backupFolder.resolve("all"));

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, true)));

		assertTrue(succeeded);
		assertMarkedCompleted(backupFolder.resolve("all"));
		assertFalse(Files.exists(backupFolder.resolve("all").resolve(MARKER)));
		assertFalse(Files.exists(backupFolder.resolve("all-old")));
	}

	@Test
	public void simpleBackupWithoutDeleteAfterReplacesAFailedBackup() throws Exception {
		failedBackup(backupFolder.resolve("all"));
		completedBackup(backupFolder.resolve("all-old"));

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(rm, true, false)));

		assertTrue(succeeded);
		assertMarkedCompleted(backupFolder.resolve("all"));
		assertFalse(Files.exists(backupFolder.resolve("all-old")));
	}

	@Test
	public void failedExportStillCopiesTheDataFolder(@TempDir Path dataFolder) throws Exception {
		Files.writeString(dataFolder.resolve("uploaded-file"), "content");
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		JobDataMap data = jobData(withFailingExport(rm), false, false);
		data.put("includeFiles", true);

		BackupJob.runBackup(jobContext(data));

		Path backup = onlyTimestampedBackup();
		assertMarkedFailed(backup);
		assertTrue(Files.exists(backup.resolve("uploaded-file")),
				"a failed export must not keep the files from being backed up");
	}

	@Test
	public void failedProvenanceExportMarksTheBackupFailedAndKeepsTheMainDump() throws Exception {
		RepositoryManagerImpl failing = spy(rm);
		doReturn(repositoryFailingMidExport()).when(failing).getProvenanceRepository();

		boolean succeeded = BackupJob.runBackup(jobContext(jobData(failing, false, false)));

		assertFalse(succeeded);
		Path backup = onlyTimestampedBackup();
		assertMarkedFailed(backup);
		assertTrue(Files.size(backup.resolve(DUMP)) > 0);
		assertFalse(Files.exists(backup.resolve("repository_prov." + RDFFormat.TRIX.getDefaultFileExtension())),
				"a partly written provenance dump must not be left behind");
	}

	@Test
	public void exportFailingWithAnErrorMarksTheBackupFailedAndRethrows() throws Exception {
		RepositoryManagerImpl failing = spy(rm);
		doReturn(repositoryFailingMidExport(new OutOfMemoryError("simulated"))).when(failing).getRepository();

		assertThrows(OutOfMemoryError.class, () -> BackupJob.runBackup(jobContext(jobData(failing, false, false))));

		Path backup = onlyTimestampedBackup();
		assertMarkedFailed(backup);
		assertFalse(Files.exists(backup.resolve(DUMP)));
	}

	@Test
	public void failedDateWriteKeepsThePreviousBackup(@TempDir Path dataFolder) throws Exception {
		completedBackup(backupFolder.resolve("all"));
		// copied into the new backup, a directory named BACKUP_DATE makes writing the date fail
		Files.createDirectory(dataFolder.resolve(BACKUP_DATE));
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataFolder.toString());
		JobDataMap data = jobData(rm, true, true);
		data.put("includeFiles", true);

		boolean succeeded = BackupJob.runBackup(jobContext(data));

		assertFalse(succeeded);
		assertTrue(Files.exists(backupFolder.resolve("all-old").resolve(MARKER)),
				"the previous backup must survive a failed date write");
		assertMarkedFailed(backupFolder.resolve("all"));
	}

	@Test
	public void maintenanceKeepsFailedBackupsNewerThanTheNewestCompletedOne() throws Exception {
		completedBackup(backupFolder.resolve("20200101000000"));
		completedBackup(backupFolder.resolve("20200102000000"));
		failedBackup(backupFolder.resolve("20200103000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(withFailingExport(rm), 3)));

		assertTrue(Files.exists(backupFolder.resolve("20200101000000")),
				"failed backups must not count towards the limits and push out a completed one");
		assertTrue(Files.exists(backupFolder.resolve("20200102000000")));
		assertTrue(Files.exists(backupFolder.resolve("20200103000000")),
				"a failed backup newer than the newest completed one may hold the newest repository dump");
		assertMarkedFailed(timestampedBackups().getLast());
	}

	@Test
	public void maintenanceCapsFailedBackupsAtTheUpperLimit() throws Exception {
		completedBackup(backupFolder.resolve("20200101000000"));
		failedBackup(backupFolder.resolve("20200102000000"));
		failedBackup(backupFolder.resolve("20200103000000"));
		failedBackup(backupFolder.resolve("20200104000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(withFailingExport(rm), 2)));

		assertTrue(Files.exists(backupFolder.resolve("20200101000000")));
		assertFalse(Files.exists(backupFolder.resolve("20200102000000")));
		assertFalse(Files.exists(backupFolder.resolve("20200103000000")));
		assertTrue(Files.exists(backupFolder.resolve("20200104000000")));
		assertMarkedFailed(timestampedBackups().getLast());
	}

	@Test
	public void maintenanceCountsBackupsWithoutMarkersAsCompleted() throws Exception {
		// written before 4.11, which had no BACKUP_DATE
		Path legacy = Files.createDirectories(backupFolder.resolve("20200101000000"));
		Files.writeString(legacy.resolve(DUMP), "");
		completedBackup(backupFolder.resolve("20200102000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(rm, 3)));

		assertTrue(Files.exists(legacy));
	}

	@Test
	public void maintenanceIgnoresFoldersWhoseNameOnlyStartsWithADate() throws Exception {
		Path copy = Files.createDirectories(backupFolder.resolve("20200101000000-copy"));
		Files.writeString(copy.resolve(BACKUP_FAILED), "20200101000000\nNo space left on device");

		new BackupJob().execute(jobContext(jobDataWithMaintenance(rm, 3)));

		assertTrue(Files.exists(copy));
	}

	@Test
	public void maintenanceDoesNotLetAnUnmarkedBackupSupersedeFailedOnes() throws Exception {
		completedBackup(backupFolder.resolve("20200101000000"));
		failedBackup(backupFolder.resolve("20200102000000"));
		// left by a killed run, possibly with a truncated dump
		Path unmarked = Files.createDirectories(backupFolder.resolve("20200103000000"));
		Files.writeString(unmarked.resolve(DUMP), "<TriX>");

		new BackupJob().execute(jobContext(jobDataWithMaintenance(withFailingExport(rm), 3)));

		assertTrue(Files.exists(backupFolder.resolve("20200102000000")),
				"a failed backup newer than the newest dated one may hold the newest repository dump");
	}

	@Test
	public void maintenanceCountsBackupsMarkedFailedByEarlierVersions() throws Exception {
		for (String name : List.of("20200101000000", "20200102000000")) {
			// earlier versions dated a backup whose data copy failed and marked it failed as well
			completedBackup(backupFolder.resolve(name));
			Files.writeString(backupFolder.resolve(name).resolve(BACKUP_FAILED), name + "\nAccessDeniedException");
		}

		new BackupJob().execute(jobContext(jobDataWithMaintenance(rm, 3)));

		assertTrue(Files.exists(backupFolder.resolve("20200101000000")));
		assertTrue(Files.exists(backupFolder.resolve("20200102000000")));
	}

	@Test
	public void maintenanceKeepsTheNewestFailedBackupsWhenNoneCompleted() throws Exception {
		failedBackup(backupFolder.resolve("20200101000000"));
		failedBackup(backupFolder.resolve("20200102000000"));
		failedBackup(backupFolder.resolve("20200103000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(withFailingExport(rm), 2)));

		assertFalse(Files.exists(backupFolder.resolve("20200101000000")));
		assertFalse(Files.exists(backupFolder.resolve("20200102000000")));
		assertTrue(Files.exists(backupFolder.resolve("20200103000000")));
		assertMarkedFailed(timestampedBackups().getLast());
	}

	@Test
	public void maintenanceDoesNotTakeADataDirectoryForAMarker() throws Exception {
		failedBackup(backupFolder.resolve("20200101000000"));
		completedBackup(backupFolder.resolve("20200102000000"));
		// copied from the data folder of a context named BACKUP_FAILED
		Files.createDirectory(backupFolder.resolve("20200102000000").resolve(BACKUP_FAILED));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(withFailingExport(rm), 3)));

		assertFalse(Files.exists(backupFolder.resolve("20200101000000")),
				"the completed 20200102000000 supersedes the older failed backup");
		assertTrue(Files.exists(backupFolder.resolve("20200102000000")));
	}

	@Test
	public void maintenanceCountsOnlyCompletedBackupsAfterARecovery() throws Exception {
		completedBackup(backupFolder.resolve("20200101000000"));
		completedBackup(backupFolder.resolve("20200102000000"));
		failedBackup(backupFolder.resolve("20200103000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(rm, 3)));

		assertTrue(Files.exists(backupFolder.resolve("20200101000000")),
				"three completed backups are within the upper limit of three");
		assertTrue(Files.exists(backupFolder.resolve("20200102000000")));
		assertFalse(Files.exists(backupFolder.resolve("20200103000000")));
		assertMarkedCompleted(timestampedBackups().getLast());
	}

	@Test
	public void maintenancePrunesTheOldestBackupAfterACompletedOne() throws Exception {
		completedBackup(backupFolder.resolve("20200101000000"));
		completedBackup(backupFolder.resolve("20200102000000"));

		new BackupJob().execute(jobContext(jobDataWithMaintenance(rm, 2)));

		assertMarkedCompleted(timestampedBackups().getLast());
		assertFalse(Files.exists(backupFolder.resolve("20200101000000")));
		assertTrue(Files.exists(backupFolder.resolve("20200102000000")));
	}

	/**
	 * The job data of a backup in TriX without files and maintenance, typed as {@link BackupScheduler} puts it:
	 * {@link BackupJob#execute} casts the values, and a wrongly typed one would be swallowed as a failed execution.
	 */
	private static JobDataMap jobData(RepositoryManagerImpl rm, boolean simple, boolean deleteAfter) {
		JobDataMap data = new JobDataMap();
		data.put("rm", rm);
		data.put("gzip", false);
		data.put("includeFiles", false);
		data.put("deleteAfter", deleteAfter);
		data.put("maintenance", false);
		data.put("format", RDFFormat.TRIX);
		data.put("simple", simple);
		return data;
	}

	/** Timestamped backups with maintenance that keeps at most {@code upperLimit} and lets none expire. */
	private static JobDataMap jobDataWithMaintenance(RepositoryManagerImpl rm, int upperLimit) {
		JobDataMap data = jobData(rm, false, false);
		data.put("maintenance", true);
		data.put("upperLimit", upperLimit);
		data.put("lowerLimit", 0);
		data.put("expiresAfterDays", 1_000_000);
		return data;
	}

	private static JobExecutionContext jobContext(JobDataMap data) {
		JobExecutionContext context = mock(JobExecutionContext.class);
		when(context.getJobDetail()).thenReturn(JobBuilder.newJob(BackupJob.class).usingJobData(data).build());
		return context;
	}

	/**
	 * {@code rm} with a main repository whose export fails partway, as on a full disk. The spy is not assigned to
	 * {@code rm}, so that tear-down shuts down the original.
	 */
	private static RepositoryManagerImpl withFailingExport(RepositoryManagerImpl rm) {
		RepositoryManagerImpl failing = spy(rm);
		doReturn(repositoryFailingMidExport()).when(failing).getRepository();
		return failing;
	}

	private static void assertMarkedFailed(Path backup) {
		assertTrue(Files.exists(backup.resolve(BACKUP_FAILED)), backup + " must be marked failed");
		assertFalse(Files.exists(backup.resolve(BACKUP_DATE)), backup + " must not be dated");
	}

	private static void assertMarkedCompleted(Path backup) {
		assertTrue(Files.exists(backup.resolve(BACKUP_DATE)), backup + " must be dated");
		assertFalse(Files.exists(backup.resolve(BACKUP_FAILED)), backup + " must not be marked failed");
	}

	private static void completedBackup(Path directory) throws IOException {
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(BACKUP_DATE), "20200101000000");
		Files.writeString(directory.resolve(MARKER), "");
	}

	private static void failedBackup(Path directory) throws IOException {
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(BACKUP_FAILED), "20200101000000\nNo space left on device");
	}

	private List<Path> timestampedBackups() throws IOException {
		try (Stream<Path> children = Files.list(backupFolder)) {
			return children.filter(child -> child.getFileName().toString().matches("\\d{14}")).sorted().toList();
		}
	}

	private Path onlyTimestampedBackup() throws IOException {
		List<Path> backups = timestampedBackups();
		assertEquals(1, backups.size(), "expected exactly one backup, found " + backups);
		return backups.getFirst();
	}

}
